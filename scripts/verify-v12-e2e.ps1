﻿#requires -Version 5.1
<#
.SYNOPSIS
    前端联调与验收脚本（PASS/FAIL 汇总，原始输出写入 logs/verify 目录）。

.DESCRIPTION
    1. 检查 53 个路由组件文件全部存在；
    2. 重新跑 typecheck / build（可 -SkipFrontend 跳过）；
    3. 对真实后端做冒烟：ping、注册、登录、me、任务列表、通知未读、越权 403。

    依赖：Node 22 在 PATH（脚本会重建 PATH）；后端已在 $BaseUrl 启动。
    示例：powershell -ExecutionPolicy Bypass -File scripts/verify-v12-e2e.ps1
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [switch]$SkipFrontend
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $repo 'logs/verify'
if (-not (Test-Path $logDir)) {
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
}
$logPath = Join-Path $logDir 'e2e-verify.log'
Start-Transcript -Path $logPath -Force | Out-Null

# 新开进程的 PATH 可能不含 Node，按用户级 / 机器级重新拼一次
$machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$env:Path = "$machinePath;$userPath"

$npmCmd = (Get-Command npm.cmd -ErrorAction SilentlyContinue).Source
if (-not $npmCmd) {
    throw 'npm.cmd 不在 PATH 中，无法执行前端构建检查'
}

$script:Checks = 0
$script:Failures = 0

function Check {
    param([string]$Name, [scriptblock]$Body)
    $script:Checks++
    try {
        & $Body
        Write-Host ("PASS  {0}" -f $Name)
    } catch {
        $script:Failures++
        Write-Host ("FAIL  {0} :: {1}" -f $Name, $_.Exception.Message)
    }
}

function Assert-True {
    param($Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body,
        $Session
    )
    $request = @{
        Uri         = "$BaseUrl$Path"
        Method      = $Method
        UseBasicParsing = $true
        TimeoutSec  = 15
        Headers     = @{ Accept = 'application/json' }
    }
    if ($Session) { $request.WebSession = $Session }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json'
        $request.Body = ($Body | ConvertTo-Json -Depth 6)
    }
    try {
        $response = Invoke-WebRequest @request
        return [pscustomobject]@{
            Status = [int]$response.StatusCode
            Json   = ($response.Content | ConvertFrom-Json)
        }
    } catch {
        $webResponse = $_.Exception.Response
        if (-not $webResponse) { throw }
        $reader = New-Object System.IO.StreamReader($webResponse.GetResponseStream())
        $text = $reader.ReadToEnd()
        $json = $null
        if ($text) {
            try { $json = $text | ConvertFrom-Json } catch { $json = $null }
        }
        return [pscustomobject]@{
            Status = [int]$webResponse.StatusCode
            Json   = $json
        }
    }
}

Write-Host "E2E verify  base=$BaseUrl  repo=$repo"

# ---------- 1. 路由组件 ----------
Check 'route components exist (53)' {
    $routerPath = Join-Path $repo 'frontend/src/router/index.ts'
    $router = Get-Content $routerPath -Raw
    $imports = [regex]::Matches($router, "import\('@/(views/[^']+)'\)")
    Assert-True ($imports.Count -eq 53) "expected 53 route components, got $($imports.Count)"
    foreach ($match in $imports) {
        $relative = $match.Groups[1].Value
        $full = Join-Path (Join-Path $repo 'frontend/src') $relative
        Assert-True (Test-Path $full) "missing route component: $relative"
    }
}

# ---------- 2. 前端构建 ----------
if (-not $SkipFrontend) {
    Check 'frontend typecheck' {
        Push-Location (Join-Path $repo 'frontend')
        try {
            $output = & $npmCmd run typecheck 2>&1
            if ($LASTEXITCODE -ne 0) {
                throw ("typecheck exit $LASTEXITCODE`n" + (($output | Select-Object -Last 20) -join "`n"))
            }
        } finally {
            Pop-Location
        }
    }

    Check 'frontend build' {
        Push-Location (Join-Path $repo 'frontend')
        try {
            $output = & $npmCmd run build 2>&1
            if ($LASTEXITCODE -ne 0) {
                throw ("build exit $LASTEXITCODE`n" + (($output | Select-Object -Last 20) -join "`n"))
            }
            $tail = ($output | Select-Object -Last 2) -join "`n"
            Write-Host $tail
        } finally {
            Pop-Location
        }
    }
}

# ---------- 3. 后端冒烟 ----------
Check 'GET /api/ping' {
    $result = Invoke-Api -Method GET -Path '/api/ping'
    Assert-True ($result.Status -eq 200) "status=$($result.Status)"
    Assert-True ($result.Json.code -eq 'SUCCESS') "code=$($result.Json.code)"
}

$phone = '19' + (Get-Random -Minimum 100000000 -Maximum 999999999)
. (Join-Path $PSScriptRoot 'demo-config.ps1')
$password = $DemoPassword
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

Check 'POST /api/identity/register' {
    $result = Invoke-Api -Method POST -Path '/api/identity/register' -Body @{
        phone    = $phone
        email    = "e2e$phone@example.com"
        password = $password
        nickname = 'E2E Worker'
        role     = 'WORKER'
    }
    Assert-True ($result.Status -eq 200) "status=$($result.Status) code=$($result.Json.code)"
    Assert-True ($result.Json.code -eq 'SUCCESS') "code=$($result.Json.code) message=$($result.Json.message)"
    Assert-True ($result.Json.data.userId -is [string]) 'userId should be serialized as string'
}

Check 'POST /api/identity/login (session cookie)' {
    $result = Invoke-Api -Method POST -Path '/api/identity/login' -Session $session -Body @{
        account  = $phone
        password = $password
    }
    Assert-True ($result.Status -eq 200) "status=$($result.Status)"
    Assert-True ($result.Json.code -eq 'SUCCESS') "code=$($result.Json.code)"
    Assert-True ($result.Json.data.currentIdentity -eq 'WORKER') "identity=$($result.Json.data.currentIdentity)"
}

Check 'GET /api/identity/me' {
    $result = Invoke-Api -Method GET -Path '/api/identity/me' -Session $session
    Assert-True ($result.Status -eq 200 -and $result.Json.code -eq 'SUCCESS') "status=$($result.Status) code=$($result.Json.code)"
    Assert-True ($result.Json.data.userId -is [string]) 'userId should be string'
}

Check 'GET /api/tasks (PageView shape)' {
    $result = Invoke-Api -Method GET -Path '/api/tasks?page=1&size=5' -Session $session
    Assert-True ($result.Status -eq 200 -and $result.Json.code -eq 'SUCCESS') "status=$($result.Status) code=$($result.Json.code)"
    Assert-True ($null -ne $result.Json.data.items) 'items missing'
    Assert-True ($result.Json.data.total -is [string]) 'total should be string (long -> string)'
}

Check 'GET /api/notifications/unread-count' {
    $result = Invoke-Api -Method GET -Path '/api/notifications/unread-count' -Session $session
    Assert-True ($result.Status -eq 200 -and $result.Json.code -eq 'SUCCESS') "status=$($result.Status) code=$($result.Json.code)"
}

Check 'GET /api/admin/dashboard as WORKER returns 403' {
    $result = Invoke-Api -Method GET -Path '/api/admin/dashboard' -Session $session
    Assert-True ($result.Status -eq 403) "expected 403, got $($result.Status)"
    Assert-True ($result.Json.code -eq 'FORBIDDEN') "code=$($result.Json.code)"
}

Write-Host ''
Write-Host ("RESULT  checks={0} failures={1}" -f $script:Checks, $script:Failures)
Write-Host "LOG     $logPath"

Stop-Transcript | Out-Null
if ($script:Failures -gt 0) { exit 1 }
exit 0
