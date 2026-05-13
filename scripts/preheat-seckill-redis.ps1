param(
    [switch]$OnlyActive,
    [string]$VoucherIds
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$mainClass = "com.livepick.tools.SeckillRedisPreheatMain"
$execGoal = "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"

$execArgs = @()
if ($OnlyActive) {
    $execArgs += "--only-active"
}
if ($VoucherIds) {
    $execArgs += "--voucher-ids=$VoucherIds"
}

$mavenArgs = @(
    "-q"
    "-DskipTests"
    "-Dexec.mainClass=$mainClass"
)

if ($execArgs.Count -gt 0) {
    $mavenArgs += "-Dexec.args=$($execArgs -join ' ')"
}

$mavenArgs += $execGoal

Write-Host "Running seckill Redis preheat..." -ForegroundColor Cyan
Write-Host "Project root: $projectRoot"
if ($execArgs.Count -gt 0) {
    Write-Host "Arguments: $($execArgs -join ' ')"
}

Push-Location $projectRoot
try {
    & mvn @mavenArgs
} finally {
    Pop-Location
}
