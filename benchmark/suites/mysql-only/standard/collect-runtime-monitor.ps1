param(
    [Parameter(Mandatory = $true)]
    [int]$AppPid,

    [Parameter(Mandatory = $true)]
    [string]$ScenarioDir,

    [Parameter(Mandatory = $true)]
    [string]$StopFile,

    [Parameter(Mandatory = $false)]
    [int]$IntervalSeconds = 2,

    [Parameter(Mandatory = $false)]
    [string]$JstatPath = "",

    [Parameter(Mandatory = $false)]
    [string]$MySqlHost = "127.0.0.1",

    [Parameter(Mandatory = $false)]
    [int]$MySqlPort = 3306,

    [Parameter(Mandatory = $false)]
    [string]$MySqlUser = "root",

    [Parameter(Mandatory = $false)]
    [string]$MySqlPassword = "heyunhui2856"
)

$ErrorActionPreference = "Stop"
$env:MYSQL_PWD = $MySqlPassword

$runtimeCsv = Join-Path $ScenarioDir "runtime-monitor.csv"
$gcCsv = Join-Path $ScenarioDir "gc-monitor.csv"
$processorCount = [Environment]::ProcessorCount

"sample_time,app_cpu_pct,app_private_mb,app_working_set_mb,mysql_cpu_pct,mysql_private_mb,mysql_working_set_mb,system_cpu_pct,system_available_mb,threads_connected,threads_running,innodb_row_lock_current_waits,innodb_row_lock_time,innodb_row_lock_waits,data_lock_waits,metadata_lock_waits" | Set-Content -Encoding UTF8 $runtimeCsv
"sample_time,s0,s1,e,o,m,ccs,ygc,ygct,fgc,fgct,cgc,cgct,gct" | Set-Content -Encoding UTF8 $gcCsv

function Get-MySqlProcessSample {
    $mysqlPerf = Get-CimInstance Win32_PerfFormattedData_PerfProc_Process |
        Where-Object { $_.Name -like "mysqld*" } |
        Sort-Object PrivateBytes -Descending |
        Select-Object -First 1

    if (-not $mysqlPerf) {
        return [pscustomobject]@{
            CpuPct = 0
            PrivateMb = 0
            WorkingSetMb = 0
        }
    }

    return [pscustomobject]@{
        CpuPct = [math]::Round([double]$mysqlPerf.PercentProcessorTime / [math]::Max($processorCount, 1), 2)
        PrivateMb = [math]::Round([double]$mysqlPerf.PrivateBytes / 1MB, 2)
        WorkingSetMb = [math]::Round([double]$mysqlPerf.WorkingSetPrivate / 1MB, 2)
    }
}

function Get-AppProcessSample {
    $appPerf = Get-CimInstance Win32_PerfFormattedData_PerfProc_Process |
        Where-Object { $_.IDProcess -eq $AppPid } |
        Select-Object -First 1

    if (-not $appPerf) {
        return $null
    }

    return [pscustomobject]@{
        CpuPct = [math]::Round([double]$appPerf.PercentProcessorTime / [math]::Max($processorCount, 1), 2)
        PrivateMb = [math]::Round([double]$appPerf.PrivateBytes / 1MB, 2)
        WorkingSetMb = [math]::Round([double]$appPerf.WorkingSetPrivate / 1MB, 2)
    }
}

function Get-SystemSample {
    $samples = Get-Counter '\Processor(_Total)\% Processor Time','\Memory\Available MBytes'
    $cpuSample = $samples.CounterSamples | Where-Object { $_.Path -like '*\processor(_total)\% processor time' }
    $memorySample = $samples.CounterSamples | Where-Object { $_.Path -like '*\memory\available mbytes' }

    return [pscustomobject]@{
        CpuPct = [math]::Round([double]$cpuSample.CookedValue, 2)
        AvailableMb = [math]::Round([double]$memorySample.CookedValue, 2)
    }
}

function Get-MySqlStatusMap {
    $sql = @"
SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Innodb_row_lock_current_waits','Innodb_row_lock_time','Innodb_row_lock_waits');
SELECT 'data_lock_waits', COUNT(*) FROM performance_schema.data_lock_waits;
SELECT 'metadata_lock_waits', COUNT(*) FROM performance_schema.metadata_locks WHERE LOCK_STATUS='PENDING';
"@

    $rows = mysql -h $MySqlHost -P $MySqlPort -u $MySqlUser --batch --raw --skip-column-names -e $sql 2>$null
    $map = @{}
    foreach ($row in $rows) {
        if (-not $row) {
            continue
        }
        $parts = $row -split "`t"
        if ($parts.Length -lt 2) {
            continue
        }
        $map[$parts[0]] = $parts[1]
    }
    return $map
}

function Write-GcSample {
    param([string]$SampleTime)

    if (-not $JstatPath -or -not (Test-Path $JstatPath)) {
        return
    }

    try {
        $gcOutput = & $JstatPath -gcutil $AppPid 1000 1 2>$null
        if (-not $gcOutput -or $gcOutput.Count -lt 2) {
            return
        }

        $values = ($gcOutput[1] -split '\s+') | Where-Object { $_ -ne "" }
        if ($values.Count -lt 13) {
            return
        }

        "$SampleTime,$($values -join ',')" | Add-Content -Encoding UTF8 $gcCsv
    } catch {
    }
}

while (-not (Test-Path $StopFile)) {
    $sampleTime = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $appSample = Get-AppProcessSample
    if (-not $appSample) {
        break
    }

    $mysqlSample = Get-MySqlProcessSample
    $systemSample = Get-SystemSample
    $statusMap = Get-MySqlStatusMap

    $runtimeRow = @(
        $sampleTime
        $appSample.CpuPct
        $appSample.PrivateMb
        $appSample.WorkingSetMb
        $mysqlSample.CpuPct
        $mysqlSample.PrivateMb
        $mysqlSample.WorkingSetMb
        $systemSample.CpuPct
        $systemSample.AvailableMb
        $statusMap["Threads_connected"]
        $statusMap["Threads_running"]
        $statusMap["Innodb_row_lock_current_waits"]
        $statusMap["Innodb_row_lock_time"]
        $statusMap["Innodb_row_lock_waits"]
        $statusMap["data_lock_waits"]
        $statusMap["metadata_lock_waits"]
    ) -join ","

    $runtimeRow | Add-Content -Encoding UTF8 $runtimeCsv
    Write-GcSample -SampleTime $sampleTime

    Start-Sleep -Seconds $IntervalSeconds
}
