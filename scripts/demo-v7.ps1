#requires -Version 5.1
<#
.SYNOPSIS
    V7 演示一键跑（v7-c5）。只用 curl.exe，兼容 Windows PowerShell 5.1。

.DESCRIPTION
    前置条件（脚本不负责启动）：
      1. 应用已启动（mvn spring-boot:run），默认监听 127.0.0.1:8080；
      2. MySQL 与 Redis 可达（docker compose --profile core up -d）；
      3. 已设置 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD。

    演示分段：
      0. 准备：雇主注册 → 资质审核 → 充值 → 劳动者注册 → 发布 → 录用 → 交付 → 验收通过
      1. 评价与信誉（FR-REPUT.RATE.01 / .02）：双方互评、重复评价 409、非当事人 403、信誉聚合
      2. 站内信（FR-MSG.LIST.01）：打款通知可查、未读数、标记已读与全部已读
      3. 账号治理（FR-ADMIN.USER.01）：检索、禁用后登录 403、重复禁用 409、启用恢复
      4. 看板与审计（治理看板 / FR-ADMIN.AUDIT.01）：五项计数、USER 审计流水与 CSV 导出

.NOTES
    每次运行注册 2 个随机手机号的新账号（雇主 + 1 名劳动者），可重复执行。
    注册口按 IP 限 5 次/分钟：一分钟内连续重跑 3 次会被 429 拦截，等窗口过去再执行。
    演示口令由 ESCROW_DEMO_PASSWORD 提供，脚本内不保存任何凭据。
    站内信默认 escrow.notify.mode=log：V7 起 log 模式同步写收件箱，不依赖 Kafka。
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'demo-config.ps1')
$RechargeAmount = 1000000L
$UnitPrice = 30000L

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
    $file = Join-Path $env:TEMP ("demo-v7-" + [guid]::NewGuid().ToString('N') + ".tmp")
    [void]$script:TempFiles.Add($file)
    return $file
}

function Read-Utf8Text {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return '' }
    try { return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8) }
    catch { return '' }
}

function Invoke-DemoRequest {
    param(
        [string]$Method,
        [string]$Path,
        [hashtable]$Body,
        [string]$CookieFile
    )

    $bodyFile = New-TempFile
    $headerFile = New-TempFile
    $curlArgs = @(
        '--silent', '--show-error',
        '--max-time', '15',
        '-X', $Method,
        '-o', $bodyFile,
        '-D', $headerFile,
        '-w', '%{http_code}'
    )

    if ($CookieFile) {
        if (Test-Path $CookieFile) { $curlArgs += @('-b', $CookieFile) }
        $curlArgs += @('-c', $CookieFile)
    }
    if ($Body) {
        $json = ($Body | ConvertTo-Json -Compress)
        $payloadFile = New-TempFile
        [System.IO.File]::WriteAllText($payloadFile, $json, (New-Object System.Text.UTF8Encoding($false)))
        $curlArgs += @('-H', 'Content-Type: application/json', '--data-binary', "@$payloadFile")
    }
    $curlArgs += ($BaseUrl + $Path)

    $statusText = & curl.exe @curlArgs
    $status = 0
    [void][int]::TryParse(($statusText | Select-Object -Last 1), [ref]$status)

    $content = Read-Utf8Text -Path $bodyFile
    $headers = Read-Utf8Text -Path $headerFile

    $jsonObject = $null
    if ($content) {
        try { $jsonObject = $content | ConvertFrom-Json } catch { $jsonObject = $null }
    }

    Remove-Item $bodyFile, $headerFile -Force -ErrorAction SilentlyContinue

    return [pscustomobject]@{
        Status  = $status
        Content = $content
        Json    = $jsonObject
        Headers = $headers
    }
}

function New-DemoPhone {
    return '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
}

function New-DemoAccount {
    param([string]$Role)
    $phone = New-DemoPhone
    $response = Invoke-DemoRequest -Method POST -Path '/api/identity/register' -Body @{
        phone    = $phone
        password = $DemoPassword
        nickname = "demo-v7-$Role".ToLower()
        role     = $Role
    }
    return [pscustomobject]@{
        Phone  = $phone
        Status = $response.Status
        UserId = $response.Json.data.userId
    }
}

function New-DemoSession {
    param([string]$Phone, [string]$Password)
    $jar = New-TempFile
    $login = Invoke-DemoRequest -Method POST -Path '/api/identity/login' -Body @{
        account  = $Phone
        password = $Password
    } -CookieFile $jar
    return [pscustomobject]@{
        Status = $login.Status
        Json   = $login.Json
        Jar    = $jar
    }
}

try {
    Write-Host "V7 演示脚本（$BaseUrl）" -ForegroundColor Cyan

    $adminPhone = $env:ADMIN_INIT_PHONE
    $adminPassword = $env:ADMIN_INIT_PASSWORD
    if (-not $adminPhone -or -not $adminPassword) {
        Write-Host '缺少 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD：资质审核、账号治理与看板需要管理员会话。' -ForegroundColor Red
        exit 2
    }

    # ---------------------------------------------------------------- 演示 0：准备
    Write-Host "`n=== 演示 0：资质 + 充值 + 任务验收（2 个新账号） ===" -ForegroundColor Cyan

    $employer = New-DemoAccount -Role 'EMPLOYER'
    $employerSession = New-DemoSession -Phone $employer.Phone -Password $DemoPassword
    $script:EmployerJar = $employerSession.Jar
    Write-Check ($employer.Status -eq 200 -and $employerSession.Status -eq 200) '0.1 注册并登录雇主' `
        "status=$($employerSession.Status) userId=$($employer.UserId)"

    $qualification = Invoke-DemoRequest -Method POST -Path '/api/identity/qualification' -Body @{
        subjectType = 'INDIVIDUAL'
        subjectName = 'demo-v7-employer'
        idLast4     = '1234'
    } -CookieFile $script:EmployerJar
    Write-Check ($qualification.Status -eq 200) '0.2 提交雇主资质' `
        "status=$($qualification.Status) code=$($qualification.Json.code)"

    $adminSession = New-DemoSession -Phone $adminPhone -Password $adminPassword
    $script:AdminJar = $adminSession.Jar
    $review = Invoke-DemoRequest -Method POST -Path "/api/admin/qualifications/$($employer.UserId)/review" `
        -Body @{ decision = 'APPROVED' } -CookieFile $script:AdminJar
    Write-Check ($adminSession.Status -eq 200 -and $review.Status -eq 200) '0.3 管理员审核通过' `
        "status=$($review.Status) code=$($review.Json.code)"

    $recharge = Invoke-DemoRequest -Method POST -Path '/api/fund/recharge' `
        -Body @{ amount = $RechargeAmount } -CookieFile $script:EmployerJar
    Write-Check ($recharge.Status -eq 200 -and "$($recharge.Json.data.available)" -eq "$RechargeAmount") `
        '0.4 模拟充值 10000.00 元' "available=$($recharge.Json.data.available)"

    $worker = New-DemoAccount -Role 'WORKER'
    $workerSession = New-DemoSession -Phone $worker.Phone -Password $DemoPassword
    $script:WorkerJar = $workerSession.Jar
    Write-Check ($workerSession.Status -eq 200) '0.5 注册并登录劳动者' "userId=$($worker.UserId)"

    $draft = Invoke-DemoRequest -Method POST -Path '/api/tasks' -Body @{
        title          = 'demo-v7 review task'
        description    = 'v7 demo task'
        category       = 'demo'
        unitPrice      = $UnitPrice
        slotCount      = 1
        startTime      = (Get-Date).AddDays(1).ToString('yyyy-MM-ddTHH:mm:ss')
        endTime        = (Get-Date).AddDays(2).ToString('yyyy-MM-ddTHH:mm:ss')
        workLocation   = 'demo-location'
        settlementNote = 'demo-note'
    } -CookieFile $script:EmployerJar
    $taskId = $draft.Json.data.id
    $publish = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskId/publish" -CookieFile $script:EmployerJar
    $apply = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskId/applications" -CookieFile $script:WorkerJar
    $hire = Invoke-DemoRequest -Method POST `
        -Path "/api/tasks/$taskId/applications/$($apply.Json.data.id)/hire" -CookieFile $script:EmployerJar
    $contractId = $hire.Json.data.contractId
    [void](Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/start" -CookieFile $script:WorkerJar)
    $deliver = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/deliver" -Body @{
        images      = @('https://img.example.com/demo-v7.jpg')
        description = 'delivered by demo-v7'
    } -CookieFile $script:WorkerJar
    $accept = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/acceptance" `
        -Body @{ decision = 'PASS' } -CookieFile $script:EmployerJar
    Write-Check ($publish.Status -eq 200 -and $apply.Status -eq 200 -and $hire.Status -eq 200 -and
        $deliver.Status -eq 200 -and $accept.Status -eq 200 -and
        $accept.Json.data.contractStatus -eq 'CONTRACT_COMPLETED') `
        '0.6 发布 → 报名 → 录用 → 交付 → 验收通过' `
        "taskId=$taskId contractId=$contractId settlementId=$($accept.Json.data.settlementId)"

    # ---------------------------------------------------------------- 演示 1：评价与信誉
    Write-Host "`n=== 演示 1：双方互评、重复评价与信誉聚合（FR-REPUT.RATE.01 / .02） ===" -ForegroundColor Cyan

    $workerReview = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/reviews" `
        -Body @{ rating = 5; comment = '结算及时，沟通顺畅' } -CookieFile $script:WorkerJar
    Write-Check ($workerReview.Status -eq 200 -and $workerReview.Json.data.rating -eq 5) `
        '1.1 劳动者评价雇主 5 分' "status=$($workerReview.Status)"

    $employerReview = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/reviews" `
        -Body @{ rating = 4; comment = '交付合格' } -CookieFile $script:EmployerJar
    Write-Check ($employerReview.Status -eq 200 -and $employerReview.Json.data.rating -eq 4) `
        '1.2 雇主评价劳动者 4 分' "status=$($employerReview.Status)"

    $duplicate = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/reviews" `
        -Body @{ rating = 3; comment = '重复评价' } -CookieFile $script:WorkerJar
    Write-Check ($duplicate.Status -eq 409 -and $duplicate.Json.code -eq 'CONFLICT') `
        '1.3 同一签约重复评价 409' "status=$($duplicate.Status) code=$($duplicate.Json.code)"

    $strangerReview = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/reviews" `
        -Body @{ rating = 5 } -CookieFile $script:AdminJar
    Write-Check ($strangerReview.Status -eq 403 -and $strangerReview.Json.code -eq 'FORBIDDEN') `
        '1.4 非签约当事人评价 403' "status=$($strangerReview.Status) code=$($strangerReview.Json.code)"

    $reputation = Invoke-DemoRequest -Method GET -Path "/api/users/$($worker.UserId)/reputation?page=1&size=10" `
        -CookieFile $script:WorkerJar
    Write-Check ($reputation.Status -eq 200 -and ([int]$reputation.Json.data.completedContracts) -ge 1 -and
        ([int]$reputation.Json.data.reviewCount) -ge 1 -and
        ([int]$reputation.Json.data.averageRatingX100) -eq 400) `
        '1.5 劳动者信誉：完成数 ≥1、平均分 4.00' `
        "completed=$($reputation.Json.data.completedContracts) ratingX100=$($reputation.Json.data.averageRatingX100)"

    # ---------------------------------------------------------------- 演示 2：站内信
    Write-Host "`n=== 演示 2：站内信查询与已读（FR-MSG.LIST.01） ===" -ForegroundColor Cyan

    $inbox = Invoke-DemoRequest -Method GET -Path '/api/notifications?page=1&size=20' -CookieFile $script:WorkerJar
    $inboxTotal = $inbox.Json.data.total
    Write-Check ($inbox.Status -eq 200 -and ([int]$inboxTotal) -ge 1) `
        '2.1 劳动者收件箱有打款通知' "total=$inboxTotal"

    $unreadBefore = Invoke-DemoRequest -Method GET -Path '/api/notifications/unread-count' -CookieFile $script:WorkerJar
    Write-Check (([int]$unreadBefore.Json.data.unreadCount) -ge 1) `
        '2.2 未读数 ≥1' "unread=$($unreadBefore.Json.data.unreadCount)"

    $firstId = $inbox.Json.data.items[0].id
    $readOne = Invoke-DemoRequest -Method POST -Path "/api/notifications/$firstId/read" -CookieFile $script:WorkerJar
    $readAll = Invoke-DemoRequest -Method POST -Path '/api/notifications/read-all' -CookieFile $script:WorkerJar
    $unreadAfter = Invoke-DemoRequest -Method GET -Path '/api/notifications/unread-count' -CookieFile $script:WorkerJar
    Write-Check ($readOne.Status -eq 200 -and $readAll.Status -eq 200 -and
        "$($unreadAfter.Json.data.unreadCount)" -eq '0') `
        '2.3 标记已读 + 全部已读后未读为 0' `
        "readAll=$($readAll.Json.data.updated) unread=$($unreadAfter.Json.data.unreadCount)"

    # ---------------------------------------------------------------- 演示 3：账号治理
    Write-Host "`n=== 演示 3：账号检索、禁用与启用（FR-ADMIN.USER.01） ===" -ForegroundColor Cyan

    $users = Invoke-DemoRequest -Method GET `
        -Path "/api/admin/users?keyword=$($worker.Phone)&page=1&size=10" -CookieFile $script:AdminJar
    Write-Check ($users.Status -eq 200 -and "$($users.Json.data.total)" -eq '1' -and
        "$($users.Json.data.items[0].userId)" -eq "$($worker.UserId)") `
        '3.1 管理员按手机号检索到目标账号' `
        "status=$($users.Status) total=$($users.Json.data.total)"

    $workerUsers = Invoke-DemoRequest -Method GET -Path '/api/admin/users?page=1&size=10' -CookieFile $script:WorkerJar
    Write-Check ($workerUsers.Status -eq 403 -and $workerUsers.Json.code -eq 'FORBIDDEN') `
        '3.2 非管理员查账号列表 403' "status=$($workerUsers.Status) code=$($workerUsers.Json.code)"

    $blankReason = Invoke-DemoRequest -Method POST -Path "/api/admin/users/$($worker.UserId)/disable" `
        -Body @{ reason = '  ' } -CookieFile $script:AdminJar
    Write-Check ($blankReason.Status -eq 400) `
        '3.3 禁用不填原因 400' "status=$($blankReason.Status) code=$($blankReason.Json.code)"

    $disable = Invoke-DemoRequest -Method POST -Path "/api/admin/users/$($worker.UserId)/disable" `
        -Body @{ reason = '发布违规内容（演示）' } -CookieFile $script:AdminJar
    Write-Check ($disable.Status -eq 200 -and $disable.Json.data.status -eq 'DISABLED') `
        '3.4 管理员禁用账号' "status=$($disable.Status) userStatus=$($disable.Json.data.status)"

    $blockedLogin = New-DemoSession -Phone $worker.Phone -Password $DemoPassword
    Write-Check ($blockedLogin.Status -eq 403) `
        '3.5 禁用后登录 403' "status=$($blockedLogin.Status) code=$($blockedLogin.Json.code)"

    $disableAgain = Invoke-DemoRequest -Method POST -Path "/api/admin/users/$($worker.UserId)/disable" `
        -Body @{ reason = '重复禁用' } -CookieFile $script:AdminJar
    Write-Check ($disableAgain.Status -eq 409 -and $disableAgain.Json.code -eq 'CONFLICT') `
        '3.6 重复禁用 409' "status=$($disableAgain.Status) code=$($disableAgain.Json.code)"

    $enable = Invoke-DemoRequest -Method POST -Path "/api/admin/users/$($worker.UserId)/enable" `
        -Body @{ reason = '申诉通过（演示）' } -CookieFile $script:AdminJar
    $restoredLogin = New-DemoSession -Phone $worker.Phone -Password $DemoPassword
    Write-Check ($enable.Status -eq 200 -and $enable.Json.data.status -eq 'ACTIVE' -and
        $restoredLogin.Status -eq 200) `
        '3.7 启用后可正常登录' `
        "status=$($enable.Json.data.status) login=$($restoredLogin.Status)"

    # ---------------------------------------------------------------- 演示 4：看板与审计
    Write-Host "`n=== 演示 4：治理看板与审计流水（治理看板 / FR-ADMIN.AUDIT.01） ===" -ForegroundColor Cyan

    $dashboard = Invoke-DemoRequest -Method GET -Path '/api/admin/dashboard' -CookieFile $script:AdminJar
    Write-Check ($dashboard.Status -eq 200 -and ([int]$dashboard.Json.data.userCount) -ge 3 -and
        ([int]$dashboard.Json.data.taskCount) -ge 1) `
        '4.1 看板五项计数可查' `
        "users=$($dashboard.Json.data.userCount) tasks=$($dashboard.Json.data.taskCount) payout=$($dashboard.Json.data.pendingPayoutCount)"

    $workerDashboard = Invoke-DemoRequest -Method GET -Path '/api/admin/dashboard' -CookieFile $script:WorkerJar
    Write-Check ($workerDashboard.Status -eq 403 -and $workerDashboard.Json.code -eq 'FORBIDDEN') `
        '4.2 非管理员看板 403' "status=$($workerDashboard.Status) code=$($workerDashboard.Json.code)"

    $audits = Invoke-DemoRequest -Method GET -Path '/api/admin/audit?bizType=USER&page=1&size=20' `
        -CookieFile $script:AdminJar
    $hasDisable = $false
    foreach ($item in @($audits.Json.data.items)) {
        if ($item.bizNo -eq "$($worker.UserId)" -and $item.action -eq 'DISABLE') { $hasDisable = $true }
    }
    Write-Check ($audits.Status -eq 200 -and $hasDisable) `
        '4.3 审计流水含 USER/DISABLE 记录' `
        "total=$($audits.Json.data.total) hasDisable=$hasDisable"

    $csv = Invoke-DemoRequest -Method GET -Path '/api/admin/audit/export?bizType=USER' -CookieFile $script:AdminJar
    Write-Check ($csv.Status -eq 200 -and $csv.Content -like '*USER*DISABLE*') `
        '4.4 审计 CSV 导出包含 USER 流水' "status=$($csv.Status) bytes=$($csv.Content.Length)"

    Write-Host ("`n合计：PASS={0} FAIL={1}" -f $script:PassCount, $script:FailCount) `
        -ForegroundColor ($(if ($script:FailCount -eq 0) { 'Green' } else { 'Red' }))
    if ($script:FailCount -gt 0) { exit 1 }
}
finally {
    foreach ($file in $script:TempFiles) {
        Remove-Item $file -Force -ErrorAction SilentlyContinue
    }
}
