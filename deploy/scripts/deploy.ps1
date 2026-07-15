#!/usr/bin/env pwsh
#requires -Version 5.1
# =============================================================================
# SmartAssistant - Windows 一键部署脚本
# 在开发机（Windows）上执行，自动构建 + 上传 + 远程启动
# =============================================================================
param(
    # 不再硬编码生产 IP，须通过参数或环境变量 DEPLOY_SERVER_IP 传入
    [string]$ServerIP = $env:DEPLOY_SERVER_IP,
    [string]$ServerUser = $(if ($env:DEPLOY_SERVER_USER) { $env:DEPLOY_SERVER_USER } else { "root" }),
    [string]$ServerPath = "/opt/smart-assistant",
    [switch]$SkipBuild,
    [switch]$OnlyRestart
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ServerIP)) {
    Write-Host "ERROR: 未指定服务器 IP。请使用 -ServerIP <ip> 或设置环境变量 DEPLOY_SERVER_IP。" -ForegroundColor Red
    exit 1
}
$ProjectRoot = Resolve-Path (Split-Path -Parent $PSScriptRoot | Join-Path -ChildPath "..")

Write-Host "============================================" -ForegroundColor Cyan
Write-Host " SmartAssistant - 一键部署" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "服务器: $ServerUser@$ServerIP" -ForegroundColor White
Write-Host "项目路径: $ProjectRoot" -ForegroundColor White
Write-Host "============================================" -ForegroundColor Cyan

if ($OnlyRestart) {
    Write-Host "[INFO] 仅重启服务..." -ForegroundColor Yellow
    ssh -o StrictHostKeyChecking=no $ServerUser@$ServerIP "cd $ServerPath && docker compose -f deploy/docker-compose.yml --env-file deploy/.env restart"
    Write-Host "  重启完成" -ForegroundColor Green
    exit 0
}

# 1. 检查/构建
if (-not $SkipBuild) {
    Write-Host "[1/4] 检查本地构建产物..." -ForegroundColor Yellow
    $hasJars = (Get-ChildItem "$ProjectRoot/smart-assistant-gateway/target/*.jar" -ErrorAction SilentlyContinue).Count -gt 0
    $hasFrontend = Test-Path "$ProjectRoot/frontend/dist/index.html"
    
    if (-not $hasJars -or -not $hasFrontend) {
        Write-Host "  开始本地构建..." -ForegroundColor Yellow
        
        # 构建后端
        Write-Host "  [1a] 构建后端（Maven）..." -ForegroundColor Yellow
        Set-Location $ProjectRoot
        .\mvnw.cmd clean package -DskipTests -q
        if ($LASTEXITCODE -ne 0) {
            Write-Host "ERROR: Maven 构建失败！" -ForegroundColor Red
            exit 1
        }
        Write-Host "  Maven 构建成功。" -ForegroundColor Green
        
        # 构建前端
        Write-Host "  [1b] 构建前端（npm）..." -ForegroundColor Yellow
        Set-Location "$ProjectRoot/frontend"
        npm run build 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0) {
            Write-Host "ERROR: 前端构建失败！" -ForegroundColor Red
            exit 1
        }
        Write-Host "  前端构建成功。" -ForegroundColor Green
    } else {
        Write-Host "  构建产物已存在，跳过构建。" -ForegroundColor Green
    }
}

# 2. 上传文件
Write-Host "[2/4] 上传文件到服务器..." -ForegroundColor Yellow

# 检查 SSH
$sshTest = ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=no $ServerUser@$ServerIP "echo OK" 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: SSH 连接失败！请检查服务器 IP、用户名和密码/密钥。" -ForegroundColor Red
    Write-Host "  首次连接请执行：ssh $ServerUser@$ServerIP" -ForegroundColor Yellow
    exit 1
}

Write-Host "  SSH 连接正常，开始上传..." -ForegroundColor Yellow
ssh $ServerUser@$ServerIP "mkdir -p $ServerPath" 2>&1 | Out-Null

# 使用 rsync（优先）或 scp
$rsync = Get-Command rsync -ErrorAction SilentlyContinue
if ($rsync) {
    Write-Host "  使用 rsync 上传..." -ForegroundColor Yellow
    rsync -avz --progress --delete `
        --exclude=node_modules --exclude=.git --exclude=logs --exclude=dumpstream `
        --exclude=frontend/node_modules --exclude=*.log `
        --exclude=.env --exclude=.env.local --exclude=.env.*.local `
        --exclude=ai --exclude=data --exclude=hanlp-data --exclude=training --exclude=test-data `
        -e "ssh -o StrictHostKeyChecking=no" `
        "$ProjectRoot/" "$ServerUser@${ServerIP}:$ServerPath/"
} else {
    Write-Host "  使用 scp 上传..." -ForegroundColor Yellow
    # 打包排除
    $items = Get-ChildItem -Path $ProjectRoot -Directory | Where-Object { 
        $_.Name -notin @('node_modules','.git','logs','dumpstream','ai','data','hanlp-data','training','test-data','scripts')
    }
    foreach ($item in $items) {
        Write-Host "  上传: $($item.Name)..." -ForegroundColor Yellow
        scp -r -o StrictHostKeyChecking=no "$($item.FullName)" "$ServerUser@${ServerIP}:$ServerPath/" 2>&1 | Out-Null
    }
    # 单独上传关键文件
    scp -o StrictHostKeyChecking=no "$ProjectRoot/pom.xml" "$ServerUser@$ServerIP`:$ServerPath/" 2>&1 | Out-Null
    scp -o StrictHostKeyChecking=no "$ProjectRoot/mvnw*" "$ServerUser@$ServerIP`:$ServerPath/" 2>&1 | Out-Null
    scp -o StrictHostKeyChecking=no "$ProjectRoot/.mvn" -r "$ServerUser@$ServerIP`:$ServerPath/" 2>&1 | Out-Null
}

Write-Host "  上传完成。" -ForegroundColor Green

# 3. 远程部署
Write-Host "[3/4] 服务器部署..." -ForegroundColor Yellow

$remoteCmd = @"
set -e
cd $ServerPath

echo "[远程] 检查 .env..."
if [ ! -f deploy/.env ] && [ -f deploy/.env.production ]; then
    cp deploy/.env.production deploy/.env
    echo "[远程] 警告：从模板创建了 .env，请编辑填入真实密钥！"
fi

echo "[远程] 启动 Docker Compose..."
if ! command -v docker &> /dev/null; then
    echo "ERROR: Docker 未安装！请先运行 init-server.sh"
    exit 1
fi

docker compose -f deploy/docker-compose.yml --env-file deploy/.env pull --parallel 2>/dev/null || true
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build --remove-orphans

echo "[远程] 服务状态："
docker compose -f deploy/docker-compose.yml ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}"

echo ""
echo "Ollama 模型正在后台拉取，首次需要 5-10 分钟..."
echo "查看进度: docker logs -f smart-ollama-setup"
"@

$sshResult = ssh -o StrictHostKeyChecking=no $ServerUser@$ServerIP "$remoteCmd" 2>&1
$sshResult | ForEach-Object { Write-Host "  [远程] $_" -ForegroundColor Gray }

# 4. 健康检查
Write-Host "[4/4] 检查服务状态..." -ForegroundColor Yellow
Start-Sleep -Seconds 5

$healthCheck = ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 $ServerUser@$ServerIP "curl -s -o /dev/null -w '%{http_code}' http://localhost:80/healthz" 2>&1
if ($healthCheck -eq "200") {
    Write-Host "  健康检查通过！" -ForegroundColor Green
} else {
    Write-Host "  健康检查返回 $healthCheck，服务可能还在启动中..." -ForegroundColor Yellow
}

Write-Host "============================================" -ForegroundColor Cyan
Write-Host " 部署完成！" -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Cyan
Write-Host " 访问地址：http://$ServerIP" -ForegroundColor White
Write-Host " 健康检查：http://$ServerIP/healthz" -ForegroundColor White
Write-Host "" -ForegroundColor White
Write-Host " 常用命令：" -ForegroundColor Yellow
Write-Host "  ssh $ServerUser@$ServerIP" -ForegroundColor White
Write-Host "  docker logs -f smart-ollama-setup" -ForegroundColor White
Write-Host "  docker compose -f deploy/docker-compose.yml logs -f" -ForegroundColor White
Write-Host "============================================" -ForegroundColor Cyan
