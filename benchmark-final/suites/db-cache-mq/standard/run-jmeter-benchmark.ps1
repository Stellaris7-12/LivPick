param(
    [Parameter(Mandatory = $false)]
    [ValidateSet("baseline-50", "baseline-100", "baseline-200", "baseline-500", "oversell-100", "one-user-one-order-100", "all")]
    [string]$Scenario = "all"
)

Write-Host "db-cache-mq standard benchmark runner placeholder"
Write-Host "Use benchmark endpoints:"
Write-Host "  GET  /benchmark/metrics"
Write-Host "  POST /benchmark/admin/metrics/reset"
Write-Host "  POST /benchmark/admin/seckill/reset"
Write-Host "  GET  /benchmark/admin/mq/drain-status"
Write-Host "Next step: port the db-cache runner and switch metric extraction to mq-specific seckill metrics."
