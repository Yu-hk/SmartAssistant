$env:REDIS_PASSWORD='redis123'
$env:DEEPSEEK_API_KEY='sk-06a0e6f007614f8f90c4f2cc57401b46'
$env:JWT_SECRET='VxhZo4UzJLHOfTILtwqa3a1vU5n3FD2OXorGoqJaw4U='
$env:POSTGRES_PASSWORD='postgres123'
$env:NACOS_PASSWORD='nacos'
$env:BGE_MODEL_PATH='D:\workspace\SmartAssistant\models\bge-large-zh-v1.5.onnx'
$env:BGE_VOCAB_PATH='D:\workspace\SmartAssistant\models\tokenizer.json'

$jar = 'D:\workspace\SmartAssistant\smart-assistant-'

Write-Host 'Starting Gateway...'
Start-Process -FilePath 'java' -ArgumentList '-Dfile.encoding=UTF-8','-jar',"${jar}gateway\target\smart-assistant-gateway-1.0.0-SNAPSHOT.jar",'--server.port=8081' -WindowStyle Hidden
Start-Sleep 15

Write-Host 'Starting User...'
Start-Process -FilePath 'java' -ArgumentList '-Dfile.encoding=UTF-8','-jar',"${jar}user\target\smart-assistant-user-1.0.0-SNAPSHOT.jar",'--server.port=8086' -WindowStyle Hidden
Start-Sleep 15

Write-Host 'Starting Consumer...'
Start-Process -FilePath 'java' -ArgumentList '-Dfile.encoding=UTF-8','-jar',"${jar}consumer\target\smart-assistant-consumer-1.0.0-SNAPSHOT.jar",'--server.port=8082' -WindowStyle Hidden
Start-Sleep 30

Write-Host 'Starting Router...'
Start-Process -FilePath 'java' -ArgumentList '-Dfile.encoding=UTF-8','-jar',"${jar}router\target\smart-assistant-router-1.0.0-SNAPSHOT.jar",'--server.port=8083' -WindowStyle Hidden
Start-Sleep 30

Write-Host 'Verifying...'
foreach ($p in @(8081,8086,8082,8083)) {
    try { $r = Invoke-WebRequest -Uri "http://localhost:$p/actuator/health" -TimeoutSec 5 -UseBasicParsing; Write-Host "Port $p : $($r.StatusCode) UP" }
    catch { Write-Host "Port $p : DOWN" }
}
