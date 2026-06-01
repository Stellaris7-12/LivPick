param(
    [Parameter(Mandatory = $false)]
    [ValidateSet("flash-burst-5k-100", "flash-burst-5k-500", "flash-sustain-5k-100", "flash-sustain-5k-500", "stability-consumer-pause", "all")]
    [string]$Scenario = "all",

    [Parameter(Mandatory = $false)]
    [string]$JMeterHome = "C:\Users\heyunhui\Documents\apache-jmeter-5.6.3",

    [Parameter(Mandatory = $false)]
    [string]$MySqlDb = "livpick_db_cache_mq",

    [Parameter(Mandatory = $false)]
    [string]$RedisContainer = "livpick-redis",

    [Parameter(Mandatory = $false)]
    [string]$KafkaContainer = "livpick-kafka",

    [Parameter(Mandatory = $false)]
    [string]$KafkaConsumerGroup = "livpick-consumer-group",

    [Parameter(Mandatory = $false)]
    [string]$AppBaseUrl = "http://127.0.0.1:8081",

    [Parameter(Mandatory = $false)]
    [int]$VoucherId = 7
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
Set-Location $root

$suiteRoot = "benchmark-final/suites/db-cache-mq/flash-sale"
$jmeterBat = Join-Path $JMeterHome "bin\jmeter.bat"
if (-not (Test-Path $jmeterBat)) {
    throw "JMeter not found at $jmeterBat"
}

$env:MYSQL_PWD = "heyunhui2856"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runRoot = Join-Path "target\benchmark\jmeter\flash-sale\db-cache-mq" "run-$timestamp"
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null

function Invoke-AppJson {
    param([string]$Method, [string]$Path, [string]$Body = "")
    if ([string]::IsNullOrWhiteSpace($Body)) {
        return Invoke-RestMethod -Method $Method -Uri ($AppBaseUrl.TrimEnd("/") + $Path) -TimeoutSec 30
    }
    return Invoke-RestMethod -Method $Method -Uri ($AppBaseUrl.TrimEnd("/") + $Path) -TimeoutSec 30 -ContentType "application/json" -Body $Body
}

function Reset-Metrics {
    Invoke-AppJson -Method "Post" -Path "/benchmark/admin/metrics/reset" | Out-Null
}

function Reset-BenchmarkSeckillState {
    Invoke-AppJson -Method "Post" -Path "/benchmark/admin/seckill/reset" | Out-Null
    Invoke-AppJson -Method "Post" -Path "/benchmark/admin/seckill/reset-redis" -Body "{""voucherId"":$VoucherId}" | Out-Null
}

function Set-ConsumerPaused {
    param([bool]$Paused)
    $pausedJson = if ($Paused) { "true" } else { "false" }
    Invoke-AppJson -Method "Post" -Path "/benchmark/admin/config/consumer-pause" -Body "{""paused"":$pausedJson}" | Out-Null
}

function Get-Metrics {
    $response = Invoke-AppJson -Method "Get" -Path "/benchmark/metrics"
    if (-not $response.success) {
        throw "Failed to get metrics: $($response.errorMsg)"
    }
    return $response.data
}

function Get-DrainStatus {
    $response = Invoke-AppJson -Method "Get" -Path "/benchmark/admin/mq/drain-status"
    if (-not $response.success) {
        throw "Failed to get drain status: $($response.errorMsg)"
    }
    return $response.data
}

function Wait-ForDrain {
    $deadline = (Get-Date).AddMinutes(3)
    do {
        $drain = Get-DrainStatus
        if ($drain.drained) {
            return $drain
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "MQ backlog did not drain within 3 minutes"
}

function Get-KafkaLag {
    $output = docker exec $KafkaContainer sh -c "/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group $KafkaConsumerGroup" 2>$null
    if (-not $output) {
        return 0L
    }
    $totalLag = 0L
    foreach ($line in $output) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith("GROUP")) {
            continue
        }
        $parts = ($line -split '\s+') | Where-Object { $_ -ne "" }
        if ($parts.Count -lt 6) {
            continue
        }
        $lagValue = 0L
        if ([long]::TryParse($parts[5], [ref]$lagValue)) {
            $totalLag += $lagValue
        }
    }
    return $totalLag
}

function Wait-ForAsyncSettlement {
    $deadline = (Get-Date).AddMinutes(20)
    $stableRounds = 0
    $lastSignature = ""
    do {
        $drain = Get-DrainStatus
        $metrics = Get-Metrics
        $kafkaLag = Get-KafkaLag
        $signature = @(
            $metrics.seckill.kafkaSendSuccess,
            $metrics.seckill.pendingRegistered,
            $metrics.seckill.pendingRetried,
            $metrics.seckill.pendingRollback,
            $metrics.seckill.consumerCreated,
            $metrics.seckill.consumerReactivated,
            $metrics.seckill.consumerDuplicate,
            $metrics.seckill.consumerFailure,
            $metrics.seckill.timeoutClosed
        ) -join "|"

        if ($drain.drained -and $kafkaLag -eq 0) {
            if ($signature -eq $lastSignature) {
                $stableRounds++
            } else {
                $lastSignature = $signature
                $stableRounds = 1
            }
            if ($stableRounds -ge 3) {
                return $metrics
            }
        } else {
            $stableRounds = 0
            $lastSignature = ""
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Async settlement did not converge within 20 minutes"
}

function Parse-JtlSummary {
    param([string]$JtlPath, [string]$ScenarioName)
    if (-not (Test-Path $JtlPath)) {
        throw "JMeter result file not found: $JtlPath"
    }
    $rows = Import-Csv $JtlPath
    $samples = $rows.Count
    $successRows = $rows | Where-Object { $_.success -eq "true" }
    $elapsedValues = @($rows | ForEach-Object { [int]$_.elapsed })
    $timeValues = @($rows | ForEach-Object { [int64]$_.timeStamp })
    $durationMs = ([int64]($timeValues | Measure-Object -Maximum).Maximum) - ([int64]($timeValues | Measure-Object -Minimum).Minimum)
    if ($durationMs -le 0) { $durationMs = 1 }
    [pscustomobject]@{
        Scenario = $ScenarioName
        Samples = $samples
        Success = $successRows.Count
        Throughput = [math]::Round($samples / ($durationMs / 1000.0), 2)
        AvgMs = [math]::Round((($elapsedValues | Measure-Object -Average).Average), 2)
    }
}

function Convert-ToDouble {
    param([object]$Value)
    if ($null -eq $Value -or "$Value" -eq "") {
        return 0.0
    }
    return [double]::Parse("$Value", [System.Globalization.CultureInfo]::InvariantCulture)
}

function Convert-ObjectToDelta {
    param([object]$Before, [object]$After)
    $delta = [ordered]@{}
    foreach ($prop in $After.PSObject.Properties) {
        $beforeValue = 0
        $afterValue = Convert-ToDouble $prop.Value
        if ($Before -and $Before.PSObject.Properties.Match($prop.Name).Count -gt 0) {
            $beforeValue = Convert-ToDouble $Before.($prop.Name)
        }
        $delta[$prop.Name] = $afterValue - $beforeValue
    }
    return [pscustomobject]$delta
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

function Reset-FlashState {
    param([string]$SqlFile)
    Reset-BenchmarkSeckillState
    mysql -h 127.0.0.1 -P 3306 -u root -D $MySqlDb -e (Get-Content $SqlFile -Raw) 2>$null | Out-Null
    docker exec $RedisContainer redis-cli DEL "seckill:stock:$VoucherId" "seckill:order:$VoucherId" | Out-Null
    $stock = Get-DbScalar -Sql "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = $VoucherId;"
    if (-not [string]::IsNullOrWhiteSpace($stock)) {
        docker exec $RedisContainer redis-cli SET "seckill:stock:$VoucherId" "$($stock.Trim())" | Out-Null
    }
}

function Invoke-FlashScenario {
    param(
        [string]$Name,
        [string]$JmxFile,
        [int]$Threads,
        [int]$RampUpSeconds,
        [int]$DurationSeconds,
        [string]$ResetSql,
        [bool]$PauseConsumer = $false
    )

    Wait-ForAsyncSettlement | Out-Null
    Reset-FlashState -SqlFile $ResetSql
    Wait-ForAsyncSettlement | Out-Null
    Reset-Metrics
    Set-ConsumerPaused -Paused $PauseConsumer
    $before = Get-Metrics

    $scenarioDir = Join-Path $runRoot $Name
    New-Item -ItemType Directory -Force -Path $scenarioDir | Out-Null
    $jtl = Join-Path $scenarioDir "$Name.jtl"
    $dashboard = Join-Path $scenarioDir "dashboard"
    $jmeterArgs = @(
        "-n",
        "-t", $JmxFile,
        "-Jhost=127.0.0.1",
        "-Jport=8081",
        "-Jprotocol=http",
        "-Jthreads=$Threads",
        "-JrampUpSeconds=$RampUpSeconds",
        "-JdurationSeconds=$DurationSeconds",
        "-JuserCsv=benchmark-final/suites/db-cache-mq/standard/data/user_ids_unique.csv",
        "-l", $jtl,
        "-e",
        "-o", $dashboard
    )
    & $jmeterBat @jmeterArgs | Out-Null

    if ($PauseConsumer) {
        Start-Sleep -Seconds 8
        Set-ConsumerPaused -Paused $false
    }
    $after = Wait-ForAsyncSettlement
    $drain = Get-DrainStatus
    $delta = [pscustomobject]@{
        seckill = Convert-ObjectToDelta -Before $before.seckill -After $after.seckill
        cache = Convert-ObjectToDelta -Before $before.cache -After $after.cache
        timeout = Convert-ObjectToDelta -Before $before.timeout -After $after.timeout
        stability = Convert-ObjectToDelta -Before $before.stability -After $after.stability
    }
    $summary = Parse-JtlSummary -JtlPath $jtl -ScenarioName $Name
    $summary | Add-Member -NotePropertyName ApiAccepted -NotePropertyValue $delta.seckill.apiAccepted
    $summary | Add-Member -NotePropertyName PendingRegistered -NotePropertyValue $delta.seckill.pendingRegistered
    $summary | Add-Member -NotePropertyName ConsumerCreated -NotePropertyValue $delta.seckill.consumerCreated
    $summary | Add-Member -NotePropertyName ConsumerReactivated -NotePropertyValue $delta.seckill.consumerReactivated
    $summary | Add-Member -NotePropertyName ConsumerDuplicate -NotePropertyValue $delta.seckill.consumerDuplicate
    $summary | Add-Member -NotePropertyName ConsumerFailure -NotePropertyValue $delta.seckill.consumerFailure
    $summary | Add-Member -NotePropertyName MqBacklogPeak -NotePropertyValue $after.stability.mqBacklogPeak
    $summary | Add-Member -NotePropertyName DrainCompletedAt -NotePropertyValue $after.stability.mqDrainCompletedAt
    $summary | Add-Member -NotePropertyName ConsumerPauseAccepted -NotePropertyValue $delta.stability.consumerPauseAccepted
    $summary | Add-Member -NotePropertyName FinalStock -NotePropertyValue (Get-DbScalar -Sql "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = $VoucherId;")
    $summary | Add-Member -NotePropertyName FinalOrders -NotePropertyValue (Get-DbScalar -Sql "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = $VoucherId;")
    $summary | Add-Member -NotePropertyName Drained -NotePropertyValue $drain.drained
    $summary | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 (Join-Path $scenarioDir "$Name.summary.json")
    $delta | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 (Join-Path $scenarioDir "benchmark-metrics-delta.json")
    return $summary
}

$aggregate = New-Object System.Collections.Generic.List[object]

if ($Scenario -eq "all") {
    $aggregate.Add((Invoke-FlashScenario -Name "flash-burst-5k-100" -JmxFile "$suiteRoot/flash-burst-high-contrast.jmx" -Threads 1000 -RampUpSeconds 3 -DurationSeconds 10 -ResetSql "$suiteRoot/sql/reset_stock_flash_100.sql")) | Out-Null
    $aggregate.Add((Invoke-FlashScenario -Name "flash-burst-5k-500" -JmxFile "$suiteRoot/flash-burst-high-contrast.jmx" -Threads 1000 -RampUpSeconds 3 -DurationSeconds 10 -ResetSql "$suiteRoot/sql/reset_stock_flash_500.sql")) | Out-Null
    $aggregate.Add((Invoke-FlashScenario -Name "flash-sustain-5k-100" -JmxFile "$suiteRoot/flash-sustain-high-contrast.jmx" -Threads 500 -RampUpSeconds 5 -DurationSeconds 60 -ResetSql "$suiteRoot/sql/reset_stock_flash_100.sql")) | Out-Null
    $aggregate.Add((Invoke-FlashScenario -Name "flash-sustain-5k-500" -JmxFile "$suiteRoot/flash-sustain-high-contrast.jmx" -Threads 500 -RampUpSeconds 5 -DurationSeconds 60 -ResetSql "$suiteRoot/sql/reset_stock_flash_500.sql")) | Out-Null
    $aggregate.Add((Invoke-FlashScenario -Name "stability-consumer-pause" -JmxFile "$suiteRoot/flash-sustain-high-contrast.jmx" -Threads 500 -RampUpSeconds 5 -DurationSeconds 30 -ResetSql "$suiteRoot/sql/reset_stock_flash_500.sql" -PauseConsumer $true)) | Out-Null
} else {
    switch ($Scenario) {
        "flash-burst-5k-100" { $aggregate.Add((Invoke-FlashScenario -Name $Scenario -JmxFile "$suiteRoot/flash-burst-high-contrast.jmx" -Threads 1000 -RampUpSeconds 3 -DurationSeconds 10 -ResetSql "$suiteRoot/sql/reset_stock_flash_100.sql")) | Out-Null }
        "flash-burst-5k-500" { $aggregate.Add((Invoke-FlashScenario -Name $Scenario -JmxFile "$suiteRoot/flash-burst-high-contrast.jmx" -Threads 1000 -RampUpSeconds 3 -DurationSeconds 10 -ResetSql "$suiteRoot/sql/reset_stock_flash_500.sql")) | Out-Null }
        "flash-sustain-5k-100" { $aggregate.Add((Invoke-FlashScenario -Name $Scenario -JmxFile "$suiteRoot/flash-sustain-high-contrast.jmx" -Threads 500 -RampUpSeconds 5 -DurationSeconds 60 -ResetSql "$suiteRoot/sql/reset_stock_flash_100.sql")) | Out-Null }
        "flash-sustain-5k-500" { $aggregate.Add((Invoke-FlashScenario -Name $Scenario -JmxFile "$suiteRoot/flash-sustain-high-contrast.jmx" -Threads 500 -RampUpSeconds 5 -DurationSeconds 60 -ResetSql "$suiteRoot/sql/reset_stock_flash_500.sql")) | Out-Null }
        "stability-consumer-pause" { $aggregate.Add((Invoke-FlashScenario -Name $Scenario -JmxFile "$suiteRoot/flash-sustain-high-contrast.jmx" -Threads 500 -RampUpSeconds 5 -DurationSeconds 30 -ResetSql "$suiteRoot/sql/reset_stock_flash_500.sql" -PauseConsumer $true)) | Out-Null }
    }
}

$aggregate | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $runRoot "aggregate-summary.csv")
Write-Host "DB-cache-mq flash-sale benchmark results saved to $runRoot"
