<#
.SYNOPSIS
    SmartAssistant RAG 生产化 —— PostgreSQL + pgvector 真集成一键验证脚本（Windows / PowerShell）
.DESCRIPTION
    补齐「RAG 生产化改造」唯一未连环境验证的环节：
      1) 检查 Docker 可用
      2) 启动 docker-compose-infra.yml 中的 postgres 服务
         （pgvector/pgvector:0.8.0-pg16，端口 5433:5432，库 a2a_system，用户 postgres/postgres123）
      3) 轮询 pg_isready 直到就绪（超时 60s）
      4) 运行 PgVectorKnowledgeBaseIntegrationTest（-Dpg.integration=true）
      5) 输出结果，可选停止 postgres
.PARAMETER MavenExe
    Maven 可执行文件路径（默认 D:\maven\apache-maven-3.9.6\bin\mvn，可用 $env:MAVEN_HOME 覆盖）
.PARAMETER JavaHome
    JAVA_HOME（默认 "D:\Program Files\Java\jdk-21.0.6+7"，可用 $env:JAVA_HOME 覆盖）
.PARAMETER StopPostgres
    验证完成后停止 postgres 服务（默认不停止，便于复用）
.EXAMPLE
    .\scripts\verify-pg-rag-integration.ps1
#>
[CmdletBinding()]
param(
    [string]$MavenExe = "",
    [string]$JavaHome = "",
    [switch]$StopPostgres = $false
)

$ErrorActionPreference = "Stop"

# ---------- 路径解析 ----------
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Resolve-Path (Join-Path $ScriptDir "..")
$ComposeFile = Join-Path $ProjectRoot "docker-compose-infra.yml"

# ---------- 工具默认路径（可用环境变量/参数覆盖） ----------
if (-not $MavenExe) {
    if ($env:MAVEN_HOME) { $MavenExe = Join-Path $env:MAVEN_HOME "bin\mvn" }
    else { $MavenExe = "D:\maven\apache-maven-3.9.6\bin\mvn" }
}
if (-not $JavaHome) {
    if ($env:JAVA_HOME) { $JavaHome = $env:JAVA_HOME }
    else { $JavaHome = "D:\Program Files\Java\jdk-21.0.6+7" }
}
$env:JAVA_HOME = $JavaHome

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " SmartAssistant PG+RAG 集成验证（PowerShell）" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " 项目根目录 : $ProjectRoot"
Write-Host " 编排文件   : $ComposeFile"
Write-Host " Maven      : $MavenExe"
Write-Host " JAVA_HOME  : $JavaHome"
Write-Host "============================================================" -ForegroundColor Cyan

# ---------- 1) 检查 Docker ----------
Write-Host ""
Write-Host "[1/4] 检查 Docker 可用性..." -ForegroundColor Yellow
$dockerOk = $false
try {
    $dv = & docker --version 2>&1
    if ($LASTEXITCODE -eq 0) { $dockerOk = $true; Write-Host "  Docker 可用: $dv" }
} catch { }
if (-not $dockerOk) {
    Write-Host "  错误：未检测到 Docker。请先安装并启动 Docker Desktop 后重试。" -ForegroundColor Red
    exit 1
}

# 选择 compose 命令（优先 v2 'docker compose'，回退 v1 'docker-compose'）
$useComposePlugin = $false
if ((& docker compose version 2>$null) -and $LASTEXITCODE -eq 0) { $useComposePlugin = $true }
elseif ((& docker-compose version 2>$null) -and $LASTEXITCODE -eq 0) { $useComposePlugin = $true }
if (-not $useComposePlugin) {
    Write-Host "  错误：未检测到 docker compose / docker-compose 插件。" -ForegroundColor Red
    exit 1
}
function Invoke-Compose {
    param([string[]]$Args)
    if ($useComposePlugin) { & docker compose @Args }
    else { & docker-compose @Args }
}
Write-Host "  使用编排命令: $(if ($useComposePlugin) { 'docker compose' } else { 'docker-compose' })"

# ---------- 2) 启动 postgres ----------
Write-Host ""
Write-Host "[2/4] 启动 PostgreSQL + pgvector 服务..." -ForegroundColor Yellow
Invoke-Compose -Args @("-f", $ComposeFile, "up", "postgres", "-d")
if ($LASTEXITCODE -ne 0) {
    Write-Host "  错误：启动 postgres 失败，请检查 Docker 状态。" -ForegroundColor Red
    exit 1
}
Write-Host "  postgres 服务已启动（端口 5433:5432，库 a2a_system，用户 postgres/postgres123）。"

# ---------- 3) 轮询 pg_isready ----------
Write-Host ""
Write-Host "[3/4] 等待 PostgreSQL 就绪（超时 60s）..." -ForegroundColor Yellow
$ready = $false
for ($i = 1; $i -le 30; $i++) {
    $out = & docker exec smart-postgres pg_isready -U postgres -d a2a_system 2>&1
    if ($LASTEXITCODE -eq 0) {
        $ready = $true
        Write-Host "  PostgreSQL 已就绪（第 $i 次探测）。"
        break
    }
    Write-Host "  等待中... ($i/30) $out"
    Start-Sleep -Seconds 2
}
if (-not $ready) {
    Write-Host "  错误：60s 内 PostgreSQL 未就绪，请检查容器日志。" -ForegroundColor Red
    Invoke-Compose -Args @("-f", $ComposeFile, "logs", "postgres")
    exit 1
}

# ---------- 4) 运行集成测试 ----------
Write-Host ""
Write-Host "[4/4] 运行 PgVectorKnowledgeBaseIntegrationTest..." -ForegroundColor Yellow
$testArgs = @("-pl", "smart-assistant-common", "test",
    "-Dtest=PgVectorKnowledgeBaseIntegrationTest",
    "-Dpg.integration=true")
& $MavenExe $testArgs
$testExit = $LASTEXITCODE

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
if ($testExit -eq 0) {
    Write-Host " 集成测试执行完成（BUILD SUCCESS）。详见上方 Surefire 报告与 target/surefire-reports。" -ForegroundColor Green
} else {
    Write-Host " 集成测试存在失败（BUILD FAILURE）。请检查上方输出与 target/surefire-reports/PgVectorKnowledgeBaseIntegrationTest.txt。" -ForegroundColor Red
}
Write-Host "============================================================" -ForegroundColor Cyan

# ---------- 可选：停止 postgres ----------
if ($StopPostgres) {
    Write-Host ""
    Write-Host "按要求停止 postgres 服务..." -ForegroundColor Yellow
    Invoke-Compose -Args @("-f", $ComposeFile, "stop", "postgres")
}

exit $testExit
