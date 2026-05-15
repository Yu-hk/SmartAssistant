$jar = 'D:\workspace\SmartAssistant\smart-assistant-'
$JAVA = 'java'

# Common env vars
$D = @(
    '-Dfile.encoding=UTF-8',
    '-Dspring.ai.dashscope.api-key=sk-c423a8f289e04d07b6061c73fdc15c5c',
    '-DPOSTGRES_PASSWORD=postgres123',
    '-DREDIS_PASSWORD=redis123',
    '-DJWT_SECRET=VxhZo4UzJLHOfTILtwqa3a1vU5n3FD2OXorGoqJaw4U=',
    '-DDEEPSEEK_API_KEY=sk-06a0e6f007614f8f90c4f2cc57401b46',
    '-DNACOS_PASSWORD=nacos',
    '-DBGE_MODEL_PATH=D:\workspace\SmartAssistant\models\bge-large-zh-v1.5.onnx',
    '-DBGE_VOCAB_PATH=D:\workspace\SmartAssistant\models\tokenizer.json'
)

function Start-Svc($name, $port, $wait) {
    $cp = "$jar$name\target\smart-assistant-$name-1.0.0-SNAPSHOT.jar"
    Write-Host "Starting $name ($port)..."
    $p = Start-Process -FilePath $JAVA -ArgumentList ($D + @('-jar', $cp, "--server.port=$port")) -WindowStyle Hidden -PassThru
    Write-Host "  PID: $($p.Id)"
    Start-Sleep $wait
}

# Kill existing
Get-Process | Where-Object { $_.ProcessName -eq 'java' -or $_.ProcessName -eq 'javaw' } | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep 3

Start-Svc 'gateway' 8081 20
Start-Svc 'user' 8086 15
Start-Svc 'consumer' 8082 55
Start-Svc 'router' 8083 30
Start-Svc 'order' 8085 15
Start-Svc 'product' 8084 15
Start-Svc 'general' 8087 15

Write-Host "`n=== Health Check ==="
foreach ($p in @(8081,8086,8082,8083,8085,8084,8087)) {
    try { $r = Invoke-WebRequest -Uri "http://localhost:$p/actuator/health" -TimeoutSec 5 -UseBasicParsing; Write-Host "Port $p : $($r.StatusCode) UP" }
    catch { Write-Host "Port $p : DOWN" }
}
