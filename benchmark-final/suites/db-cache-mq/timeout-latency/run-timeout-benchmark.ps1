param(
    [Parameter(Mandatory = $false)]
    [ValidateSet("fallback-only", "delay-queue-fallback", "all")]
    [string]$Scenario = "all",

    [Parameter(Mandatory = $false)]
    [string]$AppBaseUrl = "http://127.0.0.1:8081",

    [Parameter(Mandatory = $false)]
    [string]$MySqlDb = "livpick_db_cache_mq",

    [Parameter(Mandatory = $false)]
    [int]$VoucherId = 7,

    [Parameter(Mandatory = $false)]
    [int]$OrderTimeoutSeconds = 15,

    [Parameter(Mandatory = $false)]
    [int]$UserCount = 30
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
Set-Location $root
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runRoot = Join-Path "target\benchmark\timeout-latency\db-cache-mq" "run-$timestamp"
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
$env:MYSQL_PWD = "heyunhui2856"

function Invoke-AppJson {
    param([string]$Method, [string]$Path, [string]$Body = "", [hashtable]$Headers = @{})
    $uri = $AppBaseUrl.TrimEnd("/") + $Path
    if ([string]::IsNullOrWhiteSpace($Body)) {
        return Invoke-RestMethod -Method $Method -Uri $uri -TimeoutSec 30 -Headers $Headers
    }
    return Invoke-RestMethod -Method $Method -Uri $uri -TimeoutSec 30 -ContentType "application/json" -Body $Body -Headers $Headers
}

function Get-Metrics {
    $response = Invoke-AppJson -Method "Get" -Path "/benchmark/metrics"
    if (-not $response.success) {
        throw "Failed to get metrics: $($response.errorMsg)"
    }
    return $response.data
}

function Reset-State {
    Invoke-AppJson -Method "Post" -Path "/benchmark/admin/seckill/reset" | Out-Null
    Invoke-AppJson -Method "Post" -Path "/benchmark/admin/seckill/reset-redis" -Body "{""voucherId"":$VoucherId}" | Out-Null
    Invoke-AppJson -Method "Post" -Path "/benchmark/admin/metrics/reset" | Out-Null
    $sql = "UPDATE tb_seckill_voucher SET stock = 1000, begin_time = NOW() - INTERVAL 1 HOUR, end_time = NOW() + INTERVAL 2 HOUR WHERE voucher_id = $VoucherId; DELETE FROM tb_voucher_order WHERE voucher_id = $VoucherId;"
    mysql -h 127.0.0.1 -P 3306 -u root -D $MySqlDb -e $sql 2>$null | Out-Null
}

function Set-TimeoutMode {
    param([string]$Mode)
    Invoke-AppJson -Method "Post" -Path "/benchmark/admin/config/timeout-mode" -Body "{""mode"":""$Mode"",""orderTimeoutSeconds"":$OrderTimeoutSeconds}" | Out-Null
}

function Get-DbScalar {
    param([string]$Sql)
    $result = mysql -h 127.0.0.1 -P 3306 -u root -D $MySqlDb --batch --raw --skip-column-names -e $Sql 2>$null
    $value = ($result | Select-Object -First 1)
    if ($null -eq $value) {
        return ""
    }
    return "$value".Trim()
}

function Create-UnpaidOrders {
    $created = New-Object System.Collections.Generic.List[object]
    for ($i = 1; $i -le $UserCount; $i++) {
        $userId = 900000 + $i
        $response = Invoke-AppJson -Method "Post" -Path "/voucher-order/seckill/$VoucherId" -Headers @{ "X-Benchmark-User-Id" = "$userId" }
        if (-not $response.success) {
            throw "Failed to create unpaid order for benchmark user $userId: $($response.errorMsg)"
        }
        $created.Add([pscustomobject]@{
            userId = $userId
            orderId = [string]$response.data
        }) | Out-Null
    }
    return $created
}

function Wait-ForTimeoutClosed {
    $deadline = (Get-Date).AddSeconds($OrderTimeoutSeconds + 90)
    do {
        $metrics = Get-Metrics
        if ($metrics.seckill.timeoutClosed -ge $UserCount) {
            return $metrics
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $UserCount orders to close"
}

function Invoke-Scenario {
    param([string]$Name, [string]$Mode)
    Reset-State
    Set-TimeoutMode -Mode $Mode
    $scenarioDir = Join-Path $runRoot $Name
    New-Item -ItemType Directory -Force -Path $scenarioDir | Out-Null
    $orders = Create-UnpaidOrders
    $metrics = Wait-ForTimeoutClosed
    $summary = [pscustomobject]@{
        Scenario = $Name
        Mode = $Mode
        OrderTimeoutSeconds = $OrderTimeoutSeconds
        UserCount = $UserCount
        TimeoutClosed = $metrics.seckill.timeoutClosed
        Expired = $metrics.timeout.expired
        DelayQueueTriggered = $metrics.timeout.delayQueueTriggered
        FallbackTriggered = $metrics.timeout.fallbackTriggered
        CloseLagP50Ms = $metrics.timeout.closeLagP50Ms
        CloseLagP95Ms = $metrics.timeout.closeLagP95Ms
        CloseLagMaxMs = $metrics.timeout.closeLagMaxMs
        FinalCancelledOrders = (Get-DbScalar -Sql "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = $VoucherId AND status = 4;")
        FinalStock = (Get-DbScalar -Sql "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = $VoucherId;")
    }
    $summary | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 (Join-Path $scenarioDir "$Name.summary.json")
    $orders | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 (Join-Path $scenarioDir "orders.json")
    return $summary
}

$aggregate = New-Object System.Collections.Generic.List[object]
if ($Scenario -eq "all") {
    $aggregate.Add((Invoke-Scenario -Name "fallback-only" -Mode "FALLBACK_ONLY")) | Out-Null
    $aggregate.Add((Invoke-Scenario -Name "delay-queue-fallback" -Mode "DELAY_QUEUE_FALLBACK")) | Out-Null
} elseif ($Scenario -eq "fallback-only") {
    $aggregate.Add((Invoke-Scenario -Name "fallback-only" -Mode "FALLBACK_ONLY")) | Out-Null
} else {
    $aggregate.Add((Invoke-Scenario -Name "delay-queue-fallback" -Mode "DELAY_QUEUE_FALLBACK")) | Out-Null
}

$aggregate | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $runRoot "aggregate-summary.csv")
Write-Host "DB-cache-mq timeout benchmark results saved to $runRoot"
