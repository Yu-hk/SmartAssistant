#requires -Version 5.1
# =============================================================================
# SmartAssistant - Windows 构建脚本 (PowerShell)
# =============================================================================
$ErrorActionPreference = "Stop"
$ProjectDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

Write-Host "============================================" -ForegroundColor Cyan
Write-Host " SmartAssistant - 构建开始" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan

Set-Location $ProjectDir

# 1. 检查 JDK 21
if (-not (Get-Command java -ErrorAction SilentlyContinue) -or -not (Get-Command javac -ErrorAction SilentlyContinue)) {
    Write-Host "ERROR: 未找到完整的 JDK 21（需要 java 和 javac）。" -ForegroundColor Red
    exit 1
}

$javaVersionLine = (& java --version 2>&1 | Select-Object -First 1).ToString()
$javacVersionLine = (& javac --version 2>&1 | Select-Object -First 1).ToString()
$javaMajor = [regex]::Match($javaVersionLine, '\b(?<major>\d+)(?:\.|\s|$)')
$javacMajor = [regex]::Match($javacVersionLine, '\b(?<major>\d+)(?:\.|\s|$)')
if (
    -not $javaMajor.Success -or
    -not $javacMajor.Success -or
    [int]$javaMajor.Groups["major"].Value -ne 21 -or
    [int]$javacMajor.Groups["major"].Value -ne 21
) {
    Write-Host "ERROR: 构建必须使用 JDK 21。" -ForegroundColor Red
    Write-Host "  java:  $javaVersionLine" -ForegroundColor Red
    Write-Host "  javac: $javacVersionLine" -ForegroundColor Red
    exit 1
}
Write-Host "[1/3] JDK version: $javaVersionLine" -ForegroundColor Green

# 2. Maven 构建后端
Write-Host "[2/3] Building backend services..." -ForegroundColor Yellow
& .\mvnw.cmd clean package -DskipTests -q
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Maven build failed!" -ForegroundColor Red
    exit 1
}
Write-Host "  Backend build complete." -ForegroundColor Green

# 3. 构建前端
Write-Host "[3/3] Building frontend..." -ForegroundColor Yellow
Set-Location "$ProjectDir/frontend"
if (-not (Get-Command node -ErrorAction SilentlyContinue) -or -not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) {
    Write-Host "ERROR: Node.js or npm not found." -ForegroundColor Red
    exit 1
}

if (-not (Test-Path "$ProjectDir/frontend/node_modules")) {
    if (Test-Path "$ProjectDir/frontend/package-lock.json") {
        Write-Host "  node_modules not found; running npm ci..." -ForegroundColor Yellow
        & npm.cmd ci
    } else {
        Write-Host "  package-lock.json not found; running npm install..." -ForegroundColor Yellow
        & npm.cmd install
    }
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: Frontend dependency installation failed!" -ForegroundColor Red
        exit 1
    }
}

& npm.cmd run build
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
