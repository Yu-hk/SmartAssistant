# Smart Assistant - 一键停止所有服务
$OutputEncoding = [System.Text.Encoding]::UTF8
chcp 65001 > $null

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Smart Assistant Stopping All Services" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# 停止所有 Java 后端进程
Write-Host "[后端] 停止 Java 进程..." -ForegroundColor Yellow
$javaProcesses = Get-CimInstance -ClassName Win32_Process -Filter "Name = 'java.exe'" | Where-Object {
    $_.CommandLine -match "smart-assistant"
}
$count = 0
foreach ($proc in $javaProcesses) {
    Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
    $count++
    Write-Host "  stopped PID=$($proc.ProcessId)" -ForegroundColor Green
}
if ($count -eq 0) {
    Write-Host "  未找到运行中的后端服务" -ForegroundColor Yellow
} else {
    Write-Host "  共停止 $count 个后端进程" -ForegroundColor Green
}

# 停止前端 Node 进程
Write-Host "[前端] 停止 Node 进程..." -ForegroundColor Yellow
$nodeProcesses = Get-CimInstance -ClassName Win32_Process -Filter "Name = 'node.exe'" | Where-Object {
    $_.CommandLine -match "npm run dev|vite"
}
$nodeCount = 0
foreach ($proc in $nodeProcesses) {
    Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
    $nodeCount++
    Write-Host "  stopped PID=$($proc.ProcessId)" -ForegroundColor Green
}
if ($nodeCount -eq 0) {
    Write-Host "  未找到运行中的前端进程" -ForegroundColor Yellow
} else {
    Write-Host "  共停止 $nodeCount 个前端进程" -ForegroundColor Green
}

Start-Sleep -Seconds 2

# 验证
$remaining = Get-CimInstance -ClassName Win32_Process -Filter "Name = 'java.exe'" | Where-Object {
    $_.CommandLine -match "smart-assistant"
}
if ($remaining) {
    Write-Host "⚠️  有 $($remaining.Count) 个进程未能停止，请手动检查" -ForegroundColor Yellow
} else {
    Write-Host "`n✅ 所有服务已停止" -ForegroundColor Green
}
