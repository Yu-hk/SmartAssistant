# Smart Assistant - 一键启动全部服务（后台运行，无窗口）
$OutputEncoding = [System.Text.Encoding]::UTF8
chcp 65001 > $null

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Smart Assistant Starting All Services" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

$root = "D:\workspace\SmartAssistant"

# 后端服务
$services = @(
    @{Name="Gateway"; Port=8081; Jar="smart-assistant-gateway\target\smart-assistant-gateway-1.0.0-SNAPSHOT.jar"},
    @{Name="User";    Port=8086; Jar="smart-assistant-user\target\smart-assistant-user-1.0.0-SNAPSHOT.jar"},
    @{Name="Consumer";Port=8082; Jar="smart-assistant-consumer\target\smart-assistant-consumer-1.0.0-SNAPSHOT.jar"},
    @{Name="Router";  Port=8083; Jar="smart-assistant-router\target\smart-assistant-router-1.0.0-SNAPSHOT.jar"},
    @{Name="Travel";  Port=8085; Jar="smart-assistant-travel\target\smart-assistant-travel-1.0.0-SNAPSHOT.jar"},
    @{Name="Food";    Port=8084; Jar="smart-assistant-food\target\smart-assistant-food-1.0.0-SNAPSHOT.jar"}
)

foreach ($svc in $services) {
    $jarPath = Join-Path $root $svc.Jar
    Start-Process -FilePath "java" -ArgumentList "-Dfile.encoding=UTF-8", "-jar", $jarPath -WindowStyle Hidden
    Write-Host "[$($svc.Name)] started (port $($svc.Port))" -ForegroundColor Green
}

# 前端（通过 .NET Process 后台运行，无窗口）
Write-Host "[Frontend] starting..." -ForegroundColor Yellow
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "cmd.exe"
$psi.Arguments = "/c npm run dev"
$psi.WorkingDirectory = "D:\workspace\SmartAssistant\frontend"
$psi.UseShellExecute = $true
$psi.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
$psi.CreateNoWindow = $true
[System.Diagnostics.Process]::Start($psi) | Out-Null
Write-Host "[Frontend] started (port 3001)" -ForegroundColor Green

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  All services started in background" -ForegroundColor Cyan
Write-Host "  Frontend: http://localhost:3001" -ForegroundColor Cyan
Write-Host "  Logs: $root\logs\" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
