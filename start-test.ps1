$env:REDIS_PASSWORD='redis123'
$env:DEEPSEEK_API_KEY='sk-06a0e6f007614f8f90c4f2cc57401b46'
$env:DASHSCOPE_API_KEY='sk-c423a8f289e04d07b6061c73fdc15c5c'
$env:JWT_SECRET='VxhZo4UzJLHOfTILtwqa3a1vU5n3FD2OXorGoqJaw4U='
$env:POSTGRES_PASSWORD='postgres123'
$env:NACOS_PASSWORD='nacos'
$env:AMAP_API_KEY='403aa1cd6fd2000cab3c727e0083d00b'
$env:BGE_MODEL_PATH='D:\workspace\SmartAssistant\models\bge-large-zh-v1.5.onnx'
$env:BGE_VOCAB_PATH='D:\workspace\SmartAssistant\models\tokenizer.json'

Write-Host 'Starting Gateway (8081)...'
Start-Process -FilePath 'javaw' -ArgumentList '-Dfile.encoding=UTF-8','-jar','D:\workspace\SmartAssistant\smart-assistant-gateway\target\smart-assistant-gateway-1.0.0-SNAPSHOT.jar','--server.port=8081' -WindowStyle Hidden
Start-Sleep 15

Write-Host 'Gateway health...'
try { $r = Invoke-WebRequest -Uri 'http://localhost:8081/actuator/health' -TimeoutSec 5 -UseBasicParsing; Write-Host "Gateway: $($r.StatusCode) UP" } catch { Write-Host 'Gateway: DOWN' }

Write-Host 'Starting User (8086)...'
Start-Process -FilePath 'javaw' -ArgumentList '-Dfile.encoding=UTF-8','-jar','D:\workspace\SmartAssistant\smart-assistant-user\target\smart-assistant-user-1.0.0-SNAPSHOT.jar','--server.port=8086' -WindowStyle Hidden
Start-Sleep 15

Write-Host 'User health...'
try { $r = Invoke-WebRequest -Uri 'http://localhost:8086/actuator/health' -TimeoutSec 5 -UseBasicParsing; Write-Host "User: $($r.StatusCode) UP" } catch { Write-Host 'User: DOWN' }

Write-Host 'Starting Consumer (8082)...'
Start-Process -FilePath 'javaw' -ArgumentList '-Dfile.encoding=UTF-8','-jar','D:\workspace\SmartAssistant\smart-assistant-consumer\target\smart-assistant-consumer-1.0.0-SNAPSHOT.jar','--server.port=8082' -WindowStyle Hidden
Start-Sleep 20

Write-Host 'Consumer health...'
try { $r = Invoke-WebRequest -Uri 'http://localhost:8082/actuator/health' -TimeoutSec 5 -UseBasicParsing; Write-Host "Consumer: $($r.StatusCode) UP" } catch { Write-Host 'Consumer: DOWN' }

Write-Host 'Starting Router (8083)...'
Start-Process -FilePath 'javaw' -ArgumentList '-Dfile.encoding=UTF-8','-jar','D:\workspace\SmartAssistant\smart-assistant-router\target\smart-assistant-router-1.0.0-SNAPSHOT.jar','--server.port=8083' -WindowStyle Hidden
Start-Sleep 30

Write-Host 'Router health...'
try { $r = Invoke-WebRequest -Uri 'http://localhost:8083/actuator/health' -TimeoutSec 5 -UseBasicParsing; Write-Host "Router: $($r.StatusCode) UP" } catch { Write-Host 'Router: DOWN' }

Write-Host 'All services started!'
