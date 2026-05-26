param(
    [Parameter(Mandatory = $false)]
    [ValidateSet("baseline-50", "baseline-100", "baseline-200", "baseline-500", "oversell-100", "one-user-one-order-100", "all")]
    [string]$Scenario = "all"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
Set-Location $root

$javaHome = "C:\Program Files\Java\jdk-11"
if (-not (Test-Path $javaHome)) {
    throw "JDK 11 not found at $javaHome"
}

$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"

$classpathFile = "target\test.classpath"
if (-not (Test-Path $classpathFile)) {
    Write-Host "target\test.classpath not found, building it..."
    mvn "-Dmaven.repo.local=C:\Users\heyunhui\.m2\repository" "-DincludeScope=test" "-Dmdep.outputFile=target\test.classpath" dependency:build-classpath
}

if (-not (Test-Path "target\test-classes")) {
    Write-Host "target\test-classes not found, compiling tests..."
    mvn "-Dmaven.repo.local=C:\Users\heyunhui\.m2\repository" test-compile
}

$cp = (Get-Content $classpathFile -Raw).Trim()
New-Item -ItemType Directory -Force -Path "target\benchmark\java-runner" | Out-Null

$scenarios = @{
    "baseline-50" = @{
        sql = "benchmark\jmeter\mysql-only\sql\reset_stock_large.sql"
        totalRequests = 2000
        concurrency = 50
        uniqueUsers = 2000
        userIdStart = 100000
        postSql = "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 7; SELECT COUNT(*) AS order_count FROM tb_voucher_order WHERE voucher_id = 7;"
    }
    "baseline-100" = @{
        sql = "benchmark\jmeter\mysql-only\sql\reset_stock_large.sql"
        totalRequests = 2000
        concurrency = 100
        uniqueUsers = 2000
        userIdStart = 110000
        postSql = "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 7; SELECT COUNT(*) AS order_count FROM tb_voucher_order WHERE voucher_id = 7;"
    }
    "baseline-200" = @{
        sql = "benchmark\jmeter\mysql-only\sql\reset_stock_large.sql"
        totalRequests = 2000
        concurrency = 200
        uniqueUsers = 2000
        userIdStart = 120000
        postSql = "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 7; SELECT COUNT(*) AS order_count FROM tb_voucher_order WHERE voucher_id = 7;"
    }
    "baseline-500" = @{
        sql = "benchmark\jmeter\mysql-only\sql\reset_stock_large.sql"
        totalRequests = 2000
        concurrency = 500
        uniqueUsers = 2000
        userIdStart = 130000
        postSql = "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 7; SELECT COUNT(*) AS order_count FROM tb_voucher_order WHERE voucher_id = 7;"
    }
    "oversell-100" = @{
        sql = "benchmark\jmeter\mysql-only\sql\reset_stock_small.sql"
        totalRequests = 5000
        concurrency = 100
        uniqueUsers = 5000
        userIdStart = 200000
        postSql = "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 7; SELECT COUNT(*) AS order_count FROM tb_voucher_order WHERE voucher_id = 7;"
    }
    "one-user-one-order-100" = @{
        sql = "benchmark\jmeter\mysql-only\sql\reset_stock_large.sql"
        totalRequests = 5000
        concurrency = 100
        uniqueUsers = 1000
        userIdStart = 300000
        postSql = "SELECT COUNT(*) AS order_count FROM tb_voucher_order WHERE voucher_id = 7; SELECT COUNT(*) AS duplicate_user_rows FROM (SELECT user_id FROM tb_voucher_order WHERE voucher_id = 7 GROUP BY user_id HAVING COUNT(*) > 1) t;"
    }
}

function Invoke-Scenario([string]$Name) {
    $config = $scenarios[$Name]
    if (-not $config) {
        throw "Unknown scenario: $Name"
    }

    $logFile = "target\benchmark\java-runner\$Name.txt"
    $sql = Get-Content $config.sql -Raw

    mysql -h 127.0.0.1 -P 3306 -u root -pheyunhui2856 -D livpick_mysql_only -e $sql

    & java -cp "target\test-classes;target\classes;$cp" `
        "-Dbenchmark.scenario=$Name" `
        "-Dbenchmark.baseUrl=http://127.0.0.1:8081" `
        "-Dbenchmark.voucherId=7" `
        "-Dbenchmark.totalRequests=$($config.totalRequests)" `
        "-Dbenchmark.concurrency=$($config.concurrency)" `
        "-Dbenchmark.uniqueUsers=$($config.uniqueUsers)" `
        "-Dbenchmark.warmupRequests=0" `
        "-Dbenchmark.userIdStart=$($config.userIdStart)" `
        com.livepick.SeckillHttpBenchmarkRunner | Tee-Object $logFile

    mysql -h 127.0.0.1 -P 3306 -u root -pheyunhui2856 -D livpick_mysql_only -e $config.postSql | Tee-Object -Append $logFile
}

if ($Scenario -eq "all") {
    "baseline-50", "baseline-100", "baseline-200", "baseline-500", "oversell-100", "one-user-one-order-100" | ForEach-Object {
        Invoke-Scenario $_
    }
} else {
    Invoke-Scenario $Scenario
}
