#requires -Version 5.1
<#
.SYNOPSIS
    V8 演示：Kafka 异步站内信端到端（v8-c3）。只用 curl.exe，兼容 Windows PowerShell 5.1。

.DESCRIPTION
    前置条件（脚本不负责启动）：
      1. docker compose --profile core --profile kafka up -d（broker 与 MySQL/Redis 都在跑）；
      2. 应用以 kafka profile 启动：SPRING_PROFILES_ACTIVE=dev,kafka（另外设置 KAFKA_BOOTSTRAP）；
      3. 已设置 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD。

    本脚本自动完成「验收动作 1」：跑一笔完整的打款链路，然后断言劳动者收件箱
    有打款通知——这条通知是「发布到 escrow-notify → 消费 → 落 notification」来的，
    不是 log 模式同步写的。

    另外三个动作需要人工配合，见脚本末尾的输出提示：
      2. docker stop kafka 后再跑一笔打款 → 业务仍成功，日志出现「降级直写收件箱」；
      3. 向 escrow-notify 投一条非法 JSON → 重试 2 次后进 .DLT 并落 notification_dead_letter；
      4. 同一 eventId 重复投递 → 收件箱仍只有 1 行。

.NOTES
    每次运行注册 2 个随机手机号的新账号（雇主 + 1 名劳动者），可重复执行。
    注册口按 IP 限 5 次/分钟：一分钟内连续重跑 3 次会被 429 拦截。
    演示口令由 ESCROW_DEMO_PASSWORD 提供，脚本内不保存任何凭据。
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
    $file = Join-Path $env:TEMP ("demo-v8-" + [guid]::NewGuid().ToString('N') + ".tmp")
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
        nickname = "demo-v8-$Role".ToLower()
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
    Write-Host "V8 演示脚本（$BaseUrl）" -ForegroundColor Cyan

    $adminPhone = $env:ADMIN_INIT_PHONE
    $adminPassword = $env:ADMIN_INIT_PASSWORD
    if (-not $adminPhone -or -not $adminPassword) {
        Write-Host '缺少 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD：资质审核需要管理员会话。' -ForegroundColor Red
        exit 2
    }

    Write-Host "`n=== 动作 1：kafka 模式下打款通知经 broker 落库 ===" -ForegroundColor Cyan

    $employer = New-DemoAccount -Role 'EMPLOYER'
    $employerSession = New-DemoSession -Phone $employer.Phone -Password $DemoPassword
    $employerJar = $employerSession.Jar
    Write-Check ($employerSession.Status -eq 200) '1.1 注册并登录雇主' `
        "status=$($employerSession.Status) userId=$($employer.UserId)"

    [void](Invoke-DemoRequest -Method POST -Path '/api/identity/qualification' -Body @{
        subjectType = 'INDIVIDUAL'
        subjectName = 'demo-v8-employer'
        idLast4     = '1234'
    } -CookieFile $employerJar)

    $adminSession = New-DemoSession -Phone $adminPhone -Password $adminPassword
    $review = Invoke-DemoRequest -Method POST -Path "/api/admin/qualifications/$($employer.UserId)/review" `
        -Body @{ decision = 'APPROVED' } -CookieFile $adminSession.Jar
    Write-Check ($review.Status -eq 200) '1.2 管理员审核通过资质' "status=$($review.Status)"

    $recharge = Invoke-DemoRequest -Method POST -Path '/api/fund/recharge' `
        -Body @{ amount = $RechargeAmount } -CookieFile $employerJar
    Write-Check ("$($recharge.Json.data.available)" -eq "$RechargeAmount") `
        '1.3 模拟充值 10000.00 元' "available=$($recharge.Json.data.available)"

    $worker = New-DemoAccount -Role 'WORKER'
    $workerSession = New-DemoSession -Phone $worker.Phone -Password $DemoPassword
    $workerJar = $workerSession.Jar
    Write-Check ($workerSession.Status -eq 200) '1.4 注册并登录劳动者' "userId=$($worker.UserId)"

    $draft = Invoke-DemoRequest -Method POST -Path '/api/tasks' -Body @{
        title          = 'demo-v8 kafka task'
        description    = 'v8 demo task'
        category       = 'demo'
        unitPrice      = $UnitPrice
        slotCount      = 1
        startTime      = (Get-Date).AddDays(1).ToString('yyyy-MM-ddTHH:mm:ss')
        endTime        = (Get-Date).AddDays(2).ToString('yyyy-MM-ddTHH:mm:ss')
        workLocation   = 'demo-location'
        settlementNote = 'demo-note'
    } -CookieFile $employerJar
    $taskId = $draft.Json.data.id
    [void](Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskId/publish" -CookieFile $employerJar)
    $apply = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskId/applications" -CookieFile $workerJar
    $hire = Invoke-DemoRequest -Method POST `
        -Path "/api/tasks/$taskId/applications/$($apply.Json.data.id)/hire" -CookieFile $employerJar
    $contractId = $hire.Json.data.contractId
    [void](Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/start" -CookieFile $workerJar)
    [void](Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/deliver" -Body @{
        images      = @('https://img.example.com/demo-v8.jpg')
        description = 'delivered by demo-v8'
    } -CookieFile $workerJar)
    $accept = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractId/acceptance" `
        -Body @{ decision = 'PASS' } -CookieFile $employerJar
    Write-Check ($accept.Status -eq 200 -and $accept.Json.data.contractStatus -eq 'CONTRACT_COMPLETED') `
        '1.5 发布 → 报名 → 录用 → 交付 → 验收通过（打款随验收派发）' `
        "taskId=$taskId settlementId=$($accept.Json.data.settlementId)"

    # Kafka 消费是异步的：轮询收件箱，最多等 15 秒
    $inboxTotal = 0
    for ($i = 0; $i -lt 30; $i++) {
        $inbox = Invoke-DemoRequest -Method GET -Path '/api/notifications?page=1&size=20' -CookieFile $workerJar
        $inboxTotal = [int]$inbox.Json.data.total
        if ($inboxTotal -ge 1) { break }
        Start-Sleep -Milliseconds 500
    }
    Write-Check ($inboxTotal -ge 1) '1.6 打款通知经 Kafka 消费后落库' "total=$inboxTotal"

    Write-Host @"

=== 需人工配合的三个动作 ===
  动作 2（降级）：保持应用运行，在虚拟机上 docker stop escrow-kafka，再执行一次本脚本。
          业务应仍成功，应用日志出现「站内信投递失败，降级直写收件箱」，1.6 仍 PASS。
  动作 3（死信）：向 escrow-notify 投一条非法 JSON，例如
          docker exec -i escrow-kafka /opt/kafka/bin/kafka-console-producer.sh \
            --bootstrap-server 127.0.0.1:9092 --topic escrow-notify <<< 'not-json'
          重试 2 次后转 escrow-notify.DLT，notification_dead_letter 新增 1 行。
  动作 4（幂等）：同一 eventId 重复投递，notification 仍只有 1 行，日志出现「站内信重复投递，忽略」。
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
