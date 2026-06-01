param(
    [Parameter(Mandatory = $false)]
    [ValidateSet("baseline-50", "baseline-100", "baseline-200", "baseline-500", "oversell-100", "one-user-one-order-100", "all")]
    [string]$Scenario = "all",

    [Parameter(Mandatory = $false)]
    [string]$JMeterHome = "C:\Users\heyunhui\Documents\apache-jmeter-5.6.3",

    [Parameter(Mandatory = $false)]
    [switch]$EnableMonitoring,

    [Parameter(Mandatory = $false)]
    [int]$SamplingIntervalSeconds = 2
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
Set-Location $root

$jmeterBat = Join-Path $JMeterHome "bin\jmeter.bat"
if (-not (Test-Path $jmeterBat)) {
    throw "JMeter not found at $jmeterBat"
}

$appConnection = Get-NetTCPConnection -LocalPort 8081 -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $appConnection) {
    throw "Application is not listening on port 8081"
}

$appPid = $appConnection.OwningProcess
$monitorScript = Join-Path $root "benchmark-final\suites\mysql-only\standard\collect-runtime-monitor.ps1"
$powershellExe = Join-Path $env:SystemRoot "System32\WindowsPowerShell\v1.0\powershell.exe"
$jstatCommand = Get-Command jstat.exe -ErrorAction SilentlyContinue | Select-Object -First 1
$jstatPath = if ($jstatCommand) { $jstatCommand.Source } else { "" }
$env:MYSQL_PWD = "heyunhui2856"

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runRoot = Join-Path "target\benchmark\jmeter" "run-$timestamp"
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null

$aggregate = New-Object System.Collections.Generic.List[object]

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

function Convert-ToDouble {
    param([object]$Value)
    if ($null -eq $Value -or "$Value" -eq "") {
        return 0.0
    }
    return [double]::Parse("$Value", [System.Globalization.CultureInfo]::InvariantCulture)
}

function Reset-Database {
    param([string]$SqlFile)
    $sql = Get-Content $SqlFile -Raw
    mysql -h 127.0.0.1 -P 3306 -u root -D livpick_mysql_only -e $sql 2>$null | Out-Null
}

function Get-ScenarioPostCheck {
    param([string]$Name)
    switch ($Name) {
        "one-user-one-order-100" {
            return @{
                Sql = "SELECT COUNT(*) AS order_count FROM tb_voucher_order WHERE voucher_id = 7; SELECT COUNT(*) AS duplicate_user_rows FROM (SELECT user_id FROM tb_voucher_order WHERE voucher_id = 7 GROUP BY user_id HAVING COUNT(*) > 1) t;"
                Type = "duplicate"
            }
        }
        default {
            return @{
                Sql = "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 7; SELECT COUNT(*) AS order_count FROM tb_voucher_order WHERE voucher_id = 7;"
                Type = "stock"
            }
        }
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
        [string[]]$DbOutput,
        [string]$Type
    )

    $numericLines = $DbOutput | Select-String '^([0-9]+)$'
    if ($Type -eq "stock") {
        $Summary | Add-Member -NotePropertyName FinalStock -NotePropertyValue (($numericLines | Select-Object -First 1).Matches.Value)
        $Summary | Add-Member -NotePropertyName FinalOrders -NotePropertyValue (($numericLines | Select-Object -Skip 1 -First 1).Matches.Value)
        $Summary | Add-Member -NotePropertyName DuplicateUsers -NotePropertyValue ""
    } else {
        $Summary | Add-Member -NotePropertyName FinalStock -NotePropertyValue ""
        $Summary | Add-Member -NotePropertyName FinalOrders -NotePropertyValue (($numericLines | Select-Object -First 1).Matches.Value)
        $Summary | Add-Member -NotePropertyName DuplicateUsers -NotePropertyValue (($numericLines | Select-Object -Skip 1 -First 1).Matches.Value)
    }
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
WHERE OBJECT_SCHEMA = 'livpick_mysql_only'
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
    $args = "-NoProfile -ExecutionPolicy Bypass -File $quotedMonitorScript -AppPid $appPid -ScenarioDir $quotedScenarioDir -StopFile $quotedStopFile -IntervalSeconds $SamplingIntervalSeconds"

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

function Invoke-Scenario {
    param(
        [string]$Name,
        [string]$JmxFile,
        [int]$Threads,
        [string]$CsvFile,
        [string]$ResetSql
    )

    Reset-Database -SqlFile $ResetSql

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

    try {
        if ($EnableMonitoring) {
            $statusStart = Get-MySqlStatusSnapshot
            $monitor = Start-MonitorProcess -ScenarioDir $scenarioDir
        }

        & $jmeterBat '-n' '-t' $JmxFile "-Jthreads=$Threads" '-JrampUpSeconds=5' '-JdurationSeconds=60' "-JuserCsv=$CsvFile" '-l' $jtl '-e' '-o' $dashboardDir |
            Tee-Object $console
    } finally {
        $scenarioEnd = Get-Date
        if ($EnableMonitoring) {
            Stop-MonitorProcess -Monitor $monitor
            $statusEnd = Get-MySqlStatusSnapshot
            Export-MySqlDiagnostics -ScenarioDir $scenarioDir -ScenarioStart $scenarioStart -ScenarioEnd $scenarioEnd
        }
    }

    $summary = Parse-JtlSummary -JtlPath $jtl -ScenarioName $Name

    $postCheck = Get-ScenarioPostCheck -Name $Name
    $dbOutput = mysql -h 127.0.0.1 -P 3306 -u root -D livpick_mysql_only -e $postCheck.Sql 2>$null
    $dbOutput | Set-Content -Encoding UTF8 $dbCheck
    Add-PostCheckFields -Summary $summary -DbOutput $dbOutput -Type $postCheck.Type

    if ($EnableMonitoring) {
        Add-MonitoringSummary -Summary $summary -ScenarioDir $scenarioDir -StartStatus $statusStart -EndStatus $statusEnd
    }

    $aggregate.Add($summary) | Out-Null
    $summary | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $scenarioDir "$Name.summary.json")
}

Reset-Database -SqlFile "benchmark-final\suites\mysql-only\standard\sql\reset_stock_large.sql"
& $jmeterBat '-n' '-t' 'benchmark-final\suites\mysql-only\standard\baseline-throughput.jmx' '-Jthreads=10' '-JrampUpSeconds=2' '-JdurationSeconds=10' '-JuserCsv=benchmark-final/suites/mysql-only/standard/data/user_ids_unique.csv' '-l' (Join-Path $runRoot 'warmup.jtl') |
    Tee-Object (Join-Path $runRoot 'warmup.console.txt')

if ($Scenario -eq "all") {
    Invoke-Scenario -Name "baseline-50" -JmxFile "benchmark-final\suites\mysql-only\standard\baseline-throughput.jmx" -Threads 50 -CsvFile "benchmark-final/suites/mysql-only/standard/data/user_ids_unique.csv" -ResetSql "benchmark-final\suites\mysql-only\standard\sql\reset_stock_large.sql"
    Invoke-Scenario -Name "baseline-100" -JmxFile "benchmark-final\suites\mysql-only\standard\baseline-throughput.jmx" -Threads 100 -CsvFile "benchmark-final/suites/mysql-only/standard/data/user_ids_unique.csv" -ResetSql "benchmark-final\suites\mysql-only\standard\sql\reset_stock_large.sql"
    Invoke-Scenario -Name "baseline-200" -JmxFile "benchmark-final\suites\mysql-only\standard\baseline-throughput.jmx" -Threads 200 -CsvFile "benchmark-final/suites/mysql-only/standard/data/user_ids_unique.csv" -ResetSql "benchmark-final\suites\mysql-only\standard\sql\reset_stock_large.sql"
    Invoke-Scenario -Name "baseline-500" -JmxFile "benchmark-final\suites\mysql-only\standard\baseline-throughput.jmx" -Threads 500 -CsvFile "benchmark-final/suites/mysql-only/standard/data/user_ids_unique.csv" -ResetSql "benchmark-final\suites\mysql-only\standard\sql\reset_stock_large.sql"
    Invoke-Scenario -Name "oversell-100" -JmxFile "benchmark-final\suites\mysql-only\standard\oversell-check.jmx" -Threads 100 -CsvFile "benchmark-final/suites/mysql-only/standard/data/user_ids_unique.csv" -ResetSql "benchmark-final\suites\mysql-only\standard\sql\reset_stock_small.sql"
    Invoke-Scenario -Name "one-user-one-order-100" -JmxFile "benchmark-final\suites\mysql-only\standard\one-user-one-order.jmx" -Threads 100 -CsvFile "benchmark-final/suites/mysql-only/standard/data/user_ids_repeat.csv" -ResetSql "benchmark-final\suites\mysql-only\standard\sql\reset_stock_large.sql"
} else {
    switch ($Scenario) {
        "baseline-50" { Invoke-Scenario -Name "baseline-50" -JmxFile "benchmark-final\suites\mysql-only\standard\baseline-throughput.jmx" -Threads 50 -CsvFile "benchmark-final/suites/mysql-only/standard/data/user_ids_unique.csv" -ResetSql "benchmark-final\suites\mysql-only\standard\sql\reset_stock_large.sql" }
        "baseline-100" { Invoke-Scenario -Name "baseline-100" -JmxFile "benchmark-final\suites\mysql-only\standard\baseline-throughput.jmx" -Threads 100 -CsvFile "benchmark-final/suites/mysql-only/standard/data/user_ids_unique.csv" -ResetSql "benchmark-final\suites\mysql-only\standard\sql\reset_stock_large.sql" }
        "baseline-200" { Invoke-Scenario -Name "baseline-200" -JmxFile "benchmark-final\suites\mysql-only\standard\baseline-throughput.jmx" -Threads 200 -CsvFile "benchmark-final/suites/mysql-only/standard/data/user_ids_unique.csv" -ResetSql "benchmark-final\suites\mysql-only\standard\sql\reset_stock_large.sql" }
        "baseline-500" { Invoke-Scenario -Name "baseline-500" -JmxFile "benchmark-final\suites\mysql-only\standard\baseline-throughput.jmx" -Threads 500 -CsvFile "benchmark-final/suites/mysql-only/standard/data/user_ids_unique.csv" -ResetSql "benchmark-final\suites\mysql-only\standard\sql\reset_stock_large.sql" }
        "oversell-100" { Invoke-Scenario -Name "oversell-100" -JmxFile "benchmark-final\suites\mysql-only\standard\oversell-check.jmx" -Threads 100 -CsvFile "benchmark-final/suites/mysql-only/standard/data/user_ids_unique.csv" -ResetSql "benchmark-final\suites\mysql-only\standard\sql\reset_stock_small.sql" }
        "one-user-one-order-100" { Invoke-Scenario -Name "one-user-one-order-100" -JmxFile "benchmark-final\suites\mysql-only\standard\one-user-one-order.jmx" -Threads 100 -CsvFile "benchmark-final/suites/mysql-only/standard/data/user_ids_repeat.csv" -ResetSql "benchmark-final\suites\mysql-only\standard\sql\reset_stock_large.sql" }
    }
}

$aggregate | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $runRoot "aggregate-summary.csv")
Write-Host "JMeter benchmark results saved to $runRoot"
