# =============================================================================
# SmartAssistant - Windows 构建脚本 (PowerShell)
# =============================================================================
$ErrorActionPreference = "Stop"
$ProjectDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

Write-Host "============================================" -ForegroundColor Cyan
Write-Host " SmartAssistant - 构建开始" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan

Set-Location $ProjectDir

# 1. Maven 构建后端
Write-Host "[1/2] Building backend services..." -ForegroundColor Yellow
& .\mvnw.cmd clean package -DskipTests -q
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Maven build failed!" -ForegroundColor Red
    exit 1
}
Write-Host "  Backend build complete." -ForegroundColor Green

# 2. 构建前端
Write-Host "[2/2] Building frontend..." -ForegroundColor Yellow
Set-Location "$ProjectDir/frontend"
npm run build
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Frontend build failed!" -ForegroundColor Red
    exit 1
}
Write-Host "  Frontend build complete." -ForegroundColor Green

Write-Host "============================================" -ForegroundColor Cyan
Write-Host " 构建完成！" -ForegroundColor Green
Write-Host "  - 后端 JAR: */target/*.jar" -ForegroundColor Green
Write-Host "  - 前端:     frontend/dist/" -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "部署步骤：" -ForegroundColor Yellow
Write-Host "  1. 将整个项目目录上传到云服务器" -ForegroundColor White
Write-Host "  2. cd deploy && cp .env.production .env" -ForegroundColor White
Write-Host "  3. docker compose --env-file .env up -d" -ForegroundColor White
