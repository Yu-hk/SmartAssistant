@echo off
echo ========================================
echo   A2A Monitoring Stack Starter
echo ========================================
echo.

cd /d "%~dp0"

echo [1/3] Starting monitoring services...
docker-compose up -d prometheus grafana loki promtail jaeger

echo.
echo [2/3] Waiting for services to start...
timeout /t 5 /nobreak >nul

echo.
echo [3/3] Starting real-time log sync service...
start powershell -NoExit -ExecutionPolicy Bypass -File ".\sync-logs-realtime.ps1"

echo.
echo ========================================
echo   All services started!
echo ========================================
echo.
echo Services:
echo   - Prometheus: http://localhost:9090
echo   - Grafana:    http://localhost:3000 (admin/admin)
echo   - Loki:       http://localhost:3100
echo   - Jaeger:     http://localhost:16686
echo.
echo Tip: Check logs at Grafana Explore
echo.
pause
