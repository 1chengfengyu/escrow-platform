#requires -Version 5.1
<#
.SYNOPSIS
    V5 演示一键跑（v5-c6）。只用 curl.exe，兼容 Windows PowerShell 5.1。

.DESCRIPTION
    前置条件（脚本不负责启动）：
      1. 应用已启动（mvn spring-boot:run），默认监听 127.0.0.1:8080；
      2. MySQL 与 Redis 可达（docker compose --profile core up -d）；
      3. 已设置 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD（处置台与资质审核需要管理员会话）。

    演示分段（默认 SUCCESS 模式）：
      0. 准备：雇主注册 → 资质审核 → 充值 → 劳动者注册
      1. 验收通过 → 结算 → 打款成功（管理端按状态可查到该笔）
      2. 劳动者到账 291.00 元，雇主冻结清零
      3. 非管理员访问处置台 403 FORBIDDEN（只认会话里的当前身份）
      4. 管理员查账龄告警：200 且返回数组（无超龄单据时为空）
      5. 重复验收 409，不重复打款

    未知态演示（可选）：以未知模式启动应用后再跑本脚本
      java -jar ... --escrow.payout.gateway-mode=TIMEOUT
      PowerShell: $env:DEMO_PAYOUT_MODE = 'TIMEOUT'
      脚本改为断言「验收后打款单进入待查证、结算仍在打款中」；
      查证 → 判失败 → 新请求号重试 → 超限转人工 的完整链路由真库用例证明
      （V5VerificationIntegrationTest / V5ManualDecisionIntegrationTest），
      定时任务默认 30 秒一轮，不必在 5 分钟演示里等完 3 次尝试。

.NOTES
    每次运行注册 2 个随机手机号的新账号（雇主 + 1 名劳动者），可重复执行。
    注册口按 IP 限 5 次/分钟：一分钟内连续重跑 3 次会被 429 拦截，等窗口过去再执行。
    演示口令由 ESCROW_DEMO_PASSWORD 提供，脚本内不保存任何凭据。
    站内信默认 escrow.notify.mode=log，只在应用日志里出现
    「站内信[log模式] ... bizNo=<结算单号>」，可用结算单号 grep 核对。
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'demo-config.ps1')
$RechargeAmount = 1000000L
$UnitPrice = 30000L
$ExpectedNet = 29100L

# SUCCESS（默认）或 TIMEOUT / LOST_RESPONSE / CHANNEL_MISSING 等未知模式
$PayoutMode = if ($env:DEMO_PAYOUT_MODE) { $env:DEMO_PAYOUT_MODE.ToUpperInvariant() } else { 'SUCCESS' }
$UnknownModes = @('TIMEOUT', 'LOST_RESPONSE', 'CHANNEL_MISSING')

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
    $file = Join-Path $env:TEMP ("demo-v5-" + [guid]::NewGuid().ToString('N') + ".tmp")
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
        nickname = "demo-v5-$Role".ToLower()
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
        Jar    = $jar
    }
}

function New-DemoDraft {
    param([string]$Title, [int]$Slots)
    $body = @{
        title          = $Title
        description    = 'v5 demo task'
        category       = 'demo'
        unitPrice      = $UnitPrice
        slotCount      = $Slots
        startTime      = (Get-Date).AddDays(1).ToString('yyyy-MM-ddTHH:mm:ss')
        endTime        = (Get-Date).AddDays(2).ToString('yyyy-MM-ddTHH:mm:ss')
        workLocation   = 'demo-location'
        settlementNote = 'demo-note'
    }
    return Invoke-DemoRequest -Method POST -Path '/api/tasks' -Body $body -CookieFile $script:EmployerJar
}

# 管理端按状态查打款单（分页第一页）
function Find-AdminPayout {
    param([string]$Status)
    $response = Invoke-DemoRequest -Method GET `
        -Path "/api/admin/payouts?status=$Status&page=1&size=20" -CookieFile $script:AdminJar
    if ($response.Status -ne 200) { return $null }
    return $response
}

try {
    Write-Host "V5 演示脚本（$BaseUrl，payout mode=$PayoutMode）" -ForegroundColor Cyan

    $adminPhone = $env:ADMIN_INIT_PHONE
    $adminPassword = $env:ADMIN_INIT_PASSWORD
    if (-not $adminPhone -or -not $adminPassword) {
        Write-Host '缺少 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD：资质审核与处置台需要管理员会话。' -ForegroundColor Red
        exit 2
    }

    # ---------------------------------------------------------------- 演示 0：准备
    Write-Host "`n=== 演示 0：雇主资质 + 充值 + 劳动者（2 个新账号） ===" -ForegroundColor Cyan

    $employer = New-DemoAccount -Role 'EMPLOYER'
    $employerSession = New-DemoSession -Phone $employer.Phone -Password $DemoPassword
    $script:EmployerJar = $employerSession.Jar
    Write-Check ($employer.Status -eq 200 -and $employerSession.Status -eq 200) '0.1 注册并登录雇主' `
        "status=$($employerSession.Status) userId=$($employer.UserId)"

    $qualification = Invoke-DemoRequest -Method POST -Path '/api/identity/qualification' -Body @{
        subjectType = 'INDIVIDUAL'
        subjectName = 'demo-v5-employer'
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
        -Body @{ amount = $RechargeAmount; requestId = [guid]::NewGuid().ToString('N') } -CookieFile $script:EmployerJar
    Write-Check ($recharge.Status -eq 200 -and "$($recharge.Json.data.available)" -eq "$RechargeAmount") `
        '0.4 模拟充值 10000.00 元' "available=$($recharge.Json.data.available)"

    $worker = New-DemoAccount -Role 'WORKER'
    $workerSession = New-DemoSession -Phone $worker.Phone -Password $DemoPassword
    $script:WorkerJar = $workerSession.Jar
    Write-Check ($workerSession.Status -eq 200) '0.5 注册并登录劳动者' "userId=$($worker.UserId)"

    # ---------------------------------------------------------------- 演示 1：验收 → 结算 → 打款
    Write-Host "`n=== 演示 1：验收通过 → 结算 → 打款（FR-PAY.ISSUE.02 / PAY.UNKNOWN.01） ===" -ForegroundColor Cyan

    $draft = New-DemoDraft -Title 'demo-v5 payout task' -Slots 1
    $taskId = $draft.Json.data.id
    $publish = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskId/publish" -CookieFile $script:EmployerJar
    $apply = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskId/applications" -CookieFile $script:WorkerJar
    $hire = Invoke-DemoRequest -Method POST `
        -Path "/api/tasks/$taskId/applications/$($apply.Json.data.id)/hire" -CookieFile $script:EmployerJar
    $contractId = $hire.Json.data.contractId
    [void](Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/start" -CookieFile $script:WorkerJar)
    $deliver = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/deliver" -Body @{
        images      = @('https://img.example.com/demo-v5.jpg')
        description = 'delivered by demo-v5'
    } -CookieFile $script:WorkerJar
    Write-Check ($publish.Status -eq 200 -and $apply.Status -eq 200 -and $hire.Status -eq 200 -and
        $deliver.Status -eq 200) '1.1 发布 → 报名 → 录用 → 开始 → 交付' `
        "taskId=$taskId contractId=$contractId"

    $accept = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/acceptance" `
        -Body @{ decision = 'PASS' } -CookieFile $script:EmployerJar
    $settlementId = $accept.Json.data.settlementId
    $payoutId = $accept.Json.data.payoutId
    Write-Check ($accept.Status -eq 200 -and $null -ne $settlementId -and $null -ne $payoutId) `
        '1.2 验收通过：生成结算单与打款单' `
        "status=$($accept.Status) settlementId=$settlementId payoutId=$payoutId"

    $expectStatus = if ($UnknownModes -contains $PayoutMode) { 'PAYOUT_PENDING_VERIFICATION' } else { 'PAYOUT_SUCCESS' }
    $found = $null
    for ($i = 1; $i -le 10; $i++) {
        $list = Find-AdminPayout -Status $expectStatus
        if ($list -and $list.Json -and @($list.Json.data.items).Count -gt 0) {
            $found = @($list.Json.data.items) | Where-Object { "$($_.id)" -eq "$payoutId" } | Select-Object -First 1
            if ($found) { break }
        }
        Start-Sleep -Seconds 1
    }
    Write-Check ($null -ne $found) "1.3 打款单进入 $expectStatus（管理端可查）" `
        "payoutId=$payoutId status=$(if ($found) { $found.status } else { 'not-found' })"

    # ---------------------------------------------------------------- 演示 2：资金结果
    Write-Host "`n=== 演示 2：资金结果（恒等式由 v5-verify.sql 复核） ===" -ForegroundColor Cyan

    if ($UnknownModes -contains $PayoutMode) {
        $workerBalance = Invoke-DemoRequest -Method GET -Path '/api/fund/accounts/me' -CookieFile $script:WorkerJar
        Write-Check ("$($workerBalance.Json.data.available)" -eq '0') `
            '2.1 未知态不加钱：劳动者余额仍为 0（只查证、不重发、不落成功）' `
            "available=$($workerBalance.Json.data.available)"
        Write-Host '提示：等待查证任务（30s 一轮）后，按 TIMEOUT 语义查证返回 NOT_FOUND，' -ForegroundColor Yellow
        Write-Host '      打款单转「失败 + 结算待重试」，重试任务以新请求号再出站。' -ForegroundColor Yellow
    }
    else {
        $workerBalance = Invoke-DemoRequest -Method GET -Path '/api/fund/accounts/me' -CookieFile $script:WorkerJar
        Write-Check ("$($workerBalance.Json.data.available)" -eq "$ExpectedNet") `
            '2.1 劳动者到账 291.00 元（300.00 − 3% 服务费）' `
            "available=$($workerBalance.Json.data.available) expected=$ExpectedNet"

        $employerBalance = Invoke-DemoRequest -Method GET -Path '/api/fund/accounts/me' -CookieFile $script:EmployerJar
        $employerOk = "$($employerBalance.Json.data.available)" -eq "$($RechargeAmount - $UnitPrice)" -and
            "$($employerBalance.Json.data.frozen)" -eq '0'
        Write-Check $employerOk '2.2 雇主冻结清零、可用余额只剩未托管部分' `
            "available=$($employerBalance.Json.data.available) frozen=$($employerBalance.Json.data.frozen)"
    }

    # ---------------------------------------------------------------- 演示 3：处置台权限
    Write-Host "`n=== 演示 3：处置台只认管理员身份（FR-PAY.MANUAL.01） ===" -ForegroundColor Cyan

    $workerList = Invoke-DemoRequest -Method GET -Path '/api/admin/payouts' -CookieFile $script:WorkerJar
    Write-Check ($workerList.Status -eq 403 -and $workerList.Json.code -eq 'FORBIDDEN') `
        '3.1 劳动者访问处置台 403 FORBIDDEN' `
        "status=$($workerList.Status) code=$($workerList.Json.code)"

    $adminList = Invoke-DemoRequest -Method GET -Path '/api/admin/payouts?page=1&size=5' -CookieFile $script:AdminJar
    Write-Check ($adminList.Status -eq 200 -and $null -ne $adminList.Json.data.items) `
        '3.2 管理员查询打款单分页' "status=$($adminList.Status) total=$($adminList.Json.data.total)"

    # ---------------------------------------------------------------- 演示 4：账龄告警
    Write-Host "`n=== 演示 4：账龄告警接口（FR-JOB.AGING.01） ===" -ForegroundColor Cyan

    $aging = Invoke-DemoRequest -Method GET -Path '/api/admin/payouts/aging' -CookieFile $script:AdminJar
    Write-Check ($aging.Status -eq 200 -and $null -ne $aging.Json.data) `
        '4.1 账龄接口返回数组（默认阈值 30 分钟）' `
        "status=$($aging.Status) count=$(@($aging.Json.data).Count)"

    # ---------------------------------------------------------------- 演示 5：重复验收
    Write-Host "`n=== 演示 5：重复验收不重复打款（FR-ACCEPT.GUARD.01） ===" -ForegroundColor Cyan

    $acceptAgain = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/acceptance" `
        -Body @{ decision = 'PASS' } -CookieFile $script:EmployerJar
    Write-Check ($acceptAgain.Status -eq 409 -and $acceptAgain.Json.code -eq 'INVALID_STATE_TRANSITION') `
        '5.1 重复验收 409，不重复生成结算 / 打款' `
        "status=$($acceptAgain.Status) code=$($acceptAgain.Json.code)"

    Write-Host ("`n合计：PASS={0} FAIL={1}" -f $script:PassCount, $script:FailCount) `
        -ForegroundColor ($(if ($script:FailCount -eq 0) { 'Green' } else { 'Red' }))
    if ($script:FailCount -gt 0) { exit 1 }
}
finally {
    foreach ($file in $script:TempFiles) {
        Remove-Item $file -Force -ErrorAction SilentlyContinue
    }
}
