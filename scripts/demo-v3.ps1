#requires -Version 5.1
<#
.SYNOPSIS
    V3 五段演示一键跑（v3-c5）。只用 curl.exe，兼容 Windows PowerShell 5.1。

.DESCRIPTION
    前置条件（脚本不负责启动）：
      1. 应用已启动（mvn spring-boot:run），默认监听 127.0.0.1:8080；
      2. MySQL 与 Redis 可达（docker compose --profile core up -d）；
      3. 已设置 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD（审核雇主资质需要管理员会话）。

    五段演示：
      0. 准备：雇主注册 → 资质提交与审核 → 充值；两名劳动者注册登录
      1. 报名与数据权限：发布 1 名额任务 → 两人报名 → 重复报名 409 → 雇主看 2 条 → 劳动者查列表 403
      2. 并发录用：两人抢 1 名额 → 恰好 1 成功、1 SLOT_TAKEN → 落败报名变已拒绝
      3. 履约与交付：开始 → 交付 → 详情「待验收」→ 我的签约「已交付」→ 重复交付 409
      4. 取消与再录用：2 名额任务 → 录用 → 取消 → 名额回「待占用」→ 再录用另一人
      5. 撤单收口：待处理报名全部拒绝 → 详情「已取消」+ recruitingClosed=true

    每段打印 [PASS] 或 [FAIL]；任一 FAIL 时退出码为 1。

    100 并发不放进本脚本：注册口按 IP 限 5 次/分钟，且 PowerShell 5.1 没有并行管道；
    「20 名额 × 30 并发不超卖」由真库测试 V3MultiSlotConcurrencyIntegrationTest 证明。

.NOTES
    每次运行注册 3 个随机手机号的新账号（雇主 + 2 名劳动者），可重复执行。
    注册口按 IP 限 5 次/分钟：一分钟内连续重跑 2 次会被 429 拦截，等窗口过去再执行即可。
    演示口令由 ESCROW_DEMO_PASSWORD 提供，脚本内不保存任何凭据。
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'

# 演示账号口令：脚本内常量，仅用于本地演示，不对应任何真实账号
. (Join-Path $PSScriptRoot 'demo-config.ps1')

# 演示金额（分）：充值 10000.00 元；三个任务共 5 个名额 × 300.00 元 = 1500.00 元
$RechargeAmount = 1000000L
$UnitPrice = 30000L

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
    $file = Join-Path $env:TEMP ("demo-v3-" + [guid]::NewGuid().ToString('N') + ".tmp")
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
        nickname = "demo-v3-$Role".ToLower()
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

# 建草稿：title / slotCount 必填，其余字段固定；发布走 POST /api/tasks/{id}/publish
function New-DemoDraft {
    param([string]$Title, [int]$Slots)
    $body = @{
        title          = $Title
        description    = 'v3 demo task'
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

# 按报名者列表统计某个状态的条数（list 响应里 total 是字符串）
function Get-ApplicationStatusCount {
    param($ListJson, [string]$Status)
    return @(@($ListJson.data.items) | Where-Object { $_.status -eq $Status }).Count
}

try {
    Write-Host "V3 演示脚本（$BaseUrl）" -ForegroundColor Cyan

    $adminPhone = $env:ADMIN_INIT_PHONE
    $adminPassword = $env:ADMIN_INIT_PASSWORD
    if (-not $adminPhone -or -not $adminPassword) {
        Write-Host '缺少 ADMIN_INIT_PHONE / ADMIN_INIT_PASSWORD：资质审核需要管理员会话。' -ForegroundColor Red
        Write-Host '先在当前终端设置这两个环境变量再重跑。' -ForegroundColor Red
        exit 2
    }

    # ---------------------------------------------------------------- 演示 0：准备
    Write-Host "`n=== 演示 0：雇主资质 + 充值 + 两名劳动者（3 个新账号） ===" -ForegroundColor Cyan

    $employer = New-DemoAccount -Role 'EMPLOYER'
    $employerSession = New-DemoSession -Phone $employer.Phone -Password $DemoPassword
    $script:EmployerJar = $employerSession.Jar
    Write-Check ($employer.Status -eq 200 -and $employerSession.Status -eq 200) '0.1 注册并登录雇主' `
        "status=$($employerSession.Status) userId=$($employer.UserId)"

    $qualification = Invoke-DemoRequest -Method POST -Path '/api/identity/qualification' -Body @{
        subjectType = 'INDIVIDUAL'
        subjectName = 'demo-v3-employer'
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
        -Body @{ amount = $RechargeAmount } -CookieFile $script:EmployerJar
    Write-Check ($recharge.Status -eq 200) '0.4 模拟充值 10000.00 元' `
        "available=$($recharge.Json.data.available)"

    $worker1 = New-DemoAccount -Role 'WORKER'
    $worker1Session = New-DemoSession -Phone $worker1.Phone -Password $DemoPassword
    $worker2 = New-DemoAccount -Role 'WORKER'
    $worker2Session = New-DemoSession -Phone $worker2.Phone -Password $DemoPassword
    Write-Check ($worker1Session.Status -eq 200 -and $worker2Session.Status -eq 200) `
        '0.5 注册并登录两名劳动者' "w1=$($worker1.UserId) w2=$($worker2.UserId)"

    # ---------------------------------------------------------------- 演示 1：报名与数据权限
    Write-Host "`n=== 演示 1：报名、重复报名拒绝、报名者列表数据权限（FR-SIGN.APPLY.01~.07） ===" -ForegroundColor Cyan

    $draftA = New-DemoDraft -Title 'demo-v3 one slot' -Slots 1
    $taskA = $draftA.Json.data.id
    $publishA = Publish-DemoTask -TaskId $taskA
    Write-Check ($draftA.Status -eq 200 -and $publishA.Status -eq 200) '1.1 发布 1 名额任务 A' `
        "taskId=$taskA"

    $applyW1 = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskA/applications" -CookieFile $worker1Session.Jar
    $applyW1Again = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskA/applications" -CookieFile $worker1Session.Jar
    Write-Check ($applyW1.Status -eq 200 -and $applyW1Again.Status -eq 409 -and
        $applyW1Again.Json.code -eq 'ALREADY_APPLIED') '1.2 W1 报名成功，重复报名 409 ALREADY_APPLIED' `
        "first=$($applyW1.Status) again=$($applyW1Again.Status)/$($applyW1Again.Json.code)"

    $applyW2 = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskA/applications" -CookieFile $worker2Session.Jar
    Write-Check ($applyW2.Status -eq 200) '1.3 W2 报名成功' "applicationId=$($applyW2.Json.data.id)"

    $employerList = Invoke-DemoRequest -Method GET -Path "/api/tasks/$taskA/applications" -CookieFile $script:EmployerJar
    Write-Check ($employerList.Status -eq 200 -and "$($employerList.Json.data.total)" -eq '2') `
        '1.4 雇主看到 2 条报名（报名时间倒序）' "total=$($employerList.Json.data.total)"

    $workerList = Invoke-DemoRequest -Method GET -Path "/api/tasks/$taskA/applications" -CookieFile $worker2Session.Jar
    Write-Check ($workerList.Status -eq 403) '1.5 劳动者查报名者列表 403（数据权限）' `
        "status=$($workerList.Status) code=$($workerList.Json.code)"

    $detailA = Invoke-DemoRequest -Method GET -Path "/api/tasks/$taskA" -CookieFile $worker2Session.Jar
    Write-Check ($detailA.Status -eq 200 -and $detailA.Json.data.status -eq 'TASK_RECRUITING' -and
        $detailA.Json.data.recruitingClosed -eq $false) '1.6 详情派生状态「招募中」' `
        "status=$($detailA.Json.data.status) closed=$($detailA.Json.data.recruitingClosed)"

    # ---------------------------------------------------------------- 演示 2：并发录用
    Write-Host "`n=== 演示 2：两人抢 1 名额，并发录用恰好成功一笔（FR-SIGN.HIRE.03） ===" -ForegroundColor Cyan

    $applicationW1 = $applyW1.Json.data.id
    $applicationW2 = $applyW2.Json.data.id

    # 两个 job 共用同一会话 jar（只读 -b，不写 -c），尽量同时发出
    $job1 = Start-Job -ScriptBlock {
        param($base, $task, $app, $jar)
        & curl.exe --silent --max-time 20 -X POST -b $jar -o NUL -w '%{http_code}' `
            "$base/api/tasks/$task/applications/$app/hire"
    } -ArgumentList $BaseUrl, $taskA, $applicationW1, $script:EmployerJar
    $job2 = Start-Job -ScriptBlock {
        param($base, $task, $app, $jar)
        & curl.exe --silent --max-time 20 -X POST -b $jar -o NUL -w '%{http_code}' `
            "$base/api/tasks/$task/applications/$app/hire"
    } -ArgumentList $BaseUrl, $taskA, $applicationW2, $script:EmployerJar

    [void](Wait-Job $job1, $job2 -Timeout 40)
    $code1 = 0; $code2 = 0
    [void][int]::TryParse((Receive-Job $job1 | Select-Object -Last 1), [ref]$code1)
    [void][int]::TryParse((Receive-Job $job2 | Select-Object -Last 1), [ref]$code2)
    Remove-Job $job1, $job2 -Force -ErrorAction SilentlyContinue

    $successCount = @(@($code1, $code2) | Where-Object { $_ -eq 200 }).Count
    $conflictCount = @(@($code1, $code2) | Where-Object { $_ -eq 409 }).Count
    Write-Check ($successCount -eq 1 -and $conflictCount -eq 1) '2.1 恰好一笔成功、一笔 409 SLOT_TAKEN' `
        "codes=$code1,$code2"

    if ($code1 -eq 200) {
        $winnerJar = $worker1Session.Jar
        $loserJar = $worker2Session.Jar
        $winnerName = 'W1'
    }
    else {
        $winnerJar = $worker2Session.Jar
        $loserJar = $worker1Session.Jar
        $winnerName = 'W2'
    }

    $listAfterHire = Invoke-DemoRequest -Method GET -Path "/api/tasks/$taskA/applications" -CookieFile $script:EmployerJar
    $hiredCount = Get-ApplicationStatusCount -ListJson $listAfterHire.Json -Status 'APPLY_HIRED'
    $rejectedCount = Get-ApplicationStatusCount -ListJson $listAfterHire.Json -Status 'APPLY_REJECTED'
    Write-Check ($hiredCount -eq 1 -and $rejectedCount -eq 1) '2.2 落败报名被满员收口置为已拒绝' `
        "hired=$hiredCount rejected=$rejectedCount"

    $winnerContracts = Invoke-DemoRequest -Method GET -Path '/api/contracts' -CookieFile $winnerJar
    $winnerContract = @($winnerContracts.Json.data.items) |
        Where-Object { "$($_.taskId)" -eq "$taskA" } | Select-Object -First 1
    $winnerContractId = $winnerContract.contractId
    Write-Check ($winnerContracts.Status -eq 200 -and $null -ne $winnerContractId) `
        '2.3 赢家「我的签约」出现该签约' "contractId=$winnerContractId winner=$winnerName"

    $loserContracts = Invoke-DemoRequest -Method GET -Path '/api/contracts' -CookieFile $loserJar
    $loserContract = @($loserContracts.Json.data.items) |
        Where-Object { "$($_.taskId)" -eq "$taskA" } | Select-Object -First 1
    Write-Check ($null -eq $loserContract) '2.4 输家「我的签约」没有该任务' `
        "items=$(@($loserContracts.Json.data.items).Count)"

    # ---------------------------------------------------------------- 演示 3：履约与交付
    Write-Host "`n=== 演示 3：开始履约 → 交付物 → 详情待验收（FR-SIGN.START.01 / FR-DELIVER.SUBMIT.01~.03） ===" -ForegroundColor Cyan

    $start = Invoke-DemoRequest -Method POST -Path "/api/contracts/$winnerContractId/start" -CookieFile $winnerJar
    Write-Check ($start.Status -eq 200) '3.1 赢家开始履约' "status=$($start.Status) code=$($start.Json.code)"

    $deliver = Invoke-DemoRequest -Method POST -Path "/api/contracts/$winnerContractId/deliver" -Body @{
        images      = @('https://img.example.com/demo-1.jpg')
        description = 'delivered by demo-v3'
    } -CookieFile $winnerJar
    Write-Check ($deliver.Status -eq 200 -and $deliver.Json.data.status -eq 'CONTRACT_DELIVERED') `
        '3.2 提交交付物 → 签约已交付' "status=$($deliver.Json.data.status)"

    $detailAfterDeliver = Invoke-DemoRequest -Method GET -Path "/api/tasks/$taskA" -CookieFile $script:EmployerJar
    Write-Check ($detailAfterDeliver.Json.data.status -eq 'TASK_PENDING_ACCEPTANCE') `
        '3.3 详情派生状态「待验收」（R8）' "status=$($detailAfterDeliver.Json.data.status)"

    $winnerContractsAfterDeliver = Invoke-DemoRequest -Method GET -Path '/api/contracts' -CookieFile $winnerJar
    $deliveredContract = @($winnerContractsAfterDeliver.Json.data.items) |
        Where-Object { "$($_.contractId)" -eq "$winnerContractId" } | Select-Object -First 1
    Write-Check ($null -ne $deliveredContract -and $deliveredContract.status -eq 'CONTRACT_DELIVERED') `
        '3.4 我的签约显示已交付' "status=$($deliveredContract.status)"

    $deliverAgain = Invoke-DemoRequest -Method POST -Path "/api/contracts/$winnerContractId/deliver" -Body @{
        images = @('https://img.example.com/demo-2.jpg')
    } -CookieFile $winnerJar
    Write-Check ($deliverAgain.Status -eq 409) '3.5 重复交付 409（不覆盖）' `
        "status=$($deliverAgain.Status) code=$($deliverAgain.Json.code)"

    # ---------------------------------------------------------------- 演示 4：取消与再录用
    Write-Host "`n=== 演示 4：取消签约 → 名额回待占用 → 再录用他人（FR-SIGN.CANCEL.01~.04） ===" -ForegroundColor Cyan

    $draftB = New-DemoDraft -Title 'demo-v3 two slots' -Slots 2
    $taskB = $draftB.Json.data.id
    $publishB = Publish-DemoTask -TaskId $taskB
    Write-Check ($draftB.Status -eq 200 -and $publishB.Status -eq 200) '4.1 发布 2 名额任务 B' `
        "taskId=$taskB"

    $applyB1 = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskB/applications" -CookieFile $worker1Session.Jar
    $applyB2 = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskB/applications" -CookieFile $worker2Session.Jar
    Write-Check ($applyB1.Status -eq 200 -and $applyB2.Status -eq 200) '4.2 两名劳动者报名任务 B' `
        "a1=$($applyB1.Json.data.id) a2=$($applyB2.Json.data.id)"

    $hireB1 = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskB/applications/$($applyB1.Json.data.id)/hire" `
        -CookieFile $script:EmployerJar
    $contractB1 = $hireB1.Json.data.contractId
    Write-Check ($hireB1.Status -eq 200 -and $contractB1) '4.3 录用 W1' "contractId=$contractB1"

    $cancelB1 = Invoke-DemoRequest -Method POST -Path "/api/contracts/$contractB1/cancel" -CookieFile $worker1Session.Jar
    Write-Check ($cancelB1.Status -eq 200) '4.4 W1 取消签约' "status=$($cancelB1.Status) code=$($cancelB1.Json.code)"

    $detailB = Invoke-DemoRequest -Method GET -Path "/api/tasks/$taskB" -CookieFile $script:EmployerJar
    $availableAfterCancel = @(@($detailB.Json.data.slotOccupancy) |
        Where-Object { $_.status -eq 'SLOT_AVAILABLE' }).Count
    Write-Check ($availableAfterCancel -eq 2) '4.5 名额回到「待占用」（2 个可报）' `
        "available=$availableAfterCancel"

    $hireB2 = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskB/applications/$($applyB2.Json.data.id)/hire" `
        -CookieFile $script:EmployerJar
    $detailB2 = Invoke-DemoRequest -Method GET -Path "/api/tasks/$taskB" -CookieFile $script:EmployerJar
    $occupiedB = @(@($detailB2.Json.data.slotOccupancy) | Where-Object { $_.status -eq 'SLOT_OCCUPIED' }).Count
    $availableB = @(@($detailB2.Json.data.slotOccupancy) | Where-Object { $_.status -eq 'SLOT_AVAILABLE' }).Count
    Write-Check ($hireB2.Status -eq 200 -and $occupiedB -eq 1 -and $availableB -eq 1 -and
        $detailB2.Json.data.status -eq 'TASK_RECRUITING') `
        '4.6 名额可再次录用，详情仍「招募中」（R1 优先）' `
        "status=$($detailB2.Json.data.status) occupied=$occupiedB available=$availableB"

    # ---------------------------------------------------------------- 演示 5：撤单收口
    Write-Host "`n=== 演示 5：撤单 → 待处理报名全部拒绝 → 详情已取消（FR-SIGN.APPLY.08 / R4） ===" -ForegroundColor Cyan

    $draftC = New-DemoDraft -Title 'demo-v3 cancel task' -Slots 2
    $taskC = $draftC.Json.data.id
    $publishC = Publish-DemoTask -TaskId $taskC
    $applyC = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskC/applications" -CookieFile $worker1Session.Jar
    Write-Check ($publishC.Status -eq 200 -and $applyC.Status -eq 200) '5.1 发布任务 C 且 W1 报名' `
        "taskId=$taskC applicationId=$($applyC.Json.data.id)"

    $cancelC = Invoke-DemoRequest -Method POST -Path "/api/tasks/$taskC/cancel" -CookieFile $script:EmployerJar
    Write-Check ($cancelC.Status -eq 200) '5.2 雇主撤单' "status=$($cancelC.Status) code=$($cancelC.Json.code)"

    $listC = Invoke-DemoRequest -Method GET -Path "/api/tasks/$taskC/applications" -CookieFile $script:EmployerJar
    $rejectedC = Get-ApplicationStatusCount -ListJson $listC.Json -Status 'APPLY_REJECTED'
    Write-Check ($rejectedC -eq 1) '5.3 待处理报名被置为已拒绝' "rejected=$rejectedC"

    $detailC = Invoke-DemoRequest -Method GET -Path "/api/tasks/$taskC" -CookieFile $script:EmployerJar
    Write-Check ($detailC.Json.data.status -eq 'TASK_CANCELLED' -and $detailC.Json.data.recruitingClosed -eq $true) `
        '5.4 详情「已取消」且招募已收口' `
        "status=$($detailC.Json.data.status) closed=$($detailC.Json.data.recruitingClosed)"

    Write-Host ("`n合计：PASS={0} FAIL={1}" -f $script:PassCount, $script:FailCount) `
        -ForegroundColor ($(if ($script:FailCount -eq 0) { 'Green' } else { 'Red' }))
    if ($script:FailCount -gt 0) { exit 1 }
}
finally {
    foreach ($file in $script:TempFiles) {
        Remove-Item $file -Force -ErrorAction SilentlyContinue
    }
}
