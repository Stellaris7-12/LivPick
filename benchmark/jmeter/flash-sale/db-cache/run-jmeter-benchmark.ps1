param(
    [Parameter(Mandatory = $false)]
    [ValidateSet("flash-burst-5k-100", "flash-burst-5k-500", "flash-sustain-5k-100", "flash-sustain-5k-500", "all")]
    [string]$Scenario = "all",

    [Parameter(Mandatory = $false)]
    [string]$JMeterHome = "C:\Users\heyunhui\Documents\apache-jmeter-5.6.3",

    [Parameter(Mandatory = $false)]
    [switch]$EnableMonitoring,

    [Parameter(Mandatory = $false)]
    [int]$SamplingIntervalSeconds = 2,

    [Parameter(Mandatory = $false)]
    [string]$MySqlDb = "livpick_db_cache",

    [Parameter(Mandatory = $false)]
    [string]$RedisContainer = "livpick-redis-db-cache",

    [Parameter(Mandatory = $false)]
    [string]$RedisPassword = "",

    [Parameter(Mandatory = $false)]
    [string]$AppBaseUrl = "http://127.0.0.1:8081",

    [Parameter(Mandatory = $false)]
    [int]$VoucherId = 7,

    [Parameter(Mandatory = $false)]
    [string]$UserCsv = "benchmark/jmeter/db-cache/data/user_ids_unique.csv"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
Set-Location $root

$suiteRoot = "benchmark\jmeter\flash-sale\db-cache"
$jmeterBat = Join-Path $JMeterHome "bin\jmeter.bat"
if (-not (Test-Path $jmeterBat)) {
    throw "JMeter not found at $jmeterBat"
}

$appUri = [Uri]$AppBaseUrl
$appPort = $appUri.Port
$appHost = $appUri.Host
$appProtocol = $appUri.Scheme
$appConnection = Get-NetTCPConnection -LocalPort $appPort -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $appConnection) {
    throw "Application is not listening on port $appPort"
}

$appPid = $appConnection.OwningProcess
$monitorScript = Join-Path $root "$suiteRoot\collect-runtime-monitor.ps1"
$powershellExe = Join-Path $env:SystemRoot "System32\WindowsPowerShell\v1.0\powershell.exe"
$jstatCommand = Get-Command jstat.exe -ErrorAction SilentlyContinue | Select-Object -First 1
$jstatPath = if ($jstatCommand) { $jstatCommand.Source } else { "" }
$env:MYSQL_PWD = "heyunhui2856"

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runRoot = Join-Path "target\benchmark\jmeter\flash-sale\db-cache" "run-$timestamp"
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null

$aggregate = New-Object System.Collections.Generic.List[object]

function Convert-ToDouble {
    param([object]$Value)
    if ($null -eq $Value -or "$Value" -eq "") {
        return 0.0
    }
    return [double]::Parse("$Value", [System.Globalization.CultureInfo]::InvariantCulture)
}

function Get-PercentileValue {
    param(
        [int[]]$Values,
        [double]$Percentile
    )
    if (-not $Values -or $Values.Count -eq 0) {
        return 0
    }
    $sorted = $Values | Sort-Object
    $index = [math]::Ceiling($Percentile * $sorted.Count) - 1
    if ($index -lt 0) { $index = 0 }
    if ($index -ge $sorted.Count) { $index = $sorted.Count - 1 }
    return [int]$sorted[$index]
}

function Invoke-AppJson {
    param(
        [string]$Method,
        [string]$Path
    )

    return Invoke-RestMethod -Method $Method -Uri ($AppBaseUrl.TrimEnd("/") + $Path) -TimeoutSec 30
}

function Get-BenchmarkMetricsPayload {
    $response = Invoke-AppJson -Method "Get" -Path "/benchmark/metrics"
    if (-not $response.success) {
        throw "Benchmark metrics endpoint failed: $($response.errorMsg)"
    }
    return $response.data
}

function Reset-BenchmarkMetrics {
    $response = Invoke-AppJson -Method "Post" -Path "/benchmark/admin/metrics/reset"
    if (-not $response.success) {
        throw "Reset benchmark metrics failed: $($response.errorMsg)"
    }
}

function Convert-ObjectToDelta {
    param(
        [object]$Before,
        [object]$After
    )

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

function Get-RedisDockerArgs {
    $args = @("exec", $RedisContainer, "redis-cli")
    if ($RedisPassword) {
        $args += @("-a", $RedisPassword)
    }
    return $args
}

function Invoke-RedisCli {
    param([string[]]$RedisArgs)
    return & docker @((Get-RedisDockerArgs) + $RedisArgs) 2>$null
}

function Clear-RedisPattern {
    param([string]$Pattern)

    $keys = @(Invoke-RedisCli -RedisArgs @("--raw", "--scan", "--pattern", $Pattern))
    foreach ($key in $keys) {
        if ([string]::IsNullOrWhiteSpace($key)) {
            continue
        }
        Invoke-RedisCli -RedisArgs @("DEL", $key) | Out-Null
    }
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

function Reset-Database {
    param([string]$SqlFile)

    $sql = Get-Content $SqlFile -Raw
    mysql -h 127.0.0.1 -P 3306 -u root -D $MySqlDb -e $sql 2>$null | Out-Null
}

function Reset-RedisState {
    Clear-RedisPattern -Pattern "cache:shop:*"
    Clear-RedisPattern -Pattern "cache:voucher:list:*"
    Invoke-RedisCli -RedisArgs @("DEL", "seckill:stock:$VoucherId", "seckill:order:$VoucherId") | Out-Null

    $stock = Get-DbScalar -Sql "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = $VoucherId;"
    if (-not [string]::IsNullOrWhiteSpace($stock)) {
        Invoke-RedisCli -RedisArgs @("SET", "seckill:stock:$VoucherId", "$stock") | Out-Null
        $seededStock = @(Invoke-RedisCli -RedisArgs @("GET", "seckill:stock:$VoucherId") | Select-Object -First 1)
        $seededValue = if ($seededStock.Count -gt 0 -and $seededStock[0] -ne $null) { "$($seededStock[0])".Trim() } else { "" }
        if ($seededValue -ne "$stock") {
            throw "Failed to seed Redis stock for voucher $VoucherId. Expected $stock but got '$seededValue'."
        }
    }
}

function Get-ScenarioDefinition {
    param([string]$Name)

    switch ($Name) {
        "flash-burst-5k-100" {
            return @{
                JmxFile = "$suiteRoot\flash-burst-high-contrast.jmx"
                Threads = 1000
                RampUpSeconds = 3
                DurationSeconds = 10
                ResetSql = "$suiteRoot\sql\reset_stock_flash_100.sql"
            }
        }
        "flash-burst-5k-500" {
            return @{
                JmxFile = "$suiteRoot\flash-burst-high-contrast.jmx"
                Threads = 1000
                RampUpSeconds = 3
                DurationSeconds = 10
                ResetSql = "$suiteRoot\sql\reset_stock_flash_500.sql"
            }
        }
        "flash-sustain-5k-100" {
            return @{
                JmxFile = "$suiteRoot\flash-sustain-high-contrast.jmx"
                Threads = 500
                RampUpSeconds = 5
                DurationSeconds = 60
                ResetSql = "$suiteRoot\sql\reset_stock_flash_100.sql"
            }
        }
        "flash-sustain-5k-500" {
            return @{
                JmxFile = "$suiteRoot\flash-sustain-high-contrast.jmx"
                Threads = 500
                RampUpSeconds = 5
                DurationSeconds = 60
                ResetSql = "$suiteRoot\sql\reset_stock_flash_500.sql"
            }
        }
        default {
            throw "Unsupported scenario: $Name"
        }
    }
}

function Get-ScenarioPostCheck {
    return @{
        Sql = "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = $VoucherId; SELECT COUNT(*) AS order_count FROM tb_voucher_order WHERE voucher_id = $VoucherId;"
        Type = "stock"
    }
}

function Parse-JtlSummary {
    param(
        [string]$JtlPath,
        [string]$ScenarioName
    )

    $rows = Import-Csv $JtlPath
    $samples = $rows.Count
    $successRows = $rows | Where-Object { $_.success -eq "true" }
    $failureRows = $rows | Where-Object { $_.success -ne "true" }
    $elapsedValues = @($rows | ForEach-Object { [int]$_.elapsed })
    $timeValues = @($rows | ForEach-Object { [int64]$_.timeStamp })

    $durationMs = ([int64]($timeValues | Measure-Object -Maximum).Maximum) - ([int64]($timeValues | Measure-Object -Minimum).Minimum)
    if ($durationMs -le 0) {
        $durationMs = 1
    }

    $failureBuckets = $failureRows | Group-Object responseMessage | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Count)" }

    return [pscustomobject]@{
        Scenario = $ScenarioName
        Samples = $samples
        Success = $successRows.Count
        Failed = $failureRows.Count
        Throughput = [math]::Round($samples / ($durationMs / 1000.0), 2)
        AvgMs = [math]::Round((($elapsedValues | Measure-Object -Average).Average), 2)
        P95Ms = Get-PercentileValue -Values $elapsedValues -Percentile 0.95
        P99Ms = Get-PercentileValue -Values $elapsedValues -Percentile 0.99
        MaxMs = [int](($elapsedValues | Measure-Object -Maximum).Maximum)
        ErrorPercent = if ($samples -eq 0) { 0 } else { [math]::Round(($failureRows.Count * 100.0) / $samples, 2) }
        FailureBuckets = ($failureBuckets -join "; ")
        FinalStock = ""
        FinalOrders = ""
        CacheHit = ""
        NullHit = ""
        CacheMiss = ""
        DbFallback = ""
        StaleHit = ""
        RebuildScheduled = ""
        RebuildSuccess = ""
        RebuildFailure = ""
        RebuildLockHit = ""
        RebuildLockMiss = ""
    }
}

function Get-MySqlStatusSnapshot {
    $rows = mysql -h 127.0.0.1 -P 3306 -u root --batch --raw --skip-column-names -e "SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Innodb_row_lock_current_waits','Innodb_row_lock_time','Innodb_row_lock_waits');" 2>$null
    $map = @{}
    foreach ($row in $rows) {
        if (-not $row) { continue }
        $parts = $row -split "`t"
        if ($parts.Length -lt 2) { continue }
        $map[$parts[0]] = [long]$parts[1]
    }
    return $map
}

function Add-PostCheckFields {
    param(
        [pscustomobject]$Summary,
        [string[]]$DbOutput
    )

    $numericLines = $DbOutput | Select-String '^([0-9]+)$'
    $Summary.FinalStock = (($numericLines | Select-Object -First 1).Matches.Value)
    $Summary.FinalOrders = (($numericLines | Select-Object -Skip 1 -First 1).Matches.Value)
}

function Add-BenchmarkMetricFields {
    param(
        [pscustomobject]$Summary,
        [pscustomobject]$MetricDelta
    )

    if (-not $MetricDelta) {
        return
    }

    $cacheDelta = $MetricDelta.cache
    if (-not $cacheDelta) {
        return
    }

    $Summary.CacheHit = $cacheDelta.cacheHit
    $Summary.NullHit = $cacheDelta.nullHit
    $Summary.CacheMiss = $cacheDelta.cacheMiss
    $Summary.DbFallback = $cacheDelta.dbFallback
    $Summary.StaleHit = $cacheDelta.staleHit
    $Summary.RebuildScheduled = $cacheDelta.rebuildScheduled
    $Summary.RebuildSuccess = $cacheDelta.rebuildSuccess
    $Summary.RebuildFailure = $cacheDelta.rebuildFailure
    $Summary.RebuildLockHit = $cacheDelta.rebuildLockHit
    $Summary.RebuildLockMiss = $cacheDelta.rebuildLockMiss
}

function Add-MonitoringSummary {
    param(
        [pscustomobject]$Summary,
        [string]$ScenarioDir,
        [hashtable]$StartStatus,
        [hashtable]$EndStatus
    )

    $runtimeCsv = Join-Path $ScenarioDir "runtime-monitor.csv"
    $gcCsv = Join-Path $ScenarioDir "gc-monitor.csv"
    $redisCsv = Join-Path $ScenarioDir "redis-monitor.csv"

    if (-not (Test-Path $runtimeCsv)) {
        return
    }

    $runtimeRows = Import-Csv $runtimeCsv
    if (-not $runtimeRows -or $runtimeRows.Count -eq 0) {
        return
    }

    $Summary | Add-Member -NotePropertyName MonitorSamples -NotePropertyValue $runtimeRows.Count
    $Summary | Add-Member -NotePropertyName MaxAppCpuPct -NotePropertyValue ([math]::Round((($runtimeRows | ForEach-Object { Convert-ToDouble $_.app_cpu_pct } | Measure-Object -Maximum).Maximum), 2))
    $Summary | Add-Member -NotePropertyName MaxMySqlCpuPct -NotePropertyValue ([math]::Round((($runtimeRows | ForEach-Object { Convert-ToDouble $_.mysql_cpu_pct } | Measure-Object -Maximum).Maximum), 2))
    $Summary | Add-Member -NotePropertyName MaxSystemCpuPct -NotePropertyValue ([math]::Round((($runtimeRows | ForEach-Object { Convert-ToDouble $_.system_cpu_pct } | Measure-Object -Maximum).Maximum), 2))
    $Summary | Add-Member -NotePropertyName MinSystemAvailableMb -NotePropertyValue ([math]::Round((($runtimeRows | ForEach-Object { Convert-ToDouble $_.system_available_mb } | Measure-Object -Minimum).Minimum), 2))
    $Summary | Add-Member -NotePropertyName MaxThreadsConnected -NotePropertyValue ([int](($runtimeRows | ForEach-Object { Convert-ToDouble $_.threads_connected } | Measure-Object -Maximum).Maximum))
    $Summary | Add-Member -NotePropertyName MaxThreadsRunning -NotePropertyValue ([int](($runtimeRows | ForEach-Object { Convert-ToDouble $_.threads_running } | Measure-Object -Maximum).Maximum))
    $Summary | Add-Member -NotePropertyName MaxRowLockCurrentWaits -NotePropertyValue ([int](($runtimeRows | ForEach-Object { Convert-ToDouble $_.innodb_row_lock_current_waits } | Measure-Object -Maximum).Maximum))
    $Summary | Add-Member -NotePropertyName MaxDataLockWaits -NotePropertyValue ([int](($runtimeRows | ForEach-Object { Convert-ToDouble $_.data_lock_waits } | Measure-Object -Maximum).Maximum))
    $Summary | Add-Member -NotePropertyName MaxMetadataLockWaits -NotePropertyValue ([int](($runtimeRows | ForEach-Object { Convert-ToDouble $_.metadata_lock_waits } | Measure-Object -Maximum).Maximum))
    $Summary | Add-Member -NotePropertyName MaxOldGenPct -NotePropertyValue ""
    $Summary | Add-Member -NotePropertyName YgcDelta -NotePropertyValue ""
    $Summary | Add-Member -NotePropertyName FgcDelta -NotePropertyValue ""
    $Summary | Add-Member -NotePropertyName GcTimeDeltaSec -NotePropertyValue ""
    $Summary | Add-Member -NotePropertyName MaxRedisCpuPct -NotePropertyValue ""
    $Summary | Add-Member -NotePropertyName MaxRedisMemMb -NotePropertyValue ""
    $Summary | Add-Member -NotePropertyName MaxRedisOpsPerSec -NotePropertyValue ""
    $Summary | Add-Member -NotePropertyName RedisHitsDelta -NotePropertyValue ""
    $Summary | Add-Member -NotePropertyName RedisMissesDelta -NotePropertyValue ""

    if ($StartStatus -and $EndStatus) {
        $Summary | Add-Member -NotePropertyName RowLockWaitsDelta -NotePropertyValue ([long]$EndStatus["Innodb_row_lock_waits"] - [long]$StartStatus["Innodb_row_lock_waits"])
        $Summary | Add-Member -NotePropertyName RowLockTimeDeltaMs -NotePropertyValue ([long]$EndStatus["Innodb_row_lock_time"] - [long]$StartStatus["Innodb_row_lock_time"])
    } else {
        $Summary | Add-Member -NotePropertyName RowLockWaitsDelta -NotePropertyValue ""
        $Summary | Add-Member -NotePropertyName RowLockTimeDeltaMs -NotePropertyValue ""
    }

    if (Test-Path $gcCsv) {
        $gcRows = Import-Csv $gcCsv
        if ($gcRows -and $gcRows.Count -gt 0) {
            $firstGc = $gcRows | Select-Object -First 1
            $lastGc = $gcRows | Select-Object -Last 1
            $Summary.MaxOldGenPct = [math]::Round((($gcRows | ForEach-Object { Convert-ToDouble $_.o } | Measure-Object -Maximum).Maximum), 2)
            $Summary.YgcDelta = [int](Convert-ToDouble $lastGc.ygc) - [int](Convert-ToDouble $firstGc.ygc)
            $Summary.FgcDelta = [int](Convert-ToDouble $lastGc.fgc) - [int](Convert-ToDouble $firstGc.fgc)
            $Summary.GcTimeDeltaSec = [math]::Round((Convert-ToDouble $lastGc.gct) - (Convert-ToDouble $firstGc.gct), 3)
        }
    }

    if (Test-Path $redisCsv) {
        $redisRows = Import-Csv $redisCsv
        if ($redisRows -and $redisRows.Count -gt 0) {
            $firstRedis = $redisRows | Select-Object -First 1
            $lastRedis = $redisRows | Select-Object -Last 1
            $Summary.MaxRedisCpuPct = [math]::Round((($redisRows | ForEach-Object { Convert-ToDouble $_.redis_cpu_pct } | Measure-Object -Maximum).Maximum), 2)
            $Summary.MaxRedisMemMb = [math]::Round((($redisRows | ForEach-Object { Convert-ToDouble $_.redis_mem_mb } | Measure-Object -Maximum).Maximum), 2)
            $Summary.MaxRedisOpsPerSec = [int](($redisRows | ForEach-Object { Convert-ToDouble $_.instantaneous_ops_per_sec } | Measure-Object -Maximum).Maximum)
            $Summary.RedisHitsDelta = [int64](Convert-ToDouble $lastRedis.keyspace_hits) - [int64](Convert-ToDouble $firstRedis.keyspace_hits)
            $Summary.RedisMissesDelta = [int64](Convert-ToDouble $lastRedis.keyspace_misses) - [int64](Convert-ToDouble $firstRedis.keyspace_misses)
        }
    }
}

function Export-MySqlDiagnostics {
    param(
        [string]$ScenarioDir,
        [datetime]$ScenarioStart,
        [datetime]$ScenarioEnd
    )

    $startText = $ScenarioStart.ToString("yyyy-MM-dd HH:mm:ss")
    $endText = $ScenarioEnd.ToString("yyyy-MM-dd HH:mm:ss")

    $digestSql = @"
SELECT DIGEST_TEXT, COUNT_STAR, ROUND(SUM_TIMER_WAIT/1000000000000,2) AS total_seconds, ROUND(AVG_TIMER_WAIT/1000000000,3) AS avg_ms, FIRST_SEEN, LAST_SEEN
FROM performance_schema.events_statements_summary_by_digest
WHERE LAST_SEEN BETWEEN '$startText' AND '$endText'
  AND (DIGEST_TEXT LIKE '%tb_seckill_voucher%' OR DIGEST_TEXT LIKE '%tb_voucher_order%')
ORDER BY SUM_TIMER_WAIT DESC
LIMIT 10;
"@

    $lockSql = @"
SELECT EVENT_NAME, COUNT_STAR, ROUND(SUM_TIMER_WAIT/1000000000000,2) AS total_seconds
FROM performance_schema.events_waits_summary_global_by_event_name
WHERE EVENT_NAME LIKE 'wait/lock/%'
ORDER BY SUM_TIMER_WAIT DESC
LIMIT 10;

SELECT OBJECT_SCHEMA, OBJECT_NAME, COUNT_STAR, ROUND(SUM_TIMER_WAIT/1000000000000,2) AS total_seconds
FROM performance_schema.table_lock_waits_summary_by_table
WHERE OBJECT_SCHEMA = '$MySqlDb'
ORDER BY SUM_TIMER_WAIT DESC
LIMIT 10;

SELECT * FROM performance_schema.data_lock_waits;
"@

    mysql -h 127.0.0.1 -P 3306 -u root -D performance_schema -e $digestSql 2>$null | Set-Content -Encoding UTF8 (Join-Path $ScenarioDir "statement-digest.txt")
    mysql -h 127.0.0.1 -P 3306 -u root -D performance_schema -e $lockSql 2>$null | Set-Content -Encoding UTF8 (Join-Path $ScenarioDir "lock-diagnostics.txt")
    mysql -h 127.0.0.1 -P 3306 -u root -e "SHOW ENGINE INNODB STATUS;" 2>$null | Set-Content -Encoding UTF8 (Join-Path $ScenarioDir "innodb-status.txt")
}

function Start-MonitorProcess {
    param(
        [string]$ScenarioDir
    )

    $stopFile = Join-Path $ScenarioDir "monitor.stop"
    $stdoutFile = Join-Path $ScenarioDir "monitor.stdout.txt"
    $stderrFile = Join-Path $ScenarioDir "monitor.stderr.txt"
    if (Test-Path $stopFile) {
        Remove-Item $stopFile -Force
    }

    $quotedMonitorScript = '"' + $monitorScript + '"'
    $quotedScenarioDir = '"' + $ScenarioDir + '"'
    $quotedStopFile = '"' + $stopFile + '"'
    $args = "-NoProfile -ExecutionPolicy Bypass -File $quotedMonitorScript -AppPid $appPid -ScenarioDir $quotedScenarioDir -StopFile $quotedStopFile -IntervalSeconds $SamplingIntervalSeconds -RedisContainer `"$RedisContainer`" -RedisPassword `"$RedisPassword`""

    if ($jstatPath) {
        $quotedJstatPath = '"' + $jstatPath + '"'
        $args += " -JstatPath $quotedJstatPath"
    }

    $process = Start-Process -FilePath $powershellExe -ArgumentList $args -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile
    return @{
        Process = $process
        StopFile = $stopFile
    }
}

function Stop-MonitorProcess {
    param($Monitor)

    if (-not $Monitor) {
        return
    }

    New-Item -ItemType File -Force -Path $Monitor.StopFile | Out-Null
    try {
        Wait-Process -Id $Monitor.Process.Id -Timeout ([math]::Max(10, $SamplingIntervalSeconds * 3))
    } catch {
        Stop-Process -Id $Monitor.Process.Id -Force -ErrorAction SilentlyContinue
    }
}

function Prepare-Scenario {
    param([hashtable]$Definition)

    Reset-Database -SqlFile $Definition.ResetSql
    Reset-RedisState
    Reset-BenchmarkMetrics
}

function Invoke-Scenario {
    param([string]$Name)

    $definition = Get-ScenarioDefinition -Name $Name
    Prepare-Scenario -Definition $definition

    $scenarioDir = Join-Path $runRoot $Name
    $dashboardDir = Join-Path $scenarioDir "dashboard"
    New-Item -ItemType Directory -Force -Path $scenarioDir | Out-Null

    $jtl = Join-Path $scenarioDir "$Name.jtl"
    $console = Join-Path $scenarioDir "$Name.console.txt"
    $dbCheck = Join-Path $scenarioDir "$Name.db-check.txt"

    $monitor = $null
    $statusStart = $null
    $statusEnd = $null
    $scenarioStart = Get-Date
    $metricsBefore = Get-BenchmarkMetricsPayload

    try {
        if ($EnableMonitoring) {
            $statusStart = Get-MySqlStatusSnapshot
            $monitor = Start-MonitorProcess -ScenarioDir $scenarioDir
        }

        $jmeterArgs = @(
            "-n",
            "-t", $definition.JmxFile,
            "-Jhost=$appHost",
            "-Jport=$appPort",
            "-Jprotocol=$appProtocol",
            "-JvoucherId=$VoucherId",
            "-Jthreads=$($definition.Threads)",
            "-JrampUpSeconds=$($definition.RampUpSeconds)",
            "-JdurationSeconds=$($definition.DurationSeconds)",
            "-JuserCsv=$UserCsv",
            "-l", $jtl,
            "-e",
            "-o", $dashboardDir
        )

        & $jmeterBat @jmeterArgs | Tee-Object $console
    } finally {
        $scenarioEnd = Get-Date
        if ($EnableMonitoring) {
            Stop-MonitorProcess -Monitor $monitor
            $statusEnd = Get-MySqlStatusSnapshot
            Export-MySqlDiagnostics -ScenarioDir $scenarioDir -ScenarioStart $scenarioStart -ScenarioEnd $scenarioEnd
        }
    }

    $metricsAfter = Get-BenchmarkMetricsPayload
    $metricDelta = [pscustomobject]@{
        cache = Convert-ObjectToDelta -Before $metricsBefore.cache -After $metricsAfter.cache
        redis = Convert-ObjectToDelta -Before $metricsBefore.redis -After $metricsAfter.redis
    }

    $metricsBefore | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 (Join-Path $scenarioDir "benchmark-metrics-before.json")
    $metricsAfter | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 (Join-Path $scenarioDir "benchmark-metrics-after.json")
    $metricDelta | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 (Join-Path $scenarioDir "benchmark-metrics-delta.json")

    $summary = Parse-JtlSummary -JtlPath $jtl -ScenarioName $Name
    Add-BenchmarkMetricFields -Summary $summary -MetricDelta $metricDelta

    $postCheck = Get-ScenarioPostCheck
    $dbOutput = mysql -h 127.0.0.1 -P 3306 -u root -D $MySqlDb -e $postCheck.Sql 2>$null
    $dbOutput | Set-Content -Encoding UTF8 $dbCheck
    Add-PostCheckFields -Summary $summary -DbOutput $dbOutput

    if ($EnableMonitoring) {
        Add-MonitoringSummary -Summary $summary -ScenarioDir $scenarioDir -StartStatus $statusStart -EndStatus $statusEnd
    }

    $aggregate.Add($summary) | Out-Null
    $summary | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $scenarioDir "$Name.summary.json")
}

$warmupSql = "$suiteRoot\sql\reset_stock_flash_500.sql"
Reset-Database -SqlFile $warmupSql
Reset-RedisState
Reset-BenchmarkMetrics
& $jmeterBat "-n" "-t" "$suiteRoot\flash-burst-high-contrast.jmx" "-Jhost=$appHost" "-Jport=$appPort" "-Jprotocol=$appProtocol" "-JvoucherId=$VoucherId" "-Jthreads=10" "-JrampUpSeconds=2" "-JdurationSeconds=5" "-JuserCsv=$UserCsv" "-l" (Join-Path $runRoot "warmup.jtl") |
    Tee-Object (Join-Path $runRoot "warmup.console.txt")

if ($Scenario -eq "all") {
    Invoke-Scenario -Name "flash-burst-5k-100"
    Invoke-Scenario -Name "flash-burst-5k-500"
    Invoke-Scenario -Name "flash-sustain-5k-100"
    Invoke-Scenario -Name "flash-sustain-5k-500"
} else {
    Invoke-Scenario -Name $Scenario
}

$aggregate | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $runRoot "aggregate-summary.csv")
Write-Host "DB-cache flash-sale benchmark results saved to $runRoot"
