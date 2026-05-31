param(
    [Parameter(Mandatory = $false)]
    [ValidateSet("flash-burst-5k-100", "flash-burst-5k-500", "flash-sustain-5k-100", "flash-sustain-5k-500", "all")]
    [string]$Scenario = "all"
)

Write-Host "db-cache-mq flash-sale benchmark runner placeholder"
Write-Host "Required app flags:"
Write-Host "  LIVPICK_BENCHMARK_ENABLED=true"
Write-Host "Recommended target metrics:"
Write-Host "  apiAccepted, kafkaSendSuccess/Failure, pending backlog, consumerCreated/Reactivated, timeoutClosed, paySuccess"
