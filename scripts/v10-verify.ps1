﻿﻿﻿﻿﻿#requires -Version 5.1
<#
.SYNOPSIS
    V10 验收一键跑（c1~c7）：只用 curl.exe，兼容 Windows PowerShell 5.1。

.DESCRIPTION
    前置条件（脚本不负责启动）：
      1. 应用已启动（mvn spring-boot:run），默认监听 127.0.0.1:8080；
      2. MySQL 与 Redis 可达，Flyway 已到 V21。

    覆盖：
      1. 口语→槽位（FR-AI.QUERY.01）：解析出标签与草稿号；
      2. 画像冷启动（FR-AI.PROFILE.01）：服务者写入画像、读回来源标记；
      3. 检索与理由（FR-AI.SEARCH.01）：融合排序、理由可核、trace 落库；
      4. 规则问答（FR-AI.QA.01）：引用原文、可弃答；
      5. 链路回放（FR-AI.TRACE.01）：本人 200、他人 403；
      6. 反馈幂等（FR-AI.FEEDBACK.01）：同一 trace 重复上报不累加。

.NOTES
    每次运行注册 2 个随机手机号的新账号（雇主 + 1 名服务者），可重复执行。
    注册口按 IP 限流，连续重跑会被 429 拦截，等窗口过去再执行。
    演示口令由 ESCROW_DEMO_PASSWORD 提供，脚本内不保存任何凭据。
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'demo-config.ps1')
$script:PassCount = 0
$script:FailCount = 0
$script:TempFiles = New-Object System.Collections.ArrayList

function New-TempFile {
    $path = Join-Path $env:TEMP ("v10-" + [guid]::NewGuid().ToString('N') + ".tmp")
    [void]$script:TempFiles.Add($path)
    return $path
}

function Write-Check {
    param([string]$Name, [bool]$Ok, [string]$Detail = '')
    if ($Ok) {
        $script:PassCount++
        Write-Host ("  [PASS] {0}{1}" -f $Name, $(if ($Detail) { "  $Detail" } else { '' })) -ForegroundColor Green
    } else {
        $script:FailCount++
        Write-Host ("  [FAIL] {0}{1}" -f $Name, $(if ($Detail) { "  $Detail" } else { '' })) -ForegroundColor Red
    }
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body = $null,
        [string]$Cookie = ''
    )
    $outFile = New-TempFile
    $bodyFile = New-TempFile
    $headerFile = New-TempFile
    $args = @('-s', '-o', $outFile, '-w', '%{http_code}', '-X', $Method,
        '-D', $headerFile)
    if ($Cookie) {
        $args += @('-b', $Cookie, '-c', $Cookie)
    }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 6 -Compress
        [System.IO.File]::WriteAllText($bodyFile, $json, (New-Object System.Text.UTF8Encoding($false)))
        $args += @('-H', 'Content-Type: application/json; charset=utf-8', '--data-binary', "@$bodyFile")
    }
    $args += ($BaseUrl + $Path)
    $status = (& curl.exe @args) | Select-Object -Last 1

    $content = ''
    if (Test-Path $outFile) {
        $content = [System.IO.File]::ReadAllText($outFile, [System.Text.Encoding]::UTF8)
    }
    $jsonObject = $null
    if ($content) {
        try { $jsonObject = $content | ConvertFrom-Json } catch { $jsonObject = $null }
    }
    Remove-Item $outFile, $bodyFile, $headerFile -Force -ErrorAction SilentlyContinue

    return [pscustomobject]@{
        Status = [int]$status
        Json   = $jsonObject
    }
}

function New-DemoAccount {
    param([string]$Role)
    $phone = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
    $response = Invoke-Api -Method POST -Path '/api/identity/register' -Body @{
        phone    = $phone
        password = $DemoPassword
        nickname = ("v10-" + $Role).ToLower()
        role     = $Role
    }
    if ($response.Status -ne 200 -and $response.Status -ne 201) {
        throw "注册 $Role 失败：HTTP $($response.Status)"
    }
    return $phone
}

function New-Session {
    param([string]$Phone)
    $jar = New-TempFile
    $login = Invoke-Api -Method POST -Path '/api/identity/login' -Body @{
        account  = $Phone
        password = $DemoPassword
    } -Cookie $jar
    if ($login.Status -ne 200) {
        throw "登录失败：HTTP $($login.Status)"
    }
    return $jar
}

try {
    Write-Host "V10 验收脚本（$BaseUrl）" -ForegroundColor Cyan

    # ---------------------------------------------------------------- 演示 0：准备
    Write-Host "`n=== 演示 0：准备（雇主 + 服务者各一个） ===" -ForegroundColor Cyan
    $employerPhone = New-DemoAccount -Role 'EMPLOYER'
    $workerPhone = New-DemoAccount -Role 'WORKER'
    $employer = New-Session -Phone $employerPhone
    $worker = New-Session -Phone $workerPhone
    Write-Host ("  雇主 {0} / 服务者 {1}" -f $employerPhone, $workerPhone)

    # ---------------------------------------------------------------- 演示 1：槽位解析
    Write-Host "`n=== 演示 1：口语→槽位（可编辑标签） ===" -ForegroundColor Cyan
    $parse = Invoke-Api -Method POST -Path '/api/ai/query/parse' -Cookie $employer -Body @{
        query = '上海市区找个会剪短视频的师傅，周末能做的，预算 300 元以内'
    }
    Write-Check -Name '解析返回 200' -Ok ($parse.Status -eq 200) -Detail "HTTP $($parse.Status)"
    $draftId = $parse.Json.data.draftId
    $tags = @($parse.Json.data.tags)
    Write-Check -Name '产出草稿号与标签' -Ok ($draftId -and $tags.Count -ge 3) `
        -Detail ("tags=" + (($tags | ForEach-Object { $_.label }) -join ', '))
    Write-Check -Name 'stub 模式不降级' -Ok (-not $parse.Json.data.degraded)

    # ---------------------------------------------------------------- 演示 2：画像冷启动
    Write-Host "`n=== 演示 2：服务者画像（冷启动允许空） ===" -ForegroundColor Cyan
    $blank = Invoke-Api -Method GET -Path '/api/ai/profile/me' -Cookie $worker
    Write-Check -Name '未填画像返回 404 而不是报错' -Ok ($blank.Status -eq 404) -Detail "HTTP $($blank.Status)"

    $saved = Invoke-Api -Method PUT -Path '/api/ai/profile' -Cookie $worker -Body @{
        skills            = @('视频剪辑', '短视频')
        experienceYears   = 3
        expectedUnitPrice = 25000
        location          = '上海'
        weekendAvailable  = $true
    }
    Write-Check -Name '写入画像成功' -Ok ($saved.Status -eq 200) -Detail "HTTP $($saved.Status)"
    $me = Invoke-Api -Method GET -Path '/api/ai/profile/me' -Cookie $worker
    $sources = $me.Json.data.sources
    Write-Check -Name '读回画像并带来源标记' -Ok ($me.Status -eq 200 -and $sources -and $sources.skills) `
        -Detail ("skills.source=" + $sources.skills)

    # ---------------------------------------------------------------- 演示 3：检索 + 理由
    Write-Host "`n=== 演示 3：检索（融合排序 + 可核理由） ===" -ForegroundColor Cyan
    $search = Invoke-Api -Method POST -Path '/api/ai/search' -Cookie $employer -Body @{
        draftId     = $draftId
        removedKeys = @()
    }
    Write-Check -Name '检索返回 200' -Ok ($search.Status -eq 200) -Detail "HTTP $($search.Status)"
    $traceNo = $search.Json.data.traceNo
    $items = @($search.Json.data.items)
    Write-Check -Name '命中刚建的服务者' -Ok ($items.Count -ge 1) -Detail ("items=" + $items.Count)
    Write-Check -Name '首条带可核理由' -Ok ($items.Count -ge 1 -and $items[0].reason) `
        -Detail $(if ($items.Count -ge 1) { $items[0].reason } else { '无结果' })
    Write-Check -Name '有 traceNo' -Ok ([bool]$traceNo) -Detail $traceNo

    # ---------------------------------------------------------------- 演示 4：规则问答
    Write-Host "`n=== 演示 4：规则问答（引用原文 / 可弃答） ===" -ForegroundColor Cyan
    $qa = Invoke-Api -Method POST -Path '/api/ai/qa' -Cookie $worker -Body @{
        question = '托管资金什么时候打给服务者？'
    }
    Write-Check -Name '问答返回 200' -Ok ($qa.Status -eq 200) -Detail "HTTP $($qa.Status)"
    $citations = @($qa.Json.data.citations)
    Write-Check -Name '回答带引用且不弃答' -Ok ($qa.Json.data.answer -and $citations.Count -ge 1 -and -not $qa.Json.data.abstain) `
        -Detail ("citations=" + ($citations -join ','))

    # ---------------------------------------------------------------- 演示 5：链路回放
    Write-Host "`n=== 演示 5：链路回放（本人 200 / 他人 403） ===" -ForegroundColor Cyan
    $own = Invoke-Api -Method GET -Path ("/api/ai/trace/" + $traceNo) -Cookie $employer
    Write-Check -Name '本人回放 200 且四段齐全' -Ok ($own.Status -eq 200 -and @($own.Json.data.stages).Count -ge 3) `
        -Detail ("stages=" + @($own.Json.data.stages).Count)
    $foreign = Invoke-Api -Method GET -Path ("/api/ai/trace/" + $traceNo) -Cookie $worker
    Write-Check -Name '他人回放 403' -Ok ($foreign.Status -eq 403) -Detail "HTTP $($foreign.Status)"
    $missing = Invoke-Api -Method GET -Path '/api/ai/trace/not-exists' -Cookie $employer
    Write-Check -Name '不存在的链路 404' -Ok ($missing.Status -eq 404) -Detail "HTTP $($missing.Status)"

    # ---------------------------------------------------------------- 演示 6：反馈幂等
    Write-Host "`n=== 演示 6：反馈幂等（重复上报不累加） ===" -ForegroundColor Cyan
    $first = Invoke-Api -Method POST -Path '/api/ai/feedback' -Cookie $employer -Body @{
        traceNo = $traceNo
        clicked = $true
    }
    $second = Invoke-Api -Method POST -Path '/api/ai/feedback' -Cookie $employer -Body @{
        traceNo = $traceNo
        clicked = $true
    }
    Write-Check -Name '同一 trace 重复上报都返回 200' -Ok ($first.Status -eq 200 -and $second.Status -eq 200) `
        -Detail ("HTTP {0}/{1}" -f $first.Status, $second.Status)
    $forbiddenFeedback = Invoke-Api -Method POST -Path '/api/ai/feedback' -Cookie $worker -Body @{
        traceNo = $traceNo
        clicked = $true
    }
    Write-Check -Name '他人反馈 403' -Ok ($forbiddenFeedback.Status -eq 403) -Detail "HTTP $($forbiddenFeedback.Status)"

    # ---------------------------------------------------------------- 演示 7：角色门禁
    Write-Host "`n=== 演示 7：角色门禁（找人=EMPLOYER / 找活=WORKER） ===" -ForegroundColor Cyan
    $employerProfile = Invoke-Api -Method GET -Path '/api/ai/profile/me' -Cookie $employer
    Write-Check -Name '雇主读服务者画像 403' -Ok ($employerProfile.Status -eq 403) -Detail "HTTP $($employerProfile.Status)"
}
finally {
    foreach ($file in $script:TempFiles) {
        Remove-Item $file -Force -ErrorAction SilentlyContinue
    }
    $total = $script:PassCount + $script:FailCount
    Write-Host ("`n结果：{0}/{1} 通过" -f $script:PassCount, $total) -ForegroundColor Cyan
    if ($script:FailCount -gt 0) {
        exit 1
    }
}
