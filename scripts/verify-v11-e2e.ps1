﻿#requires -Version 5.1
<#
.SYNOPSIS
    V11 端到端验收一键跑：三注册 → 登录 → 下单 → 未报名私聊 → 报名 → 录用 → 履约沟通 → 交付 → 验收结算 → 归档。

.DESCRIPTION
    前置条件（脚本不负责启动）：
      1. 应用已启动（mvn spring-boot:run），默认监听 127.0.0.1:8080；
      2. MySQL / Redis 可达，且 `escrow` 库已由 Flyway 升到 V23（chat_* 三表）；
      3. 已设置 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD（资质审核需要管理员会话）。

    覆盖：
      - 三个账号注册（雇主 + 两名劳动者）与登录；
      - 任务下单（草稿 → 发布）；
      - 未报名劳动者发起咨询、雇主回复、联系方式只标记不拦截、未读与已读；
      - 报名 / 录用自动建会话；1 名额 1 会话、两个会话消息不串；
      - 履约沟通 → 开始 → 今日交付 → 验收通过 → 结算 / 打款 → 余额到账；
      - 任务完成后全部会话归档，归档会话只读；
      - 未登录访问沟通接口 401。

    每条打印 [PASS] / [FAIL]；任一 FAIL 退出码为 1。脚本可重复执行（随机手机号）。

.NOTES
    注册口按 IP 限 5 次/分钟，本脚本注册 3 个账号，一分钟内重跑会被 429 拦截。
    演示口令由 ESCROW_DEMO_PASSWORD 提供，脚本内不保存任何凭据。
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'demo-config.ps1')
# 演示金额（分）：充值 10000.00 元；单价 300.00 元；3% 服务费 9.00 元；实发 291.00 元
$RechargeAmount = 1000000L
$UnitPrice = 30000L
$ExpectedNet = 29100L

$script:PassCount = 0
$script:FailCount = 0
$script:TempFiles = New-Object System.Collections.ArrayList

function Write-Check {
    param([bool]$Ok, [string]$Name, [string]$Detail)
    if ($Ok) {
        $script:PassCount++
        Write-Host ("[PASS] {0} | {1}" -f $Name, $Detail) -ForegroundColor Green
    }
    else {
        $script:FailCount++
        Write-Host ("[FAIL] {0} | {1}" -f $Name, $Detail) -ForegroundColor Red
    }
}

function New-TempFile {
    $file = Join-Path $env:TEMP ("verify-v11-" + [guid]::NewGuid().ToString('N') + ".tmp")
    [void]$script:TempFiles.Add($file)
    return $file
}

function Read-Utf8Text {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return '' }
    try { return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8) }
    catch { return '' }
}

# 一次 HTTP 调用：返回 状态码 / 响应体 / 响应体 JSON。请求体落 UTF-8 文件，避开 PS 5.1 引号与编码问题。
function Invoke-DemoRequest {
    param([string]$Method, [string]$Path, [hashtable]$Body, [string]$CookieFile)

    $bodyFile = New-TempFile
    $headerFile = New-TempFile
    $curlArgs = @(
        '--silent', '--show-error', '--max-time', '15',
        '-X', $Method, '-o', $bodyFile, '-D', $headerFile, '-w', '%{http_code}'
    )
    if ($CookieFile) {
        if (Test-Path $CookieFile) { $curlArgs += @('-b', $CookieFile) }
        $curlArgs += @('-c', $CookieFile)
    }
    if ($Body) {
        $json = ($Body | ConvertTo-Json -Compress -Depth 5)
        $payloadFile = New-TempFile
        [System.IO.File]::WriteAllText($payloadFile, $json, (New-Object System.Text.UTF8Encoding($false)))
        $curlArgs += @('-H', 'Content-Type: application/json', '--data-binary', "@$payloadFile")
    }
    $curlArgs += ($BaseUrl + $Path)

    $statusText = & curl.exe @curlArgs
    $status = 0
    [void][int]::TryParse(($statusText | Select-Object -Last 1), [ref]$status)

    $content = Read-Utf8Text -Path $bodyFile
    $jsonObject = $null
    if ($content) { try { $jsonObject = $content | ConvertFrom-Json } catch { $jsonObject = $null } }
    Remove-Item $bodyFile, $headerFile -Force -ErrorAction SilentlyContinue

    return [pscustomobject]@{ Status = $status; Content = $content; Json = $jsonObject }
}

function New-DemoPhone {
    param([string]$Prefix)
    return $Prefix + (Get-Random -Minimum 10000000 -Maximum 99999999)
}

function New-DemoAccount {
    param([string]$Role, [string]$Prefix, [string]$Nickname)
    $phone = New-DemoPhone -Prefix $Prefix
    $response = Invoke-DemoRequest -Method POST -Path '/api/identity/register' -Body @{
        phone    = $phone
        password = $DemoPassword
        nickname = $Nickname
        role     = $Role
    }
    return [pscustomobject]@{
        Phone  = $phone
        Status = $response.Status
        UserId = $response.Json.data.userId
        Code   = $response.Json.code
    }
}

function New-DemoSession {
    param([string]$Phone, [string]$Password)
    $jar = New-TempFile
    $login = Invoke-DemoRequest -Method POST -Path '/api/identity/login' -Body @{
        account  = $Phone
        password = $Password
    } -CookieFile $jar
    return [pscustomobject]@{ Status = $login.Status; Code = $login.Json.code; Jar = $jar }
}

# 在会话列表里按 scope 取第一个会话（列表按最后消息时间倒序，1 名额任务的会话唯一）
function Get-ConversationByScope {
    param($ListResponse, [string]$Scope)
    @($ListResponse.Json.data.items | Where-Object { $_.scope -eq $Scope }) | Select-Object -First 1
}

try {
    Write-Host "V11 端到端验收（$BaseUrl）" -ForegroundColor Cyan

    $adminPhone = $env:ADMIN_INIT_PHONE
    $adminPassword = $env:ADMIN_INIT_PASSWORD
    if (-not $adminPhone -or -not $adminPassword) {
        Write-Host '缺少 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD：资质审核需要管理员会话。' -ForegroundColor Red
        exit 2
    }

    # ------------------------------------------------------------ 1. 三个注册
    Write-Host "`n=== 1. 三个账号注册（雇主 + 两名劳动者） ===" -ForegroundColor Cyan
    $employer = New-DemoAccount -Role 'EMPLOYER' -Prefix '137' -Nickname 'demo-v11-employer'
    $worker1 = New-DemoAccount -Role 'WORKER' -Prefix '136' -Nickname 'demo-v11-worker1'
    $worker2 = New-DemoAccount -Role 'WORKER' -Prefix '135' -Nickname 'demo-v11-worker2'
    Write-Check ($employer.Status -eq 200 -and $employer.UserId) '1.1 注册雇主' `
        "status=$($employer.Status) userId=$($employer.UserId)"
    Write-Check ($worker1.Status -eq 200 -and $worker1.UserId) '1.2 注册劳动者 W1' `
        "status=$($worker1.Status) userId=$($worker1.UserId)"
    Write-Check ($worker2.Status -eq 200 -and $worker2.UserId) '1.3 注册劳动者 W2' `
        "status=$($worker2.Status) userId=$($worker2.UserId)"

    # ------------------------------------------------------------ 2. 登录
    Write-Host "`n=== 2. 三个账号登录（会话写入 Redis） ===" -ForegroundColor Cyan
    $employerSession = New-DemoSession -Phone $employer.Phone -Password $DemoPassword
    $worker1Session = New-DemoSession -Phone $worker1.Phone -Password $DemoPassword
    $worker2Session = New-DemoSession -Phone $worker2.Phone -Password $DemoPassword
    Write-Check ($employerSession.Status -eq 200) '2.1 雇主登录' "status=$($employerSession.Status)"
    Write-Check ($worker1Session.Status -eq 200) '2.2 W1 登录' "status=$($worker1Session.Status)"
    Write-Check ($worker2Session.Status -eq 200) '2.3 W2 登录' "status=$($worker2Session.Status)"

    # ------------------------------------------------------------ 3. 下单前置：资质 + 充值
    Write-Host "`n=== 3. 雇主资质审核 + 充值（下单前置） ===" -ForegroundColor Cyan
    $qualification = Invoke-DemoRequest -Method POST -Path '/api/identity/qualification' -Body @{
        subjectType = 'INDIVIDUAL'
        subjectName = 'demo-v11-employer'
        idLast4     = '1234'
    } -CookieFile $employerSession.Jar
    Write-Check ($qualification.Status -eq 200) '3.1 提交雇主资质' `
        "status=$($qualification.Status) code=$($qualification.Json.code)"

    $adminSession = New-DemoSession -Phone $adminPhone -Password $adminPassword
    $review = Invoke-DemoRequest -Method POST -Path "/api/admin/qualifications/$($employer.UserId)/review" `
        -Body @{ decision = 'APPROVED' } -CookieFile $adminSession.Jar
    Write-Check ($adminSession.Status -eq 200 -and $review.Status -eq 200) '3.2 管理员审核通过' `
        "status=$($review.Status) code=$($review.Json.code)"

    $recharge = Invoke-DemoRequest -Method POST -Path '/api/fund/recharge' `
        -Body @{ amount = $RechargeAmount } -CookieFile $employerSession.Jar
    Write-Check ($recharge.Status -eq 200 -and "$($recharge.Json.data.available)" -eq "$RechargeAmount") `
        '3.3 模拟充值 10000.00 元' "available=$($recharge.Json.data.available)"

    # ------------------------------------------------------------ 4. 任务下单
    Write-Host "`n=== 4. 任务下单：建草稿 → 发布 ===" -ForegroundColor Cyan
    $draft = Invoke-DemoRequest -Method POST -Path '/api/tasks' -Body @{
        title          = 'demo-v11 chat to cash task'
        description    = 'v11 end to end task'
        category       = 'demo'
        unitPrice      = $UnitPrice
        slotCount      = 1
        startTime      = (Get-Date).AddHours(1).ToString('yyyy-MM-ddTHH:mm:ss')
        endTime        = (Get-Date).AddDays(2).ToString('yyyy-MM-ddTHH:mm:ss')
        workLocation   = 'demo-location'
        settlementNote = 'demo-note'
    } -CookieFile $employerSession.Jar
    $taskId = $draft.Json.data.id
    $publish = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskId/publish" -CookieFile $employerSession.Jar
    Write-Check ($draft.Status -eq 200 -and $publish.Status -eq 200) '4.1 建草稿并发布（1 名额 300.00 元）' `
        "taskId=$taskId status=$($publish.Json.data.status)"

    # ------------------------------------------------------------ 5. 未报名私聊
    Write-Host "`n=== 5. 咨询阶段私聊（未报名即可聊） ===" -ForegroundColor Cyan
    $createInquiry = Invoke-DemoRequest -Method POST -Path '/api/conversations' -Body @{
        taskId = [long]$taskId
        scope  = 'INQUIRY'
    } -CookieFile $worker1Session.Jar
    $inquiryId = $createInquiry.Json.data.id
    Write-Check ($createInquiry.Status -eq 200 -and $createInquiry.Json.data.scope -eq 'INQUIRY') `
        '5.1 W1 未报名发起咨询，会话创建成功' `
        "status=$($createInquiry.Status) scope=$($createInquiry.Json.data.scope) conversationId=$inquiryId"

    $send1 = Invoke-DemoRequest -Method POST -Path "/api/conversations/$inquiryId/messages" `
        -Body @{ content = 'hello, is this task still open?' } -CookieFile $worker1Session.Jar
    $reply = Invoke-DemoRequest -Method POST -Path "/api/conversations/$inquiryId/messages" `
        -Body @{ content = 'yes it is. call 13800001111 to confirm' } -CookieFile $employerSession.Jar
    Write-Check ($send1.Status -eq 200 -and $reply.Status -eq 200 -and $reply.Json.data.contactHint -eq $true) `
        '5.2 双方互发成功；疑似联系方式只标记不拦截' `
        "send=$($send1.Status) reply=$($reply.Status) contactHint=$($reply.Json.data.contactHint)"

    $messages = Invoke-DemoRequest -Method GET -Path "/api/conversations/$inquiryId/messages?page=1&size=20" `
        -CookieFile $worker1Session.Jar
    $contents = @($messages.Json.data.items | ForEach-Object { $_.content })
    Write-Check ($messages.Status -eq 200 -and $contents.Count -eq 2 -and
        $contents[0] -eq 'hello, is this task still open?') `
        '5.3 消息正序可读（2 条，先发先读）' "count=$($contents.Count) first=$($contents[0])"

    $unreadBefore = Invoke-DemoRequest -Method GET -Path '/api/conversations/unread-count' -CookieFile $worker1Session.Jar
    $markRead = Invoke-DemoRequest -Method POST -Path "/api/conversations/$inquiryId/read" -CookieFile $worker1Session.Jar
    $unreadAfter = Invoke-DemoRequest -Method GET -Path '/api/conversations/unread-count' -CookieFile $worker1Session.Jar
    Write-Check ("$($unreadBefore.Json.data.unreadCount)" -eq '1' -and
        "$($unreadAfter.Json.data.unreadCount)" -eq '0') '5.4 未读 1 → 标记已读后 0' `
        "before=$($unreadBefore.Json.data.unreadCount) after=$($unreadAfter.Json.data.unreadCount) mark=$($markRead.Status)"

    # ------------------------------------------------------------ 6. 报名 → 录用自动建会话
    Write-Host "`n=== 6. 报名 / 录用自动建会话（APPLICATION / CONTRACT） ===" -ForegroundColor Cyan
    $apply = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskId/applications" -CookieFile $worker1Session.Jar
    $applicationId = $apply.Json.data.id
    $listAfterApply = Invoke-DemoRequest -Method GET -Path '/api/conversations?page=1&size=50' -CookieFile $worker1Session.Jar
    $applicationConversation = Get-ConversationByScope -ListResponse $listAfterApply -Scope 'APPLICATION'
    Write-Check ($apply.Status -eq 200 -and $null -ne $applicationConversation) `
        '6.1 W1 报名成功，APPLICATION 会话自动创建' `
        "apply=$($apply.Status) total=$($listAfterApply.Json.data.total)"

    $hire = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskId/applications/$applicationId/hire" `
        -CookieFile $employerSession.Jar
    $contractId = $hire.Json.data.contractId
    $listAfterHire = Invoke-DemoRequest -Method GET -Path '/api/conversations?page=1&size=50' -CookieFile $worker1Session.Jar
    $contractConversation = Get-ConversationByScope -ListResponse $listAfterHire -Scope 'CONTRACT'
    Write-Check ($hire.Status -eq 200 -and $null -ne $contractConversation -and
        "$($listAfterHire.Json.data.total)" -eq '3') `
        '6.2 录用成功，CONTRACT 会话自动创建（共 3 个会话）' `
        "hire=$($hire.Status) contractId=$contractId total=$($listAfterHire.Json.data.total)"

    # ------------------------------------------------------------ 7. 履约沟通不串会话
    Write-Host "`n=== 7. 跨阶段会话隔离 ===" -ForegroundColor Cyan
    $contractMessage = Invoke-DemoRequest -Method POST `
        -Path "/api/conversations/$($contractConversation.id)/messages" `
        -Body @{ content = 'I will start now, deliver before 18:00 today' } -CookieFile $worker1Session.Jar
    $contractMessages = Invoke-DemoRequest -Method GET `
        -Path "/api/conversations/$($contractConversation.id)/messages?page=1&size=20" -CookieFile $employerSession.Jar
    $inquiryMessagesAgain = Invoke-DemoRequest -Method GET `
        -Path "/api/conversations/$inquiryId/messages?page=1&size=20" -CookieFile $employerSession.Jar
    Write-Check ($contractMessage.Status -eq 200 -and
        @($contractMessages.Json.data.items).Count -eq 1 -and
        @($inquiryMessagesAgain.Json.data.items).Count -eq 2) `
        '7.1 CONTRACT 与 INQUIRY 消息不串（1 条 / 2 条）' `
        "contract=$(@($contractMessages.Json.data.items).Count) inquiry=$(@($inquiryMessagesAgain.Json.data.items).Count)"

    # ------------------------------------------------------------ 8. 今天交付 → 验收 → 结算
    Write-Host "`n=== 8. 履约 → 今日交付 → 验收 → 结算打款 ===" -ForegroundColor Cyan
    $start = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/start" -CookieFile $worker1Session.Jar
    $deliver = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/deliver" -Body @{
        images      = @('https://img.example.com/demo-v11.jpg')
        description = 'delivered today'
    } -CookieFile $worker1Session.Jar
    Write-Check ($start.Status -eq 200 -and $deliver.Status -eq 200 -and
        $deliver.Json.data.status -eq 'CONTRACT_DELIVERED') `
        '8.1 开始履约并提交交付物' "start=$($start.Status) deliver=$($deliver.Status)"

    $detailPending = Invoke-DemoRequest -Method GET -Path "/api/tasks/$taskId" -CookieFile $employerSession.Jar
    Write-Check ($detailPending.Json.data.status -eq 'TASK_PENDING_ACCEPTANCE') `
        '8.2 任务详情派生「待验收」' "status=$($detailPending.Json.data.status)"

    $accept = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/acceptance" `
        -Body @{ decision = 'PASS' } -CookieFile $employerSession.Jar
    Write-Check ($accept.Status -eq 200 -and $accept.Json.data.contractStatus -eq 'CONTRACT_COMPLETED' -and
        $accept.Json.data.slotStatus -eq 'SLOT_COMPLETED' -and
        $null -ne $accept.Json.data.settlementId -and $null -ne $accept.Json.data.payoutId) `
        '8.3 验收通过：签约完成、名额完结、生成结算与打款单' `
        "contract=$($accept.Json.data.contractStatus) settlementId=$($accept.Json.data.settlementId) payoutId=$($accept.Json.data.payoutId)"

    $detailDone = Invoke-DemoRequest -Method GET -Path "/api/tasks/$taskId" -CookieFile $employerSession.Jar
    $workerBalance = Invoke-DemoRequest -Method GET -Path '/api/fund/accounts/me' -CookieFile $worker1Session.Jar
    $employerBalance = Invoke-DemoRequest -Method GET -Path '/api/fund/accounts/me' -CookieFile $employerSession.Jar
    Write-Check ($detailDone.Json.data.status -eq 'TASK_COMPLETED') `
        '8.4 任务详情「已完成」' "status=$($detailDone.Json.data.status)"
    Write-Check ("$($workerBalance.Json.data.available)" -eq "$ExpectedNet" -and
        "$($employerBalance.Json.data.frozen)" -eq '0') `
        '8.5 劳动者到账 291.00 元（300.00 − 3%），雇主冻结清零' `
        "workerAvailable=$($workerBalance.Json.data.available) employerFrozen=$($employerBalance.Json.data.frozen)"

    # ------------------------------------------------------------ 9. 归档只读
    Write-Host "`n=== 9. 任务终态 → 会话归档只读 ===" -ForegroundColor Cyan
    $listFinal = Invoke-DemoRequest -Method GET -Path '/api/conversations?page=1&size=50' -CookieFile $worker1Session.Jar
    $statuses = @($listFinal.Json.data.items | ForEach-Object { $_.status })
    $allArchived = ($statuses.Count -gt 0) -and (@($statuses | Where-Object { $_ -ne 'ARCHIVED' }).Count -eq 0)
    Write-Check $allArchived '9.1 任务完成后该任务全部会话归档' `
        ("statuses=" + ($statuses -join ','))

    $sendArchived = Invoke-DemoRequest -Method POST -Path "/api/conversations/$($contractConversation.id)/messages" `
        -Body @{ content = 'can I still send?' } -CookieFile $worker1Session.Jar
    Write-Check ($sendArchived.Status -eq 403 -and $sendArchived.Json.code -eq 'FORBIDDEN') `
        '9.2 归档会话发送被拒（只读）' "status=$($sendArchived.Status) code=$($sendArchived.Json.code)"

    # ------------------------------------------------------------ 10. 未登录
    Write-Host "`n=== 10. 未登录访问沟通接口 ===" -ForegroundColor Cyan
    $anonymous = Invoke-DemoRequest -Method GET -Path '/api/conversations'
    Write-Check ($anonymous.Status -eq 401) '10.1 未登录访问 /api/conversations 返回 401' `
        "status=$($anonymous.Status) code=$($anonymous.Json.code)"
}
finally {
    foreach ($file in $script:TempFiles) {
        Remove-Item $file -Force -ErrorAction SilentlyContinue
    }
}

Write-Host ''
Write-Host ("汇总：PASS {0} / FAIL {1}" -f $script:PassCount, $script:FailCount) `
    -ForegroundColor ($(if ($script:FailCount -gt 0) { 'Red' } else { 'Green' }))
if ($script:FailCount -gt 0) { exit 1 }
exit 0
