# Smart Assistant - 一键启动全部服务（后台运行，无窗口）
$OutputEncoding = [System.Text.Encoding]::UTF8
chcp 65001 > $null

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Smart Assistant Starting All Services" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

$root = "D:\workspace\SmartAssistant"

# ========== 加载 .env 环境变量 ==========

function Load-EnvFile {
    param([string]$EnvFilePath)
    if (-not (Test-Path $EnvFilePath)) {
        Write-Host "[配置] ⚠️  不存在 .env 文件，跳过加载" -ForegroundColor Yellow
        Write-Host "[配置]   参考 .env.example 创建 .env：cp .env.example .env" -ForegroundColor Yellow
        return $false
    }
    $count = 0
    Get-Content $EnvFilePath | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq "" -or $line.StartsWith("#")) { return }
        $match = [regex]::Match($line, '^([^=]+)=(.*)$')
        if ($match.Success) {
            $key = $match.Groups[1].Value.Trim()
            $value = $match.Groups[2].Value.Trim()
            # 不覆盖已存在的环境变量（允许外部覆盖）
            if (-not [Environment]::GetEnvironmentVariable($key, "Process")) {
                [Environment]::SetEnvironmentVariable($key, $value, "Process")
                $count++
            }
        }
    }
    Write-Host "[配置] ✅ 已加载 $count 个环境变量" -ForegroundColor Green
    return $count -gt 0
}

Load-EnvFile -EnvFilePath "$root\.env"

# ========== 依赖服务检查 ==========

function Wait-ForService {
    param($Name, $Url, $TimeoutSeconds = 60)
    Write-Host "[依赖] 等待 $Name 就绪..." -ForegroundColor Yellow
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        try {
            $response = Invoke-WebRequest -Uri $Url -TimeoutSec 3 -UseBasicParsing -ErrorAction Stop
            if ($response.StatusCode -eq 200 -or $response.StatusCode -eq 401 -or $response.StatusCode -eq 302) {
                Write-Host "[依赖] $Name 已就绪 ($($timer.Elapsed.TotalSeconds.ToString('0.0')))" -ForegroundColor Green
                return $true
            }
        } catch { }
        Start-Sleep -Seconds 2
    }
    Write-Host "[依赖] ⚠️  $Name 未在 ${TimeoutSeconds}s 内就绪，继续启动..." -ForegroundColor Yellow
    return $false
}

# 等待 Docker 基础设施（Redis + Nacos）
Write-Host "`n[基础设施] 检查 Docker 服务..." -ForegroundColor Cyan
docker ps --format "{{.Names}}" 2>$null | ForEach-Object { Write-Host "  ✅ $_" -ForegroundColor Green }

# 等待 Nacos 就绪（服务发现依赖）
Wait-ForService -Name "Nacos" -Url "http://127.0.0.1:8848/nacos/actuator/health" -TimeoutSeconds 90

# ========== 后端服务启动 ==========

# 分组启动：Router 依赖 Nacos 就绪
$routerServices = @(
    @{Name="Gateway"; Port=8081; Jar="smart-assistant-gateway\target\smart-assistant-gateway-1.0.0-SNAPSHOT.jar"},
    @{Name="User";    Port=8086; Jar="smart-assistant-user\target\smart-assistant-user-1.0.0-SNAPSHOT.jar"}
)

$agentServices = @(
    @{Name="Consumer";Port=8082; Jar="smart-assistant-consumer\target\smart-assistant-consumer-1.0.0-SNAPSHOT.jar"},
    @{Name="Router";  Port=8083; Jar="smart-assistant-router\target\smart-assistant-router-1.0.0-SNAPSHOT.jar"},
    @{Name="Travel";  Port=8085; Jar="smart-assistant-travel\target\smart-assistant-travel-1.0.0-SNAPSHOT.jar"},
    @{Name="Food";    Port=8084; Jar="smart-assistant-food\target\smart-assistant-food-1.0.0-SNAPSHOT.jar"},
    @{Name="General"; Port=8087; Jar="smart-assistant-general\target\smart-assistant-general-1.0.0-SNAPSHOT.jar"}
)

# 第一组：Gateway + User（无需 Agent 发现）
Write-Host "`n[后端] 启动基础服务..." -ForegroundColor Cyan
foreach ($svc in $routerServices) {
    $jarPath = Join-Path $root $svc.Jar
    Start-Process -FilePath "java" -ArgumentList "-Dfile.encoding=UTF-8", "-jar", $jarPath -WindowStyle Hidden
    Write-Host "  [$($svc.Name)] started (port $($svc.Port))" -ForegroundColor Green
}

# 等待几秒让 Gateway 和 User 注册到 Nacos
Start-Sleep -Seconds 3

# 第二组：Agent 服务
Write-Host "`n[后端] 启动 Agent 服务..." -ForegroundColor Cyan
foreach ($svc in $agentServices) {
    $jarPath = Join-Path $root $svc.Jar
    Start-Process -FilePath "java" -ArgumentList "-Dfile.encoding=UTF-8", "-jar", $jarPath -WindowStyle Hidden
    Write-Host "  [$($svc.Name)] started (port $($svc.Port))" -ForegroundColor Green
}

# 等待后端服务注册
Start-Sleep -Seconds 5

# ========== 前端 ==========
Write-Host "`n[前端] starting..." -ForegroundColor Yellow
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "cmd.exe"
$psi.Arguments = "/c npm run dev"
$psi.WorkingDirectory = "D:\workspace\SmartAssistant\frontend"
$psi.UseShellExecute = $true
$psi.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
$psi.CreateNoWindow = $true
[System.Diagnostics.Process]::Start($psi) | Out-Null
Write-Host "[前端] started (port 3001)" -ForegroundColor Green

# ========== 汇总 ==========
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "  All services started in background" -ForegroundColor Cyan
Write-Host "  Frontend: http://localhost:3001" -ForegroundColor Cyan
Write-Host "  Logs: $root\logs\" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
