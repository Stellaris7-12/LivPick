param(
    [string]$RedisCli = "redis-cli",
    [string]$Host = "127.0.0.1",
    [int]$Port = 6379,
    [string]$Pattern = "login:token:*",
    [int]$Limit = 200,
    [string]$Output = "load-test-tokens.csv"
)

$redisCliCommand = Get-Command $RedisCli -ErrorAction SilentlyContinue
if (-not $redisCliCommand) {
    throw "未找到 redis-cli，请先确认 redis-cli 已安装并在 PATH 中，或通过 -RedisCli 指定绝对路径。"
}

$scanArgs = @("-h", $Host, "-p", $Port, "--scan", "--pattern", $Pattern)
$rawKeys = & $RedisCli @scanArgs

if ($LASTEXITCODE -ne 0) {
    throw "redis-cli 扫描失败，请检查 Redis 地址、端口和权限配置。"
}

$keys = @($rawKeys | Where-Object { $_ -and $_.Trim().Length -gt 0 })
if ($Limit -gt 0) {
    $keys = $keys | Select-Object -First $Limit
}

$tokens = $keys | ForEach-Object {
    if ($_ -like "login:token:*") {
        $_.Substring("login:token:".Length)
    }
}

if (-not $tokens -or $tokens.Count -eq 0) {
    throw "未扫描到任何 token，请确认 Redis 中存在 login:token:* 键。"
}

$outputPath = [System.IO.Path]::GetFullPath($Output)
$outputDir = Split-Path -Parent $outputPath
if ($outputDir) {
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
}

@("token") + $tokens | Set-Content -Path $outputPath -Encoding UTF8

Write-Host ("token 导出完成，数量={0}，csv={1}" -f $tokens.Count, $outputPath)
