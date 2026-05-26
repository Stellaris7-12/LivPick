param(
    [Parameter(Mandatory = $false)]
    [ValidateSet("baseline-50", "baseline-100", "baseline-200", "baseline-500", "oversell-100", "one-user-one-order-100", "all")]
    [string]$Scenario = "all",

    [Parameter(Mandatory = $false)]
    [string]$JMeterHome = "C:\Users\heyunhui\Documents\apache-jmeter-5.6.3"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
Set-Location $root

$jmeterBat = Join-Path $JMeterHome "bin\jmeter.bat"
if (-not (Test-Path $jmeterBat)) {
    throw "JMeter not found at $jmeterBat"
}

if (-not (Get-NetTCPConnection -LocalPort 8081 -ErrorAction SilentlyContinue | Select-Object -First 1)) {
    throw "Application is not listening on port 8081"
}

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

function Reset-Database {
    param([string]$SqlFile)
    $sql = Get-Content $SqlFile -Raw
    mysql -h 127.0.0.1 -P 3306 -u root -pheyunhui2856 -D livpick_mysql_only -e $sql | Out-Null
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

    & $jmeterBat '-n' '-t' $JmxFile "-Jthreads=$Threads" '-JrampUpSeconds=5' '-JdurationSeconds=60' "-JuserCsv=$CsvFile" '-l' $jtl '-e' '-o' $dashboardDir |
        Tee-Object $console

    $summary = Parse-JtlSummary -JtlPath $jtl -ScenarioName $Name

    $postCheck = Get-ScenarioPostCheck -Name $Name
    $dbOutput = mysql -h 127.0.0.1 -P 3306 -u root -pheyunhui2856 -D livpick_mysql_only -e $postCheck.Sql
    $dbOutput | Set-Content -Encoding UTF8 $dbCheck

    $numericLines = $dbOutput | Select-String '^([0-9]+)$'
    if ($postCheck.Type -eq "stock") {
        $summary | Add-Member -NotePropertyName FinalStock -NotePropertyValue (($numericLines | Select-Object -First 1).Matches.Value)
        $summary | Add-Member -NotePropertyName FinalOrders -NotePropertyValue (($numericLines | Select-Object -Skip 1 -First 1).Matches.Value)
        $summary | Add-Member -NotePropertyName DuplicateUsers -NotePropertyValue ""
    } else {
        $summary | Add-Member -NotePropertyName FinalStock -NotePropertyValue ""
        $summary | Add-Member -NotePropertyName FinalOrders -NotePropertyValue (($numericLines | Select-Object -First 1).Matches.Value)
        $summary | Add-Member -NotePropertyName DuplicateUsers -NotePropertyValue (($numericLines | Select-Object -Skip 1 -First 1).Matches.Value)
    }

    $aggregate.Add($summary) | Out-Null
    $summary | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $scenarioDir "$Name.summary.json")
}

Reset-Database -SqlFile "benchmark\jmeter\mysql-only\sql\reset_stock_large.sql"
& $jmeterBat '-n' '-t' 'benchmark\jmeter\mysql-only\baseline-throughput.jmx' '-Jthreads=10' '-JrampUpSeconds=2' '-JdurationSeconds=10' '-JuserCsv=benchmark/jmeter/mysql-only/data/user_ids_unique.csv' '-l' (Join-Path $runRoot 'warmup.jtl') |
    Tee-Object (Join-Path $runRoot 'warmup.console.txt')

if ($Scenario -eq "all") {
    Invoke-Scenario -Name "baseline-50" -JmxFile "benchmark\jmeter\mysql-only\baseline-throughput.jmx" -Threads 50 -CsvFile "benchmark/jmeter/mysql-only/data/user_ids_unique.csv" -ResetSql "benchmark\jmeter\mysql-only\sql\reset_stock_large.sql"
    Invoke-Scenario -Name "baseline-100" -JmxFile "benchmark\jmeter\mysql-only\baseline-throughput.jmx" -Threads 100 -CsvFile "benchmark/jmeter/mysql-only/data/user_ids_unique.csv" -ResetSql "benchmark\jmeter\mysql-only\sql\reset_stock_large.sql"
    Invoke-Scenario -Name "baseline-200" -JmxFile "benchmark\jmeter\mysql-only\baseline-throughput.jmx" -Threads 200 -CsvFile "benchmark/jmeter/mysql-only/data/user_ids_unique.csv" -ResetSql "benchmark\jmeter\mysql-only\sql\reset_stock_large.sql"
    Invoke-Scenario -Name "baseline-500" -JmxFile "benchmark\jmeter\mysql-only\baseline-throughput.jmx" -Threads 500 -CsvFile "benchmark/jmeter/mysql-only/data/user_ids_unique.csv" -ResetSql "benchmark\jmeter\mysql-only\sql\reset_stock_large.sql"
    Invoke-Scenario -Name "oversell-100" -JmxFile "benchmark\jmeter\mysql-only\oversell-check.jmx" -Threads 100 -CsvFile "benchmark/jmeter/mysql-only/data/user_ids_unique.csv" -ResetSql "benchmark\jmeter\mysql-only\sql\reset_stock_small.sql"
    Invoke-Scenario -Name "one-user-one-order-100" -JmxFile "benchmark\jmeter\mysql-only\one-user-one-order.jmx" -Threads 100 -CsvFile "benchmark/jmeter/mysql-only/data/user_ids_repeat.csv" -ResetSql "benchmark\jmeter\mysql-only\sql\reset_stock_large.sql"
} else {
    switch ($Scenario) {
        "baseline-50" { Invoke-Scenario -Name "baseline-50" -JmxFile "benchmark\jmeter\mysql-only\baseline-throughput.jmx" -Threads 50 -CsvFile "benchmark/jmeter/mysql-only/data/user_ids_unique.csv" -ResetSql "benchmark\jmeter\mysql-only\sql\reset_stock_large.sql" }
        "baseline-100" { Invoke-Scenario -Name "baseline-100" -JmxFile "benchmark\jmeter\mysql-only\baseline-throughput.jmx" -Threads 100 -CsvFile "benchmark/jmeter/mysql-only/data/user_ids_unique.csv" -ResetSql "benchmark\jmeter\mysql-only\sql\reset_stock_large.sql" }
        "baseline-200" { Invoke-Scenario -Name "baseline-200" -JmxFile "benchmark\jmeter\mysql-only\baseline-throughput.jmx" -Threads 200 -CsvFile "benchmark/jmeter/mysql-only/data/user_ids_unique.csv" -ResetSql "benchmark\jmeter\mysql-only\sql\reset_stock_large.sql" }
        "baseline-500" { Invoke-Scenario -Name "baseline-500" -JmxFile "benchmark\jmeter\mysql-only\baseline-throughput.jmx" -Threads 500 -CsvFile "benchmark/jmeter/mysql-only/data/user_ids_unique.csv" -ResetSql "benchmark\jmeter\mysql-only\sql\reset_stock_large.sql" }
        "oversell-100" { Invoke-Scenario -Name "oversell-100" -JmxFile "benchmark\jmeter\mysql-only\oversell-check.jmx" -Threads 100 -CsvFile "benchmark/jmeter/mysql-only/data/user_ids_unique.csv" -ResetSql "benchmark\jmeter\mysql-only\sql\reset_stock_small.sql" }
        "one-user-one-order-100" { Invoke-Scenario -Name "one-user-one-order-100" -JmxFile "benchmark\jmeter\mysql-only\one-user-one-order.jmx" -Threads 100 -CsvFile "benchmark/jmeter/mysql-only/data/user_ids_repeat.csv" -ResetSql "benchmark\jmeter\mysql-only\sql\reset_stock_large.sql" }
    }
}

$aggregate | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $runRoot "aggregate-summary.csv")
Write-Host "JMeter benchmark results saved to $runRoot"
