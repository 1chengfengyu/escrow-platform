#requires -Version 5.1
<#
.SYNOPSIS
    V1 五条演示一键跑（v1-c6）。只用 curl.exe，兼容 Windows PowerShell 5.1。

.DESCRIPTION
    前置条件（脚本不负责启动）：
      1. 应用已启动（mvn spring-boot:run），默认监听 127.0.0.1:8080；
      2. MySQL 与 Redis 可达（docker compose --profile core up -d）；
      3. 已设置 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD（演示 5 的管理员视角需要）。

    五条演示：会话 / 双键限流与锁定 / 越权 403 / 日志脱敏 / 响应脱敏。
    每条打印 [PASS] 或 [FAIL]；任一 FAIL 时退出码为 1。

.NOTES
    每次运行都会注册随机手机号的新账号，可重复执行。
    注意注册口按 IP 限 5 次/分钟：一次完整运行会注册 4 个账号，
    一分钟内连续重跑会被 429 拦截，等窗口过去再执行即可。
    演示口令由 ESCROW_DEMO_PASSWORD 提供，脚本内不保存任何凭据。
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'

# 演示账号口令：脚本内常量，仅用于本地演示，不对应任何真实账号
. (Join-Path $PSScriptRoot 'demo-config.ps1')
$WrongPassword = 'Wrong#2026Aa'

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
    $file = Join-Path $env:TEMP ("demo-v1-" + [guid]::NewGuid().ToString('N') + ".tmp")
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
        '--max-time', '10',
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
        # 请求体全部使用 ASCII 值（nickname 用 demo-* ），避免 PowerShell 5.1 控制台编码把中文写坏
        $json = ($Body | ConvertTo-Json -Compress)
        # PowerShell 5.1 调原生 exe 时会把参数里的双引号吞掉，直接传 $json 会变成 {phone:139...}；
        # 必须落成 UTF-8 无 BOM 文件，用 curl 的 @文件 语法传（临时文件统一在 finally 清理）
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
    return '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
}

function New-DemoAccount {
    param([string]$Role)
    $phone = New-DemoPhone
    $response = Invoke-DemoRequest -Method POST -Path '/api/identity/register' -Body @{
        phone    = $phone
        password = $DemoPassword
        nickname = "demo-$Role"
        role     = $Role
    }
    return [pscustomobject]@{
        Phone   = $phone
        Status  = $response.Status
        UserId  = $response.Json.data.userId
        Content = $response.Content
    }
}

function Get-HeaderValue {
    param([string]$Headers, [string]$Name)
    foreach ($line in ($Headers -split "`r?`n")) {
        if ($line -match ('(?i)^' + [regex]::Escape($Name) + ':\s*(.+)$')) {
            return $Matches[1].Trim()
        }
    }
    return ''
}

try {
    Write-Host "V1 演示脚本（$BaseUrl）" -ForegroundColor Cyan

    # ---------------------------------------------------------------- 演示 1：会话
    Write-Host "`n=== 演示 1：会话进 Redis（注册 → 登录 → 带 Cookie 200 / 不带 Cookie 401） ===" -ForegroundColor Cyan
    $worker = New-DemoAccount -Role 'WORKER'
    Write-Check ($worker.Status -eq 200 -and $worker.UserId) '1.1 注册普通用户' `
        "status=$($worker.Status) userId=$($worker.UserId)"

    $workerJar = New-TempFile
    $login = Invoke-DemoRequest -Method POST -Path '/api/identity/login' -Body @{
        account  = $worker.Phone
        password = $DemoPassword
    } -CookieFile $workerJar
    Write-Check ($login.Status -eq 200) '1.2 登录并拿到会话' `
        "status=$($login.Status) code=$($login.Json.code) cookie=$(Test-Path $workerJar)"

    $meWithCookie = Invoke-DemoRequest -Method GET -Path '/api/identity/me' -CookieFile $workerJar
    Write-Check ($meWithCookie.Status -eq 200 -and $meWithCookie.Json.code -eq 'SUCCESS') '1.3 带 Cookie 访问 /me' `
        "status=$($meWithCookie.Status) userId=$($meWithCookie.Json.data.userId)"

    $meWithoutCookie = Invoke-DemoRequest -Method GET -Path '/api/identity/me'
    Write-Check ($meWithoutCookie.Status -eq 401 -and $meWithoutCookie.Json.code -eq 'UNAUTHENTICATED') `
        '1.4 不带 Cookie 访问 /me' "status=$($meWithoutCookie.Status) code=$($meWithoutCookie.Json.code)"

    # ---------------------------------------------------------------- 演示 2：双键限流与锁定
    Write-Host "`n=== 演示 2：双键限流与账号锁定（NFR-SEC.06 / FR-ACCT.LOGIN.02） ===" -ForegroundColor Cyan

    $locked = New-DemoAccount -Role 'WORKER'
    $lastFailure = $null
    for ($i = 1; $i -le 5; $i++) {
        $lastFailure = Invoke-DemoRequest -Method POST -Path '/api/identity/login' -Body @{
            account  = $locked.Phone
            password = $WrongPassword
        }
    }
    Write-Check ($lastFailure.Status -eq 423 -and $lastFailure.Json.code -eq 'ACCOUNT_LOCKED') `
        '2.1 A 账号连错 5 次，第 5 次即锁定' `
        "status=$($lastFailure.Status) code=$($lastFailure.Json.code)"

    $limited = New-DemoAccount -Role 'WORKER'
    $limitedJar = New-TempFile
    $lastSuccess = $null
    for ($i = 1; $i -le 11; $i++) {
        $lastSuccess = Invoke-DemoRequest -Method POST -Path '/api/identity/login' -Body @{
            account  = $limited.Phone
            password = $DemoPassword
        } -CookieFile $limitedJar
    }
    $retryAfter = Get-HeaderValue -Headers $lastSuccess.Headers -Name 'Retry-After'
    Write-Check ($lastSuccess.Status -eq 429 -and $lastSuccess.Json.code -eq 'RATE_LIMITED' -and $retryAfter -match '^\d+$') `
        '2.2 B 账号 1 分钟内第 11 次请求被限流' `
        "status=$($lastSuccess.Status) code=$($lastSuccess.Json.code) Retry-After=$retryAfter"

    # ---------------------------------------------------------------- 演示 3：越权
    Write-Host "`n=== 演示 3：普通用户访问管理端接口（FR-ADMIN.QUAL.01 的越权边界） ===" -ForegroundColor Cyan
    $forbidden = Invoke-DemoRequest -Method GET -Path '/api/admin/qualifications' -CookieFile $workerJar
    Write-Check ($forbidden.Status -eq 403 -and $forbidden.Json.code -eq 'FORBIDDEN') `
        '3.1 普通用户会话访问 /api/admin/qualifications' `
        "status=$($forbidden.Status) code=$($forbidden.Json.code)"

    # ---------------------------------------------------------------- 演示 4：日志脱敏
    Write-Host "`n=== 演示 4：日志脱敏（NFR-SEC.04） ===" -ForegroundColor Cyan
    $logPath = Join-Path (Split-Path $PSScriptRoot -Parent) 'logs\escrow-platform.log'
    if (-not (Test-Path $logPath)) {
        Write-Check $false '4.1 日志中无明文手机号' "未找到 $logPath（应用是否用本仓库的 logback-spring.xml 启动？）"
        Write-Check $false '4.2 结构化安全日志可检索' "未找到 $logPath"
    }
    else {
        $tail = (Get-Content -Path $logPath -Tail 2000) -join "`n"
        $plainPhones = [regex]::Matches($tail, '(?<!\d)1[3-9]\d{9}(?!\d)').Count
        Write-Check ($plainPhones -eq 0) '4.1 日志中无明文手机号' `
            "末 2000 行命中 $plainPhones 处"

        $securityLines = ([regex]::Matches($tail, 'SECURITY traceId=')).Count
        Write-Check ($securityLines -gt 0) '4.2 结构化安全日志可检索' `
            "末 2000 行 SECURITY 行 $securityLines 条"
    }

    # ---------------------------------------------------------------- 演示 5：响应脱敏
    Write-Host "`n=== 演示 5：同一接口，管理员看完整号码、普通用户看打码（NFR-SEC.03） ===" -ForegroundColor Cyan
    $employer = New-DemoAccount -Role 'EMPLOYER'
    if (-not $employer.UserId) {
        Write-Check $false '5.1 普通用户查看他人名片' "雇主注册失败 status=$($employer.Status)"
        Write-Check $false '5.2 管理员查看他人名片' "雇主注册失败 status=$($employer.Status)"
    }
    else {
        $cardPath = "/api/identity/users/$($employer.UserId)/card"

        $cardAsWorker = Invoke-DemoRequest -Method GET -Path $cardPath -CookieFile $workerJar
        $maskedPhone = $cardAsWorker.Json.data.phone
        Write-Check ($cardAsWorker.Status -eq 200 -and $maskedPhone -match '^\d{3}\*{4}\d{4}$') `
            '5.1 普通用户查看他人名片（手机号打码）' `
            "status=$($cardAsWorker.Status) phone=$maskedPhone"

        $adminPhone = $env:ADMIN_INIT_PHONE
        $adminPassword = $env:ADMIN_INIT_PASSWORD
        if (-not $adminPhone -or -not $adminPassword) {
            Write-Check $false '5.2 管理员查看他人名片（原样输出）' `
                '未设置 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD'
        }
        else {
            $adminJar = New-TempFile
            $adminLogin = Invoke-DemoRequest -Method POST -Path '/api/identity/login' -Body @{
                account  = $adminPhone
                password = $adminPassword
            } -CookieFile $adminJar

            if ($adminLogin.Status -ne 200) {
                Write-Check $false '5.2 管理员查看他人名片（原样输出）' `
                    "管理员登录失败 status=$($adminLogin.Status) code=$($adminLogin.Json.code)"
            }
            else {
                $cardAsAdmin = Invoke-DemoRequest -Method GET -Path $cardPath -CookieFile $adminJar
                Write-Check ($cardAsAdmin.Status -eq 200 -and $cardAsAdmin.Json.data.phone -eq $employer.Phone) `
                    '5.2 管理员查看他人名片（原样输出）' `
                    "status=$($cardAsAdmin.Status) phone=$($cardAsAdmin.Json.data.phone)"
            }
        }
    }
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
