# Smart Assistant - Monitoring Status Check Script
# Usage: Check monitoring status for all services

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Smart Assistant Monitoring Status" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Check Prometheus health
Write-Host "[1/3] Checking Prometheus..." -ForegroundColor Yellow
try {
    $health = Invoke-RestMethod -Uri "http://localhost:9090/-/healthy" -TimeoutSec 3 -ErrorAction Stop
    Write-Host "[OK] Prometheus is running" -ForegroundColor Green
} catch {
    Write-Host "[ERROR] Prometheus is not running" -ForegroundColor Red
    Write-Host "   Start monitoring: cd monitoring && docker-compose up -d" -ForegroundColor Yellow
    exit 1
}

# Get all targets
Write-Host "`n[2/3] Fetching monitoring targets..." -ForegroundColor Yellow
try {
    $targets = Invoke-RestMethod -Uri "http://localhost:9090/api/v1/targets" -TimeoutSec 5 -ErrorAction Stop
} catch {
    Write-Host "[ERROR] Failed to get targets: $_" -ForegroundColor Red
    exit 1
}

# Categorize targets
$upTargets = $targets.data.activeTargets | Where-Object { $_.health -eq 'up' }
$downTargets = $targets.data.activeTargets | Where-Object { $_.health -ne 'up' }

Write-Host "`n[3/3] Target Status Details:" -ForegroundColor Yellow
Write-Host "----------------------------------------" -ForegroundColor Gray

# Show UP services
if ($upTargets.Count -gt 0) {
    Write-Host "`n[OK] Active Services ($($upTargets.Count)):" -ForegroundColor Green
    $upTargets | ForEach-Object {
        $jobName = $_.labels.job
        $instance = if ($_.labels.instance) { " [$($_.labels.instance)]" } else { "" }
        Write-Host "   * $jobName$instance" -ForegroundColor Green
    }
}

# Show DOWN services
if ($downTargets.Count -gt 0) {
    Write-Host "`n[WARN] Inactive Services ($($downTargets.Count)):" -ForegroundColor Red
    $downTargets | ForEach-Object {
        $jobName = $_.labels.job
        $instance = if ($_.labels.instance) { " [$($_.labels.instance)]" } else { "" }
        Write-Host "   * $jobName$instance" -ForegroundColor Red
    }
}

# Summary
Write-Host "`n----------------------------------------" -ForegroundColor Gray
Write-Host "Summary:" -ForegroundColor Cyan
Write-Host "  Total Targets: $($targets.data.activeTargets.Count)" -ForegroundColor White
Write-Host "  Active:        $($upTargets.Count)" -ForegroundColor Green
Write-Host "  Inactive:      $($downTargets.Count)" -ForegroundColor Red

# Coverage calculation
$totalServices = $targets.data.activeTargets.Count
if ($totalServices -gt 0) {
    $coverage = [math]::Round(($upTargets.Count / $totalServices) * 100, 1)
    $color = if ($coverage -ge 80) { 'Green' } elseif ($coverage -ge 50) { 'Yellow' } else { 'Red' }
    Write-Host "  Coverage:      ${coverage}%" -ForegroundColor $color
}

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "Quick Links:" -ForegroundColor Yellow
Write-Host "  * Grafana Dashboard: http://localhost:3000" -ForegroundColor White
Write-Host "  * Prometheus Query:  http://localhost:9090" -ForegroundColor White
Write-Host "  * Start Services:    Run JAR files for each service" -ForegroundColor White
Write-Host "========================================" -ForegroundColor Cyan
