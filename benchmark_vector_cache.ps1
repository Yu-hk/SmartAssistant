# Vector Cache Performance Benchmark

$routerUrl = "http://localhost:8083/api/router/test/route"

Write-Host "==============================================" -ForegroundColor Cyan
Write-Host "  5-Tier Cache Performance Benchmark" -ForegroundColor Cyan
Write-Host "=============================================="

function Measure-Latency {
    param([string]$Question)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $response = curl.exe -s -X POST $routerUrl -H "Content-Type: application/json" -d ('{"question":"' + $Question + '","userId":1}')
    $sw.Stop()
    return @{Ms=$sw.ElapsedMilliseconds; Response=$response}
}

# Clear cache
redis-cli -a redis123 EVAL "return redis.call('DEL', unpack(redis.call('KEYS', 'a2a:route:*')))" 0 2>$null
Write-Host "[Setup] Cache cleared"

# Ask a seed question to populate cache
Write-Host "`n[Seed] Asking seed question to populate cache..."
Measure-Latency -Question "上海天气怎么样" | Out-Null
Write-Host "[Seed] Cache populated"

# Tier 1 test: Exact match (same question)
Write-Host "`n=== Tier 1: Exact Match ==="
$t1 = @()
foreach ($i in 1..5) {
    $r = Measure-Latency -Question "上海天气怎么样"
    $t1 += $r.Ms
    Write-Host "  #$i : $($r.Ms)ms"
}

# Tier 2 test: Keyword match (same keywords, different wording)
Write-Host "`n=== Tier 2: Keyword Hash Match ==="
$t2 = @()
foreach ($i in 1..5) {
    $r = Measure-Latency -Question "上海天气好不好"
    $t2 += $r.Ms
    Write-Host "  #$i : $($r.Ms)ms"
}

# Tier 3 test: Vector match (requires BGE vector)
# Note: TF vector uses ChineseTokenizer, which produces same keywords
# "上海天气好不好" -> keywords {"上海","天气"} -> same as Tier 2
# For a true vector test, we need different keywords but similar meaning
Write-Host "`n=== Tier 3: TF Vector Match ==="
$t3 = @()
foreach ($i in 1..5) {
    # This question has same meaning but different keywords that TF vector can catch
    $r = Measure-Latency -Question "上海天气预报"
    $t3 += $r.Ms
    Write-Host "  #$i : $($r.Ms)ms"
}

# Calculate results
$t1avg = [math]::Round(($t1 | Measure-Object -Average).Average, 1)
$t2avg = [math]::Round(($t2 | Measure-Object -Average).Average, 1)  
$t3avg = [math]::Round(($t3 | Measure-Object -Average).Average, 1)

Write-Host "`n==============================================" -ForegroundColor Cyan
Write-Host "  RESULTS" -ForegroundColor Cyan
Write-Host "=============================================="
Write-Host "  Tier 1 (Exact MD5):       ${t1avg}ms avg"
Write-Host "  Tier 2 (Keyword Hash):    ${t2avg}ms avg"
Write-Host "  Tier 3 (TF Vector Match): ${t3avg}ms avg"
Write-Host "=============================================="
