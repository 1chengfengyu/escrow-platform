#requires -Version 5.1
<#
.SYNOPSIS
    V9 多实例一致性验证（v9-c2）。同一单体起 3 个实例，逐项验证四条结论。

.DESCRIPTION
    前置条件（脚本不负责启动）：
      1. MySQL / Redis 可达（docker compose --profile core up -d）；
      2. 三个实例已启动，端口分别为 8081 / 8082 / 8083：
           $env:SERVER_PORT=8081; mvn spring-boot:run   （另开两个窗口改端口）
      3. 已设置 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD。

    本脚本自动验证三条，第四条（ShedLock）需看日志：
      1. 实例存活：三个端口 /actuator/health 均为 UP；
      2. 幂等 + 条件更新：同一任务重复发布 10 次（轮询三个实例）后，
         托管金额与名额数不变——不靠分布式锁，靠唯一索引 + 条件更新；
      3. 缓存跨实例失效：A 实例读详情填本地缓存 → B 实例撤单 → A 实例 3 秒内
         必须看到 cancelledAt（修复前会脏到本地 TTL 的 60 秒）。
    第四条 ShedLock 只能看日志：三个实例同时跑时，每轮扫描应只有 1 条执行记录。

.NOTES
    每次运行注册 1 个随机手机号的新账号（雇主），可重复执行。
    注册口按 IP 限 5 次/分钟；一分钟内连续重跑 6 次会被 429 拦截。
#>
[CmdletBinding()]
param(
    [string[]]$BaseUrls = @('http://127.0.0.1:8081', 'http://127.0.0.1:8082', 'http://127.0.0.1:8083'),
    [int]$PublishRounds = 10,
    [int]$CacheWaitSeconds = 3
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'demo-config.ps1')
$RechargeAmount = 1000000L
$UnitPrice = 30000L
$SlotCount = 2

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
    $file = Join-Path $env:TEMP ("verify-v9-" + [guid]::NewGuid().ToString('N') + ".tmp")
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
        [string]$BaseUrl,
        [string]$Method,
        [string]$Path,
        [hashtable]$Body,
        [string]$CookieFile
    )

    $bodyFile = New-TempFile
    $curlArgs = @(
        '--silent', '--show-error',
        '--max-time', '20',
        '-X', $Method,
        '-o', $bodyFile,
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
    $jsonObject = $null
    if ($content) {
        try { $jsonObject = $content | ConvertFrom-Json } catch { $jsonObject = $null }
    }
    Remove-Item $bodyFile -Force -ErrorAction SilentlyContinue

    return [pscustomobject]@{
        Status  = $status
        Content = $content
        Json    = $jsonObject
    }
}

try {
    Write-Host ("V9 多实例验证（{0}）" -f ($BaseUrls -join ', ')) -ForegroundColor Cyan

    if ($BaseUrls.Count -lt 2) {
        Write-Host '-BaseUrls 至少要给 2 个实例，否则验证不了跨实例行为。' -ForegroundColor Red
        exit 2
    }
    $adminPhone = $env:ADMIN_INIT_PHONE
    $adminPassword = $env:ADMIN_INIT_PASSWORD
    if (-not $adminPhone -or -not $adminPassword) {
        Write-Host '缺少 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD：资质审核需要管理员会话。' -ForegroundColor Red
        exit 2
    }

    $a = $BaseUrls[0]
    $b = $BaseUrls[1]

    # ---------------------------------------------------------------- 验证 1：实例存活
    Write-Host "`n=== 验证 1：三个实例共享同一 MySQL / Redis ===" -ForegroundColor Cyan
    foreach ($url in $BaseUrls) {
        $health = Invoke-DemoRequest -BaseUrl $url -Method GET -Path '/actuator/health'
        Write-Check ($health.Status -eq 200 -and $health.Json.status -eq 'UP') `
            "1.x 实例存活 $url" "status=$($health.Status) health=$($health.Json.status)"
    }

    # ---------------------------------------------------------------- 准备账号与任务
    $phone = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
    $employerJar = New-TempFile
    $register = Invoke-DemoRequest -BaseUrl $a -Method POST -Path '/api/identity/register' -Body @{
        phone    = $phone
        password = $DemoPassword
        nickname = 'verify-v9-employer'
        role     = 'EMPLOYER'
    }
    $employerId = $register.Json.data.userId
    $login = Invoke-DemoRequest -BaseUrl $a -Method POST -Path '/api/identity/login' -Body @{
        account  = $phone
        password = $DemoPassword
    } -CookieFile $employerJar
    Write-Check ($login.Status -eq 200) '0.1 注册并登录雇主' "status=$($login.Status) userId=$employerId"

    [void](Invoke-DemoRequest -BaseUrl $a -Method POST -Path '/api/identity/qualification' -Body @{
        subjectType = 'INDIVIDUAL'
        subjectName = 'verify-v9'
        idLast4     = '1234'
    } -CookieFile $employerJar)

    $adminJar = New-TempFile
    [void](Invoke-DemoRequest -BaseUrl $a -Method POST -Path '/api/identity/login' -Body @{
        account  = $adminPhone
        password = $adminPassword
    } -CookieFile $adminJar)
    $review = Invoke-DemoRequest -BaseUrl $a -Method POST `
        -Path "/api/admin/qualifications/$employerId/review" -Body @{ decision = 'APPROVED' } `
        -CookieFile $adminJar
    Write-Check ($review.Status -eq 200) '0.2 资质审核通过' "status=$($review.Status)"

    $recharge = Invoke-DemoRequest -BaseUrl $a -Method POST -Path '/api/fund/recharge' `
        -Body @{ amount = $RechargeAmount } -CookieFile $employerJar
    Write-Check ("$($recharge.Json.data.available)" -eq "$RechargeAmount") `
        '0.3 充值 10000.00 元' "available=$($recharge.Json.data.available)"

    $draft = Invoke-DemoRequest -BaseUrl $a -Method POST -Path '/api/tasks' -Body @{
        title          = 'verify-v9 task'
        description    = 'v9 multi instance'
        category       = 'demo'
        unitPrice      = $UnitPrice
        slotCount      = $SlotCount
        startTime      = (Get-Date).AddDays(1).ToString('yyyy-MM-ddTHH:mm:ss')
        endTime        = (Get-Date).AddDays(2).ToString('yyyy-MM-ddTHH:mm:ss')
        workLocation   = 'demo-location'
        settlementNote = 'demo-note'
    } -CookieFile $employerJar
    $taskId = $draft.Json.data.id
    Write-Check ($draft.Status -eq 200 -and $taskId) '0.4 创建草稿' "taskId=$taskId"

    # ---------------------------------------------------------------- 验证 2：幂等 + 条件更新
    Write-Host "`n=== 验证 2：重复发布幂等（不需要分布式锁） ===" -ForegroundColor Cyan
    $publishStatuses = @()
    for ($i = 0; $i -lt $PublishRounds; $i++) {
        $url = $BaseUrls[$i % $BaseUrls.Count]
        $publish = Invoke-DemoRequest -BaseUrl $url -Method POST -Path "/api/tasks/$taskId/publish" `
            -CookieFile $employerJar
        $publishStatuses += $publish.Status
    }
    Write-Check (($publishStatuses | Where-Object { $_ -ne 200 }).Count -eq 0) `
        "2.1 轮询 $PublishRounds 次发布全部 200" "statuses=$($publishStatuses -join ',')"

    $detailOnB = Invoke-DemoRequest -BaseUrl $b -Method GET -Path "/api/tasks/$taskId" -CookieFile $employerJar
    $expectedEscrow = $UnitPrice * $SlotCount
    $slots = @($detailOnB.Json.data.slotOccupancy)
    Write-Check ("$($detailOnB.Json.data.escrowAmount)" -eq "$expectedEscrow" -and $slots.Count -eq $SlotCount) `
        '2.2 托管金额与名额未翻倍' `
        "escrow=$($detailOnB.Json.data.escrowAmount) expected=$expectedEscrow slots=$($slots.Count)"

    # ---------------------------------------------------------------- 验证 3：缓存跨实例失效
    Write-Host "`n=== 验证 3：缓存跨实例失效（修复前最长脏 60 秒） ===" -ForegroundColor Cyan
    [void](Invoke-DemoRequest -BaseUrl $a -Method GET -Path "/api/tasks/$taskId" -CookieFile $employerJar)

    $cancel = Invoke-DemoRequest -BaseUrl $b -Method POST -Path "/api/tasks/$taskId/cancel" -CookieFile $employerJar
    Write-Check ($cancel.Status -eq 200) '3.1 B 实例撤单成功' "status=$($cancel.Status)"

    $seenCancelAtMs = -1
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $CacheWaitSeconds) {
        $fresh = Invoke-DemoRequest -BaseUrl $a -Method GET -Path "/api/tasks/$taskId" -CookieFile $employerJar
        if ($fresh.Json.data.cancelledAt) {
            $seenCancelAtMs = [int]$sw.ElapsedMilliseconds
            break
        }
        Start-Sleep -Milliseconds 200
    }
    Write-Check ($seenCancelAtMs -ge 0) `
        "3.2 A 实例 ${CacheWaitSeconds}s 内看到撤单（广播生效）" "elapsedMs=$seenCancelAtMs"

    # ---------------------------------------------------------------- 验证 4：ShedLock（看日志）
    Write-Host @"

=== 验证 4：ShedLock 只跑一次（需看日志） ===
  三个实例同时运行时，每轮扫描在日志里应只有 1 条执行记录：
    - 任务到期：task-expiry-scan
    - 自动验收：auto-acceptance-scan
    - 待开始扫描：contract-pending-start-scan
  打款派发 / 查证 / 重试是动态锁（executeWithLock，锁落在单据上），
  并发场景下同一单据只应被一个实例处理。
  说明：这一项无法由 HTTP 断言，必须对照三个实例的控制台输出。
"@ -ForegroundColor Cyan

    Write-Host ("`n合计：PASS={0} FAIL={1}" -f $script:PassCount, $script:FailCount) `
        -ForegroundColor ($(if ($script:FailCount -eq 0) { 'Green' } else { 'Red' }))
    if ($script:FailCount -gt 0) { exit 1 }
}
finally {
    foreach ($file in $script:TempFiles) {
        Remove-Item $file -Force -ErrorAction SilentlyContinue
    }
}
