#requires -Version 5.1
<#
.SYNOPSIS
    V6 演示一键跑（v6-c5）。只用 curl.exe，兼容 Windows PowerShell 5.1。

.DESCRIPTION
    前置条件（脚本不负责启动）：
      1. 应用已启动（mvn spring-boot:run），默认监听 127.0.0.1:8080；
      2. MySQL 与 Redis 可达（docker compose --profile core up -d）；
      3. 已设置 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD（管理端裁决与对账需要管理员会话）。

    演示分段：
      0. 准备：雇主注册 → 资质审核 → 充值 → 劳动者注册 → 发布 → 录用 → 交付
      1. 雇主拒收开争议（FR-ACCEPT.RESULT.02）：名额待裁决、托管冻结
      2. 双方提交证据（FR-DISPUTE.EVIDENCE.01）：对方可见
      3. 劳动者撤回 → 名额回已交付；再次申诉复用同一张争议单（FR-DISPUTE.WITHDRAW.02）
      4. 管理员裁决放款（FR-DISPUTE.RESOLVE.01）：复用验收结算，劳动者到账 291.00 元
      5. 当日对账（FR-RECON.JOB.01 / REPORT.01）：批次零差异、恒等式平衡、日报可查
      6. 越权与重复裁决：非管理员 403，重复裁决 409

.NOTES
    每次运行注册 2 个随机手机号的新账号（雇主 + 1 名劳动者），可重复执行。
    注册口按 IP 限 5 次/分钟：一分钟内连续重跑 3 次会被 429 拦截，等窗口过去再执行。
    演示口令由 ESCROW_DEMO_PASSWORD 提供，脚本内不保存任何凭据。
    对账默认 escrow.recon.archive-mode=log，Mongo 归档只在应用日志里可见，不影响本脚本。
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
$Today = (Get-Date).ToString('yyyy-MM-dd')

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
    $file = Join-Path $env:TEMP ("demo-v6-" + [guid]::NewGuid().ToString('N') + ".tmp")
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
        nickname = "demo-v6-$Role".ToLower()
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

try {
    Write-Host "V6 演示脚本（$BaseUrl，对账日=$Today）" -ForegroundColor Cyan

    $adminPhone = $env:ADMIN_INIT_PHONE
    $adminPassword = $env:ADMIN_INIT_PASSWORD
    if (-not $adminPhone -or -not $adminPassword) {
        Write-Host '缺少 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD：争议裁决与对账需要管理员会话。' -ForegroundColor Red
        exit 2
    }

    # ---------------------------------------------------------------- 演示 0：准备
    Write-Host "`n=== 演示 0：雇主资质 + 充值 + 劳动者 + 任务交付（2 个新账号） ===" -ForegroundColor Cyan

    $employer = New-DemoAccount -Role 'EMPLOYER'
    $employerSession = New-DemoSession -Phone $employer.Phone -Password $DemoPassword
    $script:EmployerJar = $employerSession.Jar
    Write-Check ($employer.Status -eq 200 -and $employerSession.Status -eq 200) '0.1 注册并登录雇主' `
        "status=$($employerSession.Status) userId=$($employer.UserId)"

    $qualification = Invoke-DemoRequest -Method POST -Path '/api/identity/qualification' -Body @{
        subjectType = 'INDIVIDUAL'
        subjectName = 'demo-v6-employer'
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
        title          = 'demo-v6 dispute task'
        description    = 'v6 demo task'
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
        images      = @('https://img.example.com/demo-v6.jpg')
        description = 'delivered by demo-v6'
    } -CookieFile $script:WorkerJar
    Write-Check ($publish.Status -eq 200 -and $apply.Status -eq 200 -and $hire.Status -eq 200 -and
        $deliver.Status -eq 200) '0.6 发布 → 报名 → 录用 → 开始 → 交付' `
        "taskId=$taskId contractId=$contractId"

    # ---------------------------------------------------------------- 演示 1：雇主拒收开争议
    Write-Host "`n=== 演示 1：雇主拒收 → 名额待裁决、托管冻结（FR-ACCEPT.RESULT.02） ===" -ForegroundColor Cyan

    $reject = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/acceptance" `
        -Body @{ decision = 'REJECT'; reason = '排版不符合约定' } -CookieFile $script:EmployerJar
    $disputeId = $reject.Json.data.disputeId
    Write-Check ($reject.Status -eq 200 -and $null -ne $disputeId) '1.1 拒收开争议：返回争议单 ID' `
        "status=$($reject.Status) disputeId=$disputeId"

    $detail = Invoke-DemoRequest -Method GET -Path "/api/disputes/$disputeId" -CookieFile $script:WorkerJar
    Write-Check ($detail.Status -eq 200 -and $detail.Json.data.status -eq 'DISPUTE_PENDING') `
        '1.2 劳动者可见争议待裁决' `
        "status=$($detail.Status) disputeStatus=$($detail.Json.data.status)"

    $acceptAgain = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/acceptance" `
        -Body @{ decision = 'PASS' } -CookieFile $script:EmployerJar
    Write-Check ($acceptAgain.Status -eq 409 -and $acceptAgain.Json.code -eq 'DISPUTE_ACTIVE') `
        '1.3 争议期间验收 409，资金不会二次推进' `
        "status=$($acceptAgain.Status) code=$($acceptAgain.Json.code)"

    # ---------------------------------------------------------------- 演示 2：双方证据
    Write-Host "`n=== 演示 2：双方提交证据、对方可见（FR-DISPUTE.EVIDENCE.01） ===" -ForegroundColor Cyan

    $workerEvidence = Invoke-DemoRequest -Method POST -Path "/api/disputes/$disputeId/evidence" -Body @{
        content   = '已按约定排版并交付'
        imageUrls = @('https://img.example.com/worker-evidence.jpg')
    } -CookieFile $script:WorkerJar
    $ownerEvidence = Invoke-DemoRequest -Method POST -Path "/api/disputes/$disputeId/evidence" -Body @{
        content = '首屏字体与约定不一致'
    } -CookieFile $script:EmployerJar
    Write-Check ($workerEvidence.Status -eq 200 -and $ownerEvidence.Status -eq 200) `
        '2.1 劳动者与雇主各提交一条证据' `
        "worker=$($workerEvidence.Status) owner=$($ownerEvidence.Status)"

    $withEvidence = Invoke-DemoRequest -Method GET -Path "/api/disputes/$disputeId" -CookieFile $script:EmployerJar
    Write-Check (@($withEvidence.Json.data.evidence).Count -eq 2) `
        '2.2 雇主能看到双方的 2 条证据' `
        "evidenceCount=$(@($withEvidence.Json.data.evidence).Count)"

    # ---------------------------------------------------------------- 演示 3：撤回与复用
    Write-Host "`n=== 演示 3：撤回还原 → 再次申诉复用同一张单（FR-DISPUTE.WITHDRAW.01 / .02） ===" -ForegroundColor Cyan

    $withdraw = Invoke-DemoRequest -Method POST -Path "/api/disputes/$disputeId/withdraw" -CookieFile $script:EmployerJar
    $afterWithdraw = Invoke-DemoRequest -Method GET -Path "/api/disputes/$disputeId" -CookieFile $script:EmployerJar
    Write-Check ($withdraw.Status -eq 200 -and $afterWithdraw.Json.data.status -eq 'DISPUTE_WITHDRAWN' -and
        "$($afterWithdraw.Json.data.withdrawCount)" -eq '1') `
        '3.1 雇主撤回：状态已撤回、次数 1' `
        "status=$($withdraw.Status) disputeStatus=$($afterWithdraw.Json.data.status)"

    $appeal = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/disputes" `
        -Body @{ reason = '交付被无理由拒收' } -CookieFile $script:WorkerJar
    Write-Check ($appeal.Status -eq 200 -and "$($appeal.Json.data)" -eq "$disputeId") `
        '3.2 劳动者再次申诉复用同一张争议单' `
        "reopenedId=$($appeal.Json.data) originalId=$disputeId"

    $ownerWithdraw = Invoke-DemoRequest -Method POST -Path "/api/disputes/$disputeId/withdraw" -CookieFile $script:EmployerJar
    Write-Check ($ownerWithdraw.Status -eq 403 -and $ownerWithdraw.Json.code -eq 'FORBIDDEN') `
        '3.3 非发起方撤回 403' `
        "status=$($ownerWithdraw.Status) code=$($ownerWithdraw.Json.code)"

    # ---------------------------------------------------------------- 演示 4：裁决放款
    Write-Host "`n=== 演示 4：管理员裁决放款，复用验收结算（FR-DISPUTE.RESOLVE.01） ===" -ForegroundColor Cyan

    $workerResolve = Invoke-DemoRequest -Method POST -Path "/api/admin/disputes/$disputeId/resolve" `
        -Body @{ resolution = 'RELEASE'; basis = '证据充分' } -CookieFile $script:WorkerJar
    Write-Check ($workerResolve.Status -eq 403 -and $workerResolve.Json.code -eq 'FORBIDDEN') `
        '4.1 非管理员裁决 403' `
        "status=$($workerResolve.Status) code=$($workerResolve.Json.code)"

    $resolve = Invoke-DemoRequest -Method POST -Path "/api/admin/disputes/$disputeId/resolve" `
        -Body @{ resolution = 'RELEASE'; basis = '交付证据完整，判定放款' } -CookieFile $script:AdminJar
    Write-Check ($resolve.Status -eq 200 -and $null -ne $resolve.Json.data.payoutId) `
        '4.2 管理员裁决放款：生成打款单' `
        "status=$($resolve.Status) payoutId=$($resolve.Json.data.payoutId)"

    $workerBalance = Invoke-DemoRequest -Method GET -Path '/api/fund/accounts/me' -CookieFile $script:WorkerJar
    Write-Check ("$($workerBalance.Json.data.available)" -eq "$ExpectedNet") `
        '4.3 劳动者到账 291.00 元（300.00 − 3% 服务费）' `
        "available=$($workerBalance.Json.data.available) expected=$ExpectedNet"

    $resolvedDetail = Invoke-DemoRequest -Method GET -Path "/api/disputes/$disputeId" -CookieFile $script:WorkerJar
    Write-Check ($resolvedDetail.Json.data.status -eq 'DISPUTE_RESOLVED' -and
        $resolvedDetail.Json.data.resolution -eq 'RELEASE' -and
        $resolvedDetail.Json.data.resolutionBasis -eq '交付证据完整，判定放款') `
        '4.4 争议已裁决：结论与依据可追溯' `
        "status=$($resolvedDetail.Json.data.status) resolution=$($resolvedDetail.Json.data.resolution)"

    $resolveAgain = Invoke-DemoRequest -Method POST -Path "/api/admin/disputes/$disputeId/resolve" `
        -Body @{ resolution = 'REFUND'; basis = '重复裁决' } -CookieFile $script:AdminJar
    Write-Check ($resolveAgain.Status -eq 409 -and $resolveAgain.Json.code -eq 'INVALID_STATE_TRANSITION') `
        '4.5 重复裁决 409，不重复打款' `
        "status=$($resolveAgain.Status) code=$($resolveAgain.Json.code)"

    # ---------------------------------------------------------------- 演示 5：当日对账
    Write-Host "`n=== 演示 5：当日对账 → 零差异 + 恒等式平衡（FR-RECON.JOB.01 / REPORT.01） ===" -ForegroundColor Cyan

    $run = Invoke-DemoRequest -Method POST -Path "/api/admin/recon/run?date=$Today" -CookieFile $script:AdminJar
    Write-Check ($run.Status -eq 200 -and "$($run.Json.data.diffCount)" -eq '0' -and
        $run.Json.data.identityOk -eq $true) `
        '5.1 对账完成：差异 0 笔、六科目恒等式平衡' `
        "local=$($run.Json.data.localCount) channel=$($run.Json.data.channelCount) diff=$($run.Json.data.diffCount)"

    $differences = Invoke-DemoRequest -Method GET -Path "/api/admin/recon/differences?date=$Today" -CookieFile $script:AdminJar
    Write-Check ($differences.Status -eq 200 -and "$($differences.Json.data.total)" -eq '0') `
        '5.2 差异单列表为空' "status=$($differences.Status) total=$($differences.Json.data.total)"

    $report = Invoke-DemoRequest -Method GET -Path "/api/admin/recon/report?date=$Today" -CookieFile $script:AdminJar
    Write-Check ($report.Status -eq 200 -and $report.Json.data.identityOk -eq $true -and
        "$($report.Json.data.pendingCount)" -eq '0') `
        '5.3 日报可查：恒等式平衡、无待处理差异' `
        "status=$($report.Json.data.status) closing=$($report.Json.data.closingTotal)"

    Write-Host ("`n合计：PASS={0} FAIL={1}" -f $script:PassCount, $script:FailCount) `
        -ForegroundColor ($(if ($script:FailCount -eq 0) { 'Green' } else { 'Red' }))
    if ($script:FailCount -gt 0) { exit 1 }
}
finally {
    foreach ($file in $script:TempFiles) {
        Remove-Item $file -Force -ErrorAction SilentlyContinue
    }
}
