# Smart Assistant - 启动全部服务
$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = "D:\workspace\SmartAssistant"
Set-Location $root

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Smart Assistant Starting All Services" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# 检查 Docker 基础设施
Write-Host ""
Write-Host "[Docker] Checking services..." -ForegroundColor Yellow
$dockerProcs = docker ps --format "{{.Names}}"
if ($dockerProcs) {
    $dockerProcs | ForEach-Object { Write-Host ("  [OK] " + $_) -ForegroundColor Green }
} else {
    Write-Host "  [WARN] No Docker containers running" -ForegroundColor Yellow
}

# 等待 Nacos
Write-Host ""
Write-Host "[Nacos] Waiting for Nacos..." -ForegroundColor Yellow
$nacosReady = $false
for ($i = 0; $i -lt 45; $i++) {
    try {
        $resp = Invoke-WebRequest -Uri "http://127.0.0.1:8848/nacos/actuator/health" -TimeoutSec 2 -UseBasicParsing
        if ($resp.StatusCode -eq 200) {
            Write-Host "  [OK] Nacos is ready" -ForegroundColor Green
            $nacosReady = $true
            break
        }
    } catch {}
    Start-Sleep -Seconds 2
}

if (-not $nacosReady) {
    Write-Host "  [WARN] Nacos not ready after 90s, continue anyway..." -ForegroundColor Yellow
}

# 启动后端服务
Write-Host ""
Write-Host "[Backend] Starting services..." -ForegroundColor Cyan

$services = @(
    @{Name="gateway"; Port=8081; Jar="smart-assistant-gateway\target\smart-assistant-gateway-1.0.0-SNAPSHOT.jar"},
    @{Name="user";    Port=8086; Jar="smart-assistant-user\target\smart-assistant-user-1.0.0-SNAPSHOT.jar"},
    @{Name="consumer";Port=8082; Jar="smart-assistant-consumer\target\smart-assistant-consumer-1.0.0-SNAPSHOT.jar"},
    @{Name="router";  Port=8083; Jar="smart-assistant-router\target\smart-assistant-router-1.0.0-SNAPSHOT.jar"},
    @{Name="travel";  Port=8085; Jar="smart-assistant-travel\target\smart-assistant-travel-1.0.0-SNAPSHOT.jar"},
    @{Name="food";    Port=8084; Jar="smart-assistant-food\target\smart-assistant-food-1.0.0-SNAPSHOT.jar"},
    @{Name="general"; Port=8087; Jar="smart-assistant-general\target\smart-assistant-general-1.0.0-SNAPSHOT.jar"}
)

foreach ($svc in $services) {
    $jarPath = Join-Path $root $svc.Jar
    $outLog = Join-Path $root "logs\$($svc.Name)-stdout.log"
    $errLog = Join-Path $root "logs\$($svc.Name)-stderr.log"
    if (Test-Path $jarPath) {
        $proc = Start-Process -FilePath "java" -ArgumentList "-Dfile.encoding=UTF-8", "-jar", $jarPath -PassThru -RedirectStandardOutput $outLog -RedirectStandardError $errLog
        Write-Host ("  [OK] " + $svc.Name + " (PID: " + $proc.Id + ")") -ForegroundColor Green
    } else {
        Write-Host ("  [SKIP] " + $svc.Name + " - JAR not found") -ForegroundColor Red
    }
}

# 等待后端启动
Write-Host ""
Write-Host "[Waiting] Giving services time to initialize..." -ForegroundColor Cyan
Start-Sleep -Seconds 15

# 检查端口
Write-Host ""
Write-Host "[Ports] Checking listening ports..." -ForegroundColor Cyan
$ports = @(8081,8082,8083,8084,8085,8086,8087)
foreach ($port in $ports) {
    $listening = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($listening) {
        Write-Host ("  [OK] Port " + $port) -ForegroundColor Green
    } else {
        Write-Host ("  [WARN] Port " + $port + " not listening") -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Backend Startup Complete" -ForegroundColor Cyan
Write-Host "  Gateway:  http://localhost:8081" -ForegroundColor Cyan
Write-Host "  Frontend: http://localhost:3001" -ForegroundColor Cyan
Write-Host "  Logs: $root\logs\" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
