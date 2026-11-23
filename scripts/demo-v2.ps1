#requires -Version 5.1
<#
.SYNOPSIS
    V2 五段演示一键跑（v2-c6）。只用 curl.exe，兼容 Windows PowerShell 5.1。

.DESCRIPTION
    前置条件（脚本不负责启动）：
      1. 应用已启动（mvn spring-boot:run），默认监听 127.0.0.1:8080；
      2. MySQL 与 Redis 可达（docker compose --profile core up -d）；
      3. 已设置 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD（审核资质需要管理员会话）。

    五段演示：
      1. 资质通过 → 充值 → 建草稿 → 发布即托管（余额搬运 + 恒等式）
      2. 详情托管金额：当事人看得到，非当事人连键都不出现（FR-TASK.DETAIL.01/.02）
      3. 撤单 → 全额退款；撤单后详情仍显示原托管额（v2-c6 口径 A）
      4. 两笔各需 60% 余额的任务并发发布 → 恰好一笔成功、另一笔 409
      5. 收尾：撤掉成功那笔，余额归位

    每段打印 [PASS] 或 [FAIL]；任一 FAIL 时退出码为 1。
    EXPLAIN 演示见 scripts/v2-explain.sql（需要 SQL 客户端，不能走 HTTP）。

.NOTES
    每次运行注册 2 个随机手机号的新账号，可重复执行。
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

# 演示金额（分）：充值 10000.00 元；每个任务 20 名额 × 300.00 元 = 6000.00 元
$RechargeAmount = 1000000L
$UnitPrice = 30000L
$SlotCount = 20
$TaskTotal = $UnitPrice * $SlotCount

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
    $file = Join-Path $env:TEMP ("demo-v2-" + [guid]::NewGuid().ToString('N') + ".tmp")
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
        nickname = "demo-v2-$Role".ToLower()
        role     = $Role
    }
    return [pscustomobject]@{
        Phone   = $phone
        Status  = $response.Status
        UserId  = $response.Json.data.userId
        Content = $response.Content
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
        Code   = $login.Json.code
        Jar    = $jar
    }
}

function New-DemoDraft {
    param([string]$Title)
    $body = @{
        title          = $Title
        description    = 'v2 demo task for escrow hold'
        category       = 'demo'
        unitPrice      = $UnitPrice
        slotCount      = $SlotCount
        startTime      = (Get-Date).AddDays(1).ToString('yyyy-MM-ddTHH:mm:ss')
        endTime        = (Get-Date).AddDays(2).ToString('yyyy-MM-ddTHH:mm:ss')
        workLocation   = 'demo-location'
        settlementNote = 'demo-note'
    }
    return Invoke-DemoRequest -Method POST -Path '/api/tasks' -Body $body -CookieFile $script:EmployerJar
}

function Get-DemoBalance {
    param([string]$CookieFile)
    $response = Invoke-DemoRequest -Method GET -Path '/api/fund/accounts/me' -CookieFile $CookieFile
    return [pscustomobject]@{
        Status    = $response.Status
        Available = [long]$response.Json.data.available
        Frozen    = [long]$response.Json.data.frozen
    }
}

try {
    Write-Host "V2 演示脚本（$BaseUrl）" -ForegroundColor Cyan

    $adminPhone = $env:ADMIN_INIT_PHONE
    $adminPassword = $env:ADMIN_INIT_PASSWORD
    if (-not $adminPhone -or -not $adminPassword) {
        Write-Host '缺少 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD：资质审核需要管理员会话。' -ForegroundColor Red
        Write-Host '先在当前终端设置这两个环境变量再重跑。' -ForegroundColor Red
        exit 2
    }

    # ---------------------------------------------------------------- 演示 1：发布即托管
    Write-Host "`n=== 演示 1：资质 → 充值 → 建草稿 → 发布即托管（FR-TASK.CREATE.04 / FR-ESCROW.HOLD.01） ===" -ForegroundColor Cyan

    $employer = New-DemoAccount -Role 'EMPLOYER'
    Write-Check ($employer.Status -eq 200 -and $employer.UserId) '1.1 注册雇主' `
        "status=$($employer.Status) userId=$($employer.UserId)"

    $employerSession = New-DemoSession -Phone $employer.Phone -Password $DemoPassword
    $script:EmployerJar = $employerSession.Jar
    Write-Check ($employerSession.Status -eq 200 -and $employerSession.Code -eq 'SUCCESS') '1.2 雇主登录' `
        "status=$($employerSession.Status) code=$($employerSession.Code)"

    $qualification = Invoke-DemoRequest -Method POST -Path '/api/identity/qualification' -Body @{
        subjectType = 'INDIVIDUAL'
        subjectName = 'demo-employer'
        idLast4     = '1234'
    } -CookieFile $script:EmployerJar
    Write-Check ($qualification.Status -eq 200) '1.3 提交雇主资质' `
        "status=$($qualification.Status) code=$($qualification.Json.code)"

    $adminSession = New-DemoSession -Phone $adminPhone -Password $adminPassword
    Write-Check ($adminSession.Status -eq 200 -and $adminSession.Code -eq 'SUCCESS') '1.4 管理员登录' `
        "status=$($adminSession.Status) code=$($adminSession.Code)"

    $review = Invoke-DemoRequest -Method POST -Path "/api/admin/qualifications/$($employer.UserId)/review" `
        -Body @{ decision = 'APPROVED' } -CookieFile $adminSession.Jar
    Write-Check ($review.Status -eq 200) '1.5 审核通过' `
        "status=$($review.Status) code=$($review.Json.code)"

    $recharge = Invoke-DemoRequest -Method POST -Path '/api/fund/recharge' `
        -Body @{ amount = $RechargeAmount } -CookieFile $script:EmployerJar
    Write-Check ($recharge.Status -eq 200) '1.6 模拟充值' `
        "available=$($recharge.Json.data.available) frozen=$($recharge.Json.data.frozen)"

    $created = New-DemoDraft -Title 'demo-v2 escrow task'
    $taskId = $created.Json.data.id
    Write-Check ($created.Status -eq 200 -and $taskId) '1.7 创建草稿（20 名额 × 30000 分）' `
        "status=$($created.Status) taskId=$taskId"

    $publish = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskId/publish" -CookieFile $script:EmployerJar
    Write-Check ($publish.Status -eq 200) '1.8 发布（发布即托管）' `
        "status=$($publish.Status) code=$($publish.Json.code)"

    $balance = Get-DemoBalance -CookieFile $script:EmployerJar
    Write-Check ($balance.Available -eq ($RechargeAmount - $TaskTotal) -and $balance.Frozen -eq $TaskTotal) `
        '1.9 余额搬运：可用减、冻结增' "available=$($balance.Available) frozen=$($balance.Frozen)"
    Write-Check (($balance.Available + $balance.Frozen) -eq $RechargeAmount) `
        '1.10 恒等式：可用 + 冻结 = 充值总额' "sum=$($balance.Available + $balance.Frozen)"

    # ---------------------------------------------------------------- 演示 2：详情托管金额
    Write-Host "`n=== 演示 2：详情托管金额的可见性（FR-TASK.DETAIL.01 / .02、NFR-SEC.03） ===" -ForegroundColor Cyan

    $worker = New-DemoAccount -Role 'WORKER'
    $workerSession = New-DemoSession -Phone $worker.Phone -Password $DemoPassword
    Write-Check ($worker.Status -eq 200 -and $workerSession.Status -eq 200) '2.1 注册并登录劳动者' `
        "status=$($workerSession.Status) userId=$($worker.UserId)"

    $partyView = Invoke-DemoRequest -Method GET -Path "/api/tasks/$taskId" -CookieFile $script:EmployerJar
    Write-Check ($partyView.Status -eq 200 -and "$($partyView.Json.data.escrowAmount)" -eq "$TaskTotal") `
        '2.2 当事人视图带 escrowAmount' "escrowAmount=$($partyView.Json.data.escrowAmount) 期望=$TaskTotal"

    $nonPartyView = Invoke-DemoRequest -Method GET -Path "/api/tasks/$taskId" -CookieFile $workerSession.Jar
    $hasEscrowKey = @($nonPartyView.Json.data.PSObject.Properties.Name) -contains 'escrowAmount'
    Write-Check ($nonPartyView.Status -eq 200 -and -not $hasEscrowKey) `
        '2.3 非当事人视图没有 escrowAmount 键' "status=$($nonPartyView.Status) hasKey=$hasEscrowKey"

    # ---------------------------------------------------------------- 演示 3：撤单退款
    Write-Host "`n=== 演示 3：撤单 → 全额退款 → 详情仍显示原托管额（FR-TASK.CANCEL.01~.02） ===" -ForegroundColor Cyan

    $cancel = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskId/cancel" -CookieFile $script:EmployerJar
    Write-Check ($cancel.Status -eq 200) '3.1 撤单' "status=$($cancel.Status) code=$($cancel.Json.code)"

    $balanceAfterCancel = Get-DemoBalance -CookieFile $script:EmployerJar
    Write-Check ($balanceAfterCancel.Available -eq $RechargeAmount -and $balanceAfterCancel.Frozen -eq 0) `
        '3.2 全额退款：可用恢复、冻结归零' "available=$($balanceAfterCancel.Available) frozen=$($balanceAfterCancel.Frozen)"

    $cancelledView = Invoke-DemoRequest -Method GET -Path "/api/tasks/$taskId" -CookieFile $script:EmployerJar
    Write-Check ("$($cancelledView.Json.data.escrowAmount)" -eq "$TaskTotal") `
        '3.3 撤单后仍显示原托管额（口径 A）' "escrowAmount=$($cancelledView.Json.data.escrowAmount)"
    Write-Check ($null -ne $cancelledView.Json.data.cancelledAt) `
        '3.4 撤单时间已写入' "cancelledAt=$($cancelledView.Json.data.cancelledAt)"

    # ---------------------------------------------------------------- 演示 4：并发发布
    Write-Host "`n=== 演示 4：两笔各需 60% 余额的任务并发发布 → 恰好一笔成功（FR-ESCROW.HOLD.04） ===" -ForegroundColor Cyan

    $draftA = New-DemoDraft -Title 'demo-v2 concurrent A'
    $draftB = New-DemoDraft -Title 'demo-v2 concurrent B'
    $taskA = $draftA.Json.data.id
    $taskB = $draftB.Json.data.id
    Write-Check ($draftA.Status -eq 200 -and $draftB.Status -eq 200) '4.1 建两个草稿（各 6000.00 元）' `
        "taskA=$taskA taskB=$taskB"

    # 两个 job 共用同一会话 jar（只读 -b，不写 -c），尽量同时发出
    $jobA = Start-Job -ScriptBlock {
        param($base, $id, $jar)
        & curl.exe --silent --max-time 15 -X POST -b $jar -o NUL -w '%{http_code}' "$base/api/tasks/$id/publish"
    } -ArgumentList $BaseUrl, $taskA, $script:EmployerJar
    $jobB = Start-Job -ScriptBlock {
        param($base, $id, $jar)
        & curl.exe --silent --max-time 15 -X POST -b $jar -o NUL -w '%{http_code}' "$base/api/tasks/$id/publish"
    } -ArgumentList $BaseUrl, $taskB, $script:EmployerJar

    [void](Wait-Job $jobA, $jobB -Timeout 30)
    $codeA = 0; $codeB = 0
    [void][int]::TryParse((Receive-Job $jobA | Select-Object -Last 1), [ref]$codeA)
    [void][int]::TryParse((Receive-Job $jobB | Select-Object -Last 1), [ref]$codeB)
    Remove-Job $jobA, $jobB -Force -ErrorAction SilentlyContinue

    $successCount = @(@($codeA, $codeB) | Where-Object { $_ -eq 200 }).Count
    $conflictCount = @(@($codeA, $codeB) | Where-Object { $_ -eq 409 }).Count
    Write-Check ($successCount -eq 1 -and $conflictCount -eq 1) '4.2 恰好一笔成功、一笔 409' `
        "codes=$codeA,$codeB（409 = INSUFFICIENT_BALANCE）"

    $balanceAfterRace = Get-DemoBalance -CookieFile $script:EmployerJar
    Write-Check (($balanceAfterRace.Available + $balanceAfterRace.Frozen) -eq $RechargeAmount) `
        '4.3 并发后恒等式仍成立' "available=$($balanceAfterRace.Available) frozen=$($balanceAfterRace.Frozen)"

    # ---------------------------------------------------------------- 演示 5：收尾
    Write-Host "`n=== 演示 5：收尾（撤掉并发成功那笔，余额归位） ===" -ForegroundColor Cyan

    $winner = $null
    foreach ($candidate in @($taskA, $taskB)) {
        $candidateView = Invoke-DemoRequest -Method GET -Path "/api/tasks/$candidate" -CookieFile $script:EmployerJar
        if ($null -ne $candidateView.Json.data.publishedAt) {
            $winner = $candidate
            break
        }
    }
    Write-Check ($null -ne $winner) '5.1 找到并发成功的那笔任务' "winner=$winner"

    if ($null -ne $winner) {
        $cleanup = Invoke-DemoRequest -Method POST -Path "/api/tasks/$winner/cancel" -CookieFile $script:EmployerJar
        Write-Check ($cleanup.Status -eq 200) '5.2 撤单收尾' "status=$($cleanup.Status) code=$($cleanup.Json.code)"

        $finalBalance = Get-DemoBalance -CookieFile $script:EmployerJar
        Write-Check ($finalBalance.Available -eq $RechargeAmount -and $finalBalance.Frozen -eq 0) `
            '5.3 余额归位' "available=$($finalBalance.Available) frozen=$($finalBalance.Frozen)"
    }

    Write-Host ("`n合计：PASS={0} FAIL={1}" -f $script:PassCount, $script:FailCount) `
        -ForegroundColor ($(if ($script:FailCount -eq 0) { 'Green' } else { 'Red' }))
    if ($script:FailCount -gt 0) { exit 1 }
}
finally {
    foreach ($file in $script:TempFiles) {
        Remove-Item $file -Force -ErrorAction SilentlyContinue
    }
}
