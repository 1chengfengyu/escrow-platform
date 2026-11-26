#requires -Version 5.1
<#
.SYNOPSIS
    V4 四段演示一键跑（v4-c6）。只用 curl.exe，兼容 Windows PowerShell 5.1。

.DESCRIPTION
    前置条件（脚本不负责启动）：
      1. 应用已启动（mvn spring-boot:run），默认监听 127.0.0.1:8080；
      2. MySQL 与 Redis 可达（docker compose --profile core up -d）；
      3. 已设置 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD（审核雇主资质需要管理员会话）。

    四段演示：
      0. 准备：雇主注册 → 资质审核 → 充值 10000.00 元；劳动者注册登录
      1. 未交付不能验收：发布 1 名额 300.00 元任务 → 报名 → 录用 → 开始 → 验收 409
      2. 验收通过全链路：交付 → 验收通过 → 签约完成、名额完结、详情已完成、
         劳动者到账 291.00 元（扣 3% 服务费）、雇主冻结清零、重复验收 409
      3. 拒收进争议：第二个任务 → 交付 → 拒收 → 详情争议中 → 再验收 409 DISPUTE_ACTIVE

    每段打印 [PASS] 或 [FAIL]；任一 FAIL 时退出码为 1。

    打款失败 / 结果未知 / 48 小时自动验收不在本脚本：
    模拟通道模式是启动期配置，由真库测试
    V4AcceptanceSettlementIntegrationTest / V4PayoutFailureIntegrationTest 证明。

.NOTES
    每次运行注册 2 个随机手机号的新账号（雇主 + 1 名劳动者），可重复执行。
    注册口按 IP 限 5 次/分钟：一分钟内连续重跑 3 次会被 429 拦截，等窗口过去再执行即可。
    演示口令由 ESCROW_DEMO_PASSWORD 提供，脚本内不保存任何凭据。
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'

# 演示账号口令：脚本内常量，仅用于本地演示，不对应任何真实账号
. (Join-Path $PSScriptRoot 'demo-config.ps1')

# 演示金额（分）：充值 10000.00 元；单价 300.00 元；3% 服务费 9.00 元；实付 291.00 元
$RechargeAmount = 1000000L
$UnitPrice = 30000L
$ExpectedFee = 900L
$ExpectedNet = 29100L

$script:PassCount = 0
$script:FailCount = 0
$script:TempFiles = New-Object System.Collections.ArrayList

function Write-Check {
    param(
        [bool]$Ok,
        [string]$Name,
        [string]$Detail
    )
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
    $file = Join-Path $env:TEMP ("demo-v4-" + [guid]::NewGuid().ToString('N') + ".tmp")
    [void]$script:TempFiles.Add($file)
    return $file
}

function Read-Utf8Text {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return '' }
    try { return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8) }
    catch { return '' }
}

# 一次 HTTP 调用：返回 状态码 / 响应体 / 响应体 JSON / 响应头
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
        # 请求体全部使用 ASCII 值，避免 PowerShell 5.1 控制台编码把中文写坏
        $json = ($Body | ConvertTo-Json -Compress)
        # PowerShell 5.1 调原生 exe 时会把参数里的双引号吞掉，必须落成 UTF-8 无 BOM 文件
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

# 随机手机号：13x + 8 位随机数，保证重复执行不撞 uk_phone
function New-DemoPhone {
    return '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
}

function New-DemoAccount {
    param([string]$Role)
    $phone = New-DemoPhone
    $response = Invoke-DemoRequest -Method POST -Path '/api/identity/register' -Body @{
        phone    = $phone
        password = $DemoPassword
        nickname = "demo-v4-$Role".ToLower()
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

# 建草稿：title / slotCount 必填，其余字段固定；发布走 POST /api/tasks/{id}/publish
function New-DemoDraft {
    param([string]$Title, [int]$Slots)
    $body = @{
        title          = $Title
        description    = 'v4 demo task'
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

function Publish-DemoTask {
    param([string]$TaskId)
    return Invoke-DemoRequest -Method POST -Path "/api/tasks/$TaskId/publish" -CookieFile $script:EmployerJar
}

# 完整走一遍「报名 → 录用 → 开始 → 交付」，返回 contractId
function New-DeliveredContract {
    param([int]$Slots, [string]$Title)
    $draft = New-DemoDraft -Title $Title -Slots $Slots
    $taskId = $draft.Json.data.id
    $publish = Publish-DemoTask -TaskId $taskId
    $apply = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskId/applications" -CookieFile $script:WorkerJar
    $hire = Invoke-DemoRequest -Method POST `
        -Path "/api/tasks/$taskId/applications/$($apply.Json.data.id)/hire" -CookieFile $script:EmployerJar
    $contractId = $hire.Json.data.contractId
    [void](Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/start" -CookieFile $script:WorkerJar)
    $deliver = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/deliver" -Body @{
        images      = @('https://img.example.com/demo-v4.jpg')
        description = 'delivered by demo-v4'
    } -CookieFile $script:WorkerJar
    return [pscustomobject]@{
        TaskId       = $taskId
        ContractId   = $contractId
        PublishOk    = ($draft.Status -eq 200 -and $publish.Status -eq 200)
        ApplyOk      = ($apply.Status -eq 200)
        HireOk       = ($hire.Status -eq 200)
        DeliverOk    = ($deliver.Status -eq 200 -and $deliver.Json.data.status -eq 'CONTRACT_DELIVERED')
    }
}

try {
    Write-Host "V4 演示脚本（$BaseUrl）" -ForegroundColor Cyan

    $adminPhone = $env:ADMIN_INIT_PHONE
    $adminPassword = $env:ADMIN_INIT_PASSWORD
    if (-not $adminPhone -or -not $adminPassword) {
        Write-Host '缺少 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD：资质审核需要管理员会话。' -ForegroundColor Red
        Write-Host '先在当前终端设置这两个环境变量再重跑。' -ForegroundColor Red
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
        subjectName = 'demo-v4-employer'
        idLast4     = '1234'
    } -CookieFile $script:EmployerJar
    Write-Check ($qualification.Status -eq 200) '0.2 提交雇主资质' `
        "status=$($qualification.Status) code=$($qualification.Json.code)"

    $adminSession = New-DemoSession -Phone $adminPhone -Password $adminPassword
    $review = Invoke-DemoRequest -Method POST -Path "/api/admin/qualifications/$($employer.UserId)/review" `
        -Body @{ decision = 'APPROVED' } -CookieFile $adminSession.Jar
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

    # ---------------------------------------------------------------- 演示 1：未交付不能验收
    Write-Host "`n=== 演示 1：未交付不能验收（FR-ACCEPT.GUARD.01） ===" -ForegroundColor Cyan

    $draftA = New-DemoDraft -Title 'demo-v4 accept task' -Slots 1
    $taskA = $draftA.Json.data.id
    $publishA = Publish-DemoTask -TaskId $taskA
    $applyA = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskA/applications" -CookieFile $script:WorkerJar
    $hireA = Invoke-DemoRequest -Method POST `
        -Path "/api/tasks/$taskA/applications/$($applyA.Json.data.id)/hire" -CookieFile $script:EmployerJar
    $contractA = $hireA.Json.data.contractId
    $startA = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractA/start" -CookieFile $script:WorkerJar
    Write-Check ($publishA.Status -eq 200 -and $applyA.Status -eq 200 -and $hireA.Status -eq 200 -and
        $startA.Status -eq 200) '1.1 发布 → 报名 → 录用 → 开始' "taskId=$taskA contractId=$contractA"

    $earlyAccept = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractA/acceptance" `
        -Body @{ decision = 'PASS' } -CookieFile $script:EmployerJar
    Write-Check ($earlyAccept.Status -eq 409 -and $earlyAccept.Json.code -eq 'INVALID_STATE_TRANSITION') `
        '1.2 未交付就验收 409，不产生结算' `
        "status=$($earlyAccept.Status) code=$($earlyAccept.Json.code)"

    # ---------------------------------------------------------------- 演示 2：验收通过全链路
    Write-Host "`n=== 演示 2：验收通过 → 结算 → 打款 → 余额到账（FR-ACCEPT.MANUAL.01 / SETTLE.* / PAY.*） ===" -ForegroundColor Cyan

    $deliverA = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractA/deliver" -Body @{
        images      = @('https://img.example.com/demo-v4-a.jpg')
        description = 'delivered task A'
    } -CookieFile $script:WorkerJar
    Write-Check ($deliverA.Status -eq 200) '2.1 提交交付物' "status=$($deliverA.Json.data.status)"

    $detailBefore = Invoke-DemoRequest -Method GET -Path "/api/tasks/$taskA" -CookieFile $script:EmployerJar
    Write-Check ($detailBefore.Json.data.status -eq 'TASK_PENDING_ACCEPTANCE') `
        '2.2 详情派生「待验收」（R8）' "status=$($detailBefore.Json.data.status)"

    $acceptA = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractA/acceptance" `
        -Body @{ decision = 'PASS' } -CookieFile $script:EmployerJar
    Write-Check ($acceptA.Status -eq 200 -and $acceptA.Json.data.contractStatus -eq 'CONTRACT_COMPLETED' -and
        $acceptA.Json.data.slotStatus -eq 'SLOT_COMPLETED' -and $null -ne $acceptA.Json.data.settlementId -and
        $null -ne $acceptA.Json.data.payoutId) '2.3 验收通过：签约完成、名额完结、生成结算与打款单' `
        "contractStatus=$($acceptA.Json.data.contractStatus) slotStatus=$($acceptA.Json.data.slotStatus) settlementId=$($acceptA.Json.data.settlementId)"

    $detailAfter = Invoke-DemoRequest -Method GET -Path "/api/tasks/$taskA" -CookieFile $script:EmployerJar
    Write-Check ($detailAfter.Json.data.status -eq 'TASK_COMPLETED') `
        '2.4 详情派生「已完成」（R5）' "status=$($detailAfter.Json.data.status)"

    $workerBalance = Invoke-DemoRequest -Method GET -Path '/api/fund/accounts/me' -CookieFile $script:WorkerJar
    Write-Check ("$($workerBalance.Json.data.available)" -eq "$ExpectedNet") `
        '2.5 劳动者到账 291.00 元（300.00 − 3% 服务费）' `
        "available=$($workerBalance.Json.data.available) expected=$ExpectedNet"

    $employerBalance = Invoke-DemoRequest -Method GET -Path '/api/fund/accounts/me' -CookieFile $script:EmployerJar
    $employerAvailableOk = "$($employerBalance.Json.data.available)" -eq "$($RechargeAmount - $UnitPrice)"
    Write-Check ($employerAvailableOk -and "$($employerBalance.Json.data.frozen)" -eq '0') `
        '2.6 雇主冻结清零、可用余额只剩未托管部分' `
        "available=$($employerBalance.Json.data.available) frozen=$($employerBalance.Json.data.frozen)"

    $acceptAgain = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractA/acceptance" `
        -Body @{ decision = 'PASS' } -CookieFile $script:EmployerJar
    Write-Check ($acceptAgain.Status -eq 409 -and $acceptAgain.Json.code -eq 'INVALID_STATE_TRANSITION') `
        '2.7 重复验收 409，不重复打款' `
        "status=$($acceptAgain.Status) code=$($acceptAgain.Json.code) fee=$ExpectedFee"

    # ---------------------------------------------------------------- 演示 3：拒收进争议
    Write-Host "`n=== 演示 3：拒收 → 冻结 → 争议中 → 再验收被守卫（FR-ACCEPT.RESULT.02 / GUARD.01） ===" -ForegroundColor Cyan

    $deliveredB = New-DeliveredContract -Slots 1 -Title 'demo-v4 reject task'
    Write-Check ($deliveredB.PublishOk -and $deliveredB.ApplyOk -and $deliveredB.HireOk -and
        $deliveredB.DeliverOk) '3.1 第二个任务走到「已交付」' "taskId=$($deliveredB.TaskId)"

    $rejectB = Invoke-DemoRequest -Method POST -Path "/api/contracts/$($deliveredB.ContractId)/acceptance" `
        -Body @{ decision = 'REJECT'; reason = 'demo reject' } -CookieFile $script:EmployerJar
    Write-Check ($rejectB.Status -eq 200 -and $rejectB.Json.data.slotStatus -eq 'SLOT_PENDING_ARBITRATION' -and
        $null -ne $rejectB.Json.data.disputeId) '3.2 拒收：名额待裁决、生成争议单' `
        "slotStatus=$($rejectB.Json.data.slotStatus) disputeId=$($rejectB.Json.data.disputeId)"

    $detailB = Invoke-DemoRequest -Method GET -Path "/api/tasks/$($deliveredB.TaskId)" -CookieFile $script:EmployerJar
    Write-Check ($detailB.Json.data.status -eq 'TASK_IN_DISPUTE') `
        '3.3 详情派生「争议中」（R2）' "status=$($detailB.Json.data.status)"

    $acceptBlocked = Invoke-DemoRequest -Method POST -Path "/api/contracts/$($deliveredB.ContractId)/acceptance" `
        -Body @{ decision = 'PASS' } -CookieFile $script:EmployerJar
    Write-Check ($acceptBlocked.Status -eq 409 -and $acceptBlocked.Json.code -eq 'DISPUTE_ACTIVE') `
        '3.4 争议中再次验收 409 DISPUTE_ACTIVE' `
        "status=$($acceptBlocked.Status) code=$($acceptBlocked.Json.code)"

    $contractsB = Invoke-DemoRequest -Method GET -Path '/api/contracts' -CookieFile $script:WorkerJar
    $contractB = @($contractsB.Json.data.items) |
        Where-Object { "$($_.contractId)" -eq "$($deliveredB.ContractId)" } | Select-Object -First 1
    Write-Check ($null -ne $contractB -and $contractB.status -eq 'CONTRACT_DELIVERED') `
        '3.5 拒收不改签约状态，仍为「已交付」' "status=$($contractB.status)"

    Write-Host ("`n合计：PASS={0} FAIL={1}" -f $script:PassCount, $script:FailCount) `
        -ForegroundColor ($(if ($script:FailCount -eq 0) { 'Green' } else { 'Red' }))
    if ($script:FailCount -gt 0) { exit 1 }
}
finally {
    foreach ($file in $script:TempFiles) {
        Remove-Item $file -Force -ErrorAction SilentlyContinue
    }
}
