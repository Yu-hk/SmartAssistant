# Smart Assistant - Frontend Setup and Launch Script
# Usage: Setup and start the frontend development server

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Smart Assistant Frontend Setup" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Check if node_modules exists
if (-Not (Test-Path "node_modules")) {
    Write-Host "[1/3] Installing dependencies..." -ForegroundColor Yellow
    npm install
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[ERROR] Failed to install dependencies" -ForegroundColor Red
        exit 1
    }
    Write-Host "[OK] Dependencies installed" -ForegroundColor Green
} else {
    Write-Host "[1/3] Dependencies already installed" -ForegroundColor Green
}

# Check backend services
Write-Host "`n[2/3] Checking backend services..." -ForegroundColor Yellow
$gatewayRunning = $false
try {
    $response = Invoke-WebRequest -Uri "http://localhost:8081/actuator/health" -TimeoutSec 3 -ErrorAction Stop
    if ($response.StatusCode -eq 200) {
        $gatewayRunning = $true
    }
} catch {
    $gatewayRunning = $false
}

if ($gatewayRunning) {
    Write-Host "[OK] API Gateway is running on port 8080" -ForegroundColor Green
} else {
    Write-Host "[WARN] API Gateway is NOT running" -ForegroundColor Yellow
    Write-Host "   Please start backend services first:" -ForegroundColor Yellow
    Write-Host "   1. User Service (port 8086)" -ForegroundColor White
    Write-Host "   2. Consumer Service (port 8082)" -ForegroundColor White
    Write-Host "   3. API Gateway (port 8080)" -ForegroundColor White
    Write-Host ""
    $continue = Read-Host "Continue anyway? (y/n)"
    if ($continue -ne 'y') {
        exit 0
    }
}

# Start frontend dev server
Write-Host "`n[3/3] Starting frontend development server..." -ForegroundColor Yellow
Write-Host "----------------------------------------" -ForegroundColor Gray
Write-Host "Frontend URL: http://localhost:3000" -ForegroundColor Cyan
Write-Host "Backend Proxy: /api -> http://localhost:8080" -ForegroundColor Cyan
Write-Host "New Path: /assistant/api/*" -ForegroundColor Cyan
Write-Host "----------------------------------------" -ForegroundColor Gray
Write-Host ""
Write-Host "Press Ctrl+C to stop the server" -ForegroundColor Yellow
Write-Host ""

npm run dev
