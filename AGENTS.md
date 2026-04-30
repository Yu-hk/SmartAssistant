# 项目启动方式

## 一键启动（推荐）

使用 `start-all.ps1` 脚本一键后台启动所有服务：

```powershell
powershell -ExecutionPolicy Bypass -File D:\workspace\SmartAssistant\start-all.ps1
```

## Maven 仓库

本地 Maven 仓库地址：**D:\repository**
- Maven home: D:\maven\apache-maven-3.9.6
- 设置文件: D:\maven\apache-maven-3.9.6\conf\settings.xml（已配置 localRepository=D:\repository）

## 手动启动（后台无窗口）

所有服务使用 `Start-Process -WindowStyle Hidden` 启动，运行在后台不弹出窗口，日志输出到 `logs/` 目录。

```powershell
# 后端服务（添加 -Dfile.encoding=UTF-8 解决中文乱码）
Start-Process -FilePath "java" -ArgumentList "-Dfile.encoding=UTF-8", "-jar", "D:\workspace\SmartAssistant\smart-assistant-gateway\target\smart-assistant-gateway-1.0.0-SNAPSHOT.jar" -WindowStyle Hidden -RedirectStandardOutput "$pwd\logs\gateway-stdout.log" -RedirectStandardError "$pwd\logs\gateway-stderr.log"
Start-Process -FilePath "java" -ArgumentList "-Dfile.encoding=UTF-8", "-jar", "D:\workspace\SmartAssistant\smart-assistant-user\target\smart-assistant-user-1.0.0-SNAPSHOT.jar" -WindowStyle Hidden -RedirectStandardOutput "$pwd\logs\user-stdout.log" -RedirectStandardError "$pwd\logs\user-stderr.log"
Start-Process -FilePath "java" -ArgumentList "-Dfile.encoding=UTF-8", "-jar", "D:\workspace\SmartAssistant\smart-assistant-consumer\target\smart-assistant-consumer-1.0.0-SNAPSHOT.jar" -WindowStyle Hidden -RedirectStandardOutput "$pwd\logs\consumer-stdout.log" -RedirectStandardError "$pwd\logs\consumer-stderr.log"
Start-Process -FilePath "java" -ArgumentList "-Dfile.encoding=UTF-8", "-jar", "D:\workspace\SmartAssistant\smart-assistant-router\target\smart-assistant-router-1.0.0-SNAPSHOT.jar" -WindowStyle Hidden -RedirectStandardOutput "$pwd\logs\router-stdout.log" -RedirectStandardError "$pwd\logs\router-stderr.log"
Start-Process -FilePath "java" -ArgumentList "-Dfile.encoding=UTF-8", "-jar", "D:\workspace\SmartAssistant\smart-assistant-travel\target\smart-assistant-travel-1.0.0-SNAPSHOT.jar" -WindowStyle Hidden -RedirectStandardOutput "$pwd\logs\travel-stdout.log" -RedirectStandardError "$pwd\logs\travel-stderr.log"
Start-Process -FilePath "java" -ArgumentList "-Dfile.encoding=UTF-8", "-jar", "D:\workspace\SmartAssistant\smart-assistant-food\target\smart-assistant-food-1.0.0-SNAPSHOT.jar" -WindowStyle Hidden -RedirectStandardOutput "$pwd\logs\food-stdout.log" -RedirectStandardError "$pwd\logs\food-stderr.log"

# 前端（通过 .NET Process 后台运行，无窗口）
```powershell
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "cmd.exe"
$psi.Arguments = "/c npm run dev"
$psi.WorkingDirectory = "D:\workspace\SmartAssistant\frontend"
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$proc = [System.Diagnostics.Process]::Start($psi)
Start-Sleep -Seconds 5
```
```

## 服务端口

| 服务 | 端口 |
|------|------|
| API Gateway | 8081 |
| Consumer | 8082 |
| Router | 8083 |
| Food | 8084 |
| Travel | 8085 |
| User | 8086 |
| Frontend | 3001 |

## 查看日志

日志优先级：**应用配置文件日志**（`logs/{spring.application.name}.log`）包含纯净的 Spring Boot 日志输出，优先查看。

```powershell
# 应用日志（推荐，根据 logback-spring.xml 配置）
type D:\workspace\SmartAssistant\logs\consumer-service.log -Tail 50
type D:\workspace\SmartAssistant\logs\router-service.log -Tail 50
type D:\workspace\SmartAssistant\logs\travel-service.log -Tail 50
type D:\workspace\SmartAssistant\logs\food-service.log -Tail 50
type D:\workspace\SmartAssistant\logs\api-gateway.log -Tail 50
type D:\workspace\SmartAssistant\logs\user-service.log -Tail 50

# 标准输出日志（包含启动横幅等）
type D:\workspace\SmartAssistant\logs\Consumer-stdout.log -Tail 30
type D:\workspace\SmartAssistant\logs\Router-stdout.log -Tail 30
