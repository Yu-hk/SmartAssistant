# Smart Assistant - 一键启动全部服务（后台运行，无窗口）
$OutputEncoding = [System.Text.Encoding]::UTF8
chcp 65001 > $null

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Smart Assistant Starting All Services" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

$root = "D:\workspace\SmartAssistant"

function Load-EnvFile {
    param([string]$EnvFilePath)
    if (-not (Test-Path $EnvFilePath)) {
        Write-Host "[Config] .env file not found, skipping" -ForegroundColor Yellow
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
            if (-not [Environment]::GetEnvironmentVariable($key, "Process")) {
                [Environment]::SetEnvironmentVariable($key, $value, "Process")
                $count++
            }
        }
    }
    Write-Host "[Config] Loaded $count environment variables" -ForegroundColor Green
    return $count -gt 0
}

Load-EnvFile -EnvFilePath "$root\.env"

function Wait-ForService {
    param($Name, $Url, $TimeoutSeconds = 60)
    Write-Host "[Check] Waiting for $Name..." -ForegroundColor Yellow
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        try {
            $response = Invoke-WebRequest -Uri $Url -TimeoutSec 3 -UseBasicParsing -ErrorAction Stop
            if ($response.StatusCode -eq 200 -or $response.StatusCode -eq 401 -or $response.StatusCode -eq 302) {
                Write-Host "[Check] $Name ready ($($timer.Elapsed.TotalSeconds.ToString('0.0')))" -ForegroundColor Green
                return $true
            }
        } catch { }
        Start-Sleep -Seconds 2
    }
    Write-Host "[Check] $Name not ready within ${TimeoutSeconds}s" -ForegroundColor Yellow
    return $false
}

Write-Host "[Infra] Checking Docker services..." -ForegroundColor Cyan
docker ps --format "{{.Names}}" 2>$null | ForEach-Object { Write-Host "  $_" -ForegroundColor Green }

Wait-ForService -Name "Nacos" -Url "http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=1" -TimeoutSeconds 90

$envVarsToPass = @(
    "DEEPSEEK_API_KEY", "DASHSCOPE_API_KEY", "AMAP_API_KEY",
    "POSTGRES_PASSWORD", "REDIS_PASSWORD", "JWT_SECRET",
    "NACOS_SERVER_ADDR", "NACOS_USERNAME", "NACOS_PASSWORD",
    "ZIPKIN_ENDPOINT", "HANLP_DATA_PATH", "POSTGRES_USER",
    "POSTGRES_HOST", "POSTGRES_PORT", "POSTGRES_DB",
    "BGE_MODEL_PATH", "BGE_VOCAB_PATH"
)

function Get-JavaArgs {
    param([string]$JarName)
    $ja = @()
    $ja += "-Dfile.encoding=UTF-8"
    $ja += "-Drouter.semantic-cache.enabled=true"
    foreach ($key in $envVarsToPass) {
        $val = [Environment]::GetEnvironmentVariable($key, "Process")
        if (-not [string]::IsNullOrEmpty($val)) {
            $ja += "-D$key=$val"
        }
    }
    # DashScope 自动配置需要 spring.ai.dashscope.api-key 系统属性
    $dashScopeKey = [Environment]::GetEnvironmentVariable("DASHSCOPE_API_KEY", "Process")
    if (-not [string]::IsNullOrEmpty($dashScopeKey)) {
        $ja += "-Dspring.ai.dashscope.api-key=$dashScopeKey"
    }
    $jwtSecret = [Environment]::GetEnvironmentVariable("JWT_SECRET", "Process")
    if ([string]::IsNullOrEmpty($jwtSecret)) {
        $ja += "-Djwt.secret=VxhZo4UzJLHOfTILtwqa3a1vU5n3FD2OXorGoqJaw4U="
    }
    $ja += "-jar"
    $ja += $JarName
    return $ja
}

$routerServices = @(
    @{Name="Gateway"; Port=8081; Jar="smart-assistant-gateway\target\smart-assistant-gateway-1.0.0-SNAPSHOT.jar"; Log="gateway"},
    @{Name="User";    Port=8086; Jar="smart-assistant-user\target\smart-assistant-user-1.0.0-SNAPSHOT.jar"; Log="user"}
)

$agentServices = @(
    @{Name="Consumer";Port=8082; Jar="smart-assistant-consumer\target\smart-assistant-consumer-1.0.0-SNAPSHOT.jar"; Log="consumer"},
    @{Name="Router";  Port=8083; Jar="smart-assistant-router\target\smart-assistant-router-1.0.0-SNAPSHOT.jar"; Log="router"},
    @{Name="Travel";  Port=8085; Jar="smart-assistant-travel\target\smart-assistant-travel-1.0.0-SNAPSHOT.jar"; Log="travel"},
    @{Name="Food";    Port=8084; Jar="smart-assistant-food\target\smart-assistant-food-1.0.0-SNAPSHOT.jar"; Log="food"},
    @{Name="General"; Port=8087; Jar="smart-assistant-general\target\smart-assistant-general-1.0.0-SNAPSHOT.jar"; Log="general"}
)

Write-Host "`n[Backend] Starting core services..." -ForegroundColor Cyan
foreach ($svc in $routerServices) {
    $jarPath = Join-Path $root $svc.Jar
    if (-not (Test-Path $jarPath)) {
        Write-Host "  [$($svc.Name)] JAR not found: $jarPath" -ForegroundColor Yellow
        continue
    }
    $javaArgs = Get-JavaArgs -JarName $jarPath
    $logDir = "$root\logs"
    if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
    Start-Process -FilePath "java" -ArgumentList $javaArgs -WindowStyle Hidden `
        -RedirectStandardOutput "$logDir\$($svc.Log)-stdout.log" `
        -RedirectStandardError "$logDir\$($svc.Log)-stderr.log"
    Write-Host "  [$($svc.Name)] started (port $($svc.Port))" -ForegroundColor Green
}

Start-Sleep -Seconds 5

Write-Host "`n[Backend] Starting Agent services..." -ForegroundColor Cyan
foreach ($svc in $agentServices) {
    $jarPath = Join-Path $root $svc.Jar
    if (-not (Test-Path $jarPath)) {
        Write-Host "  [$($svc.Name)] JAR not found: $jarPath" -ForegroundColor Yellow
        continue
    }
    $javaArgs = Get-JavaArgs -JarName $jarPath
    $logDir = "$root\logs"
    Start-Process -FilePath "java" -ArgumentList $javaArgs -WindowStyle Hidden `
        -RedirectStandardOutput "$logDir\$($svc.Log)-stdout.log" `
        -RedirectStandardError "$logDir\$($svc.Log)-stderr.log"
    Write-Host "  [$($svc.Name)] started (port $($svc.Port))" -ForegroundColor Green
}

Start-Sleep -Seconds 5

Write-Host "`n[Frontend] starting..." -ForegroundColor Yellow
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "cmd.exe"
$psi.Arguments = "/c npm run dev"
$psi.WorkingDirectory = "D:\workspace\SmartAssistant\frontend"
$psi.UseShellExecute = $true
$psi.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
$psi.CreateNoWindow = $true
[System.Diagnostics.Process]::Start($psi) | Out-Null
Write-Host "[Frontend] started (port 3001)" -ForegroundColor Green

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "  All services started in background" -ForegroundColor Cyan
Write-Host "  Frontend: http://localhost:3001" -ForegroundColor Cyan
Write-Host "  Logs: $root\logs\" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
