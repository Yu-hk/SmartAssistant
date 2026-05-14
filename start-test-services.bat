@echo off
chcp 65001 > nul

set REDIS_PASSWORD=redis123
set DEEPSEEK_API_KEY=sk-06a0e6f007614f8f90c4f2cc57401b46
set DASHSCOPE_API_KEY=sk-c423a8f289e04d07b6061c73fdc15c5c
set JWT_SECRET=VxhZo4UzJLHOfTILtwqa3a1vU5n3FD2OXorGoqJaw4U=
set POSTGRES_PASSWORD=postgres123
set NACOS_PASSWORD=nacos
set AMAP_API_KEY=403aa1cd6fd2000cab3c727e0083d00b
set BGE_MODEL_PATH=D:\workspace\SmartAssistant\models\bge-large-zh-v1.5.onnx
set BGE_VOCAB_PATH=D:\workspace\SmartAssistant\models\tokenizer.json

cd /d D:\workspace\SmartAssistant

echo Starting Gateway (8081)...
start /B java -Dfile.encoding=UTF-8 -jar smart-assistant-gateway\target\smart-assistant-gateway-1.0.0-SNAPSHOT.jar --server.port=8081 > logs\gw.log 2>&1
timeout /t 15 /nobreak > nul

echo Starting User (8086)...
start /B java -Dfile.encoding=UTF-8 -jar smart-assistant-user\target\smart-assistant-user-1.0.0-SNAPSHOT.jar --server.port=8086 > logs\user.log 2>&1
timeout /t 15 /nobreak > nul

echo Starting Consumer (8082)...
start /B java -Dfile.encoding=UTF-8 -Xmx2g -jar smart-assistant-consumer\target\smart-assistant-consumer-1.0.0-SNAPSHOT.jar --server.port=8082 > logs\consumer.log 2>&1
timeout /t 15 /nobreak > nul

echo Starting Router (8083)...
start /B java -Dfile.encoding=UTF-8 -Xmx4g -jar smart-assistant-router\target\smart-assistant-router-1.0.0-SNAPSHOT.jar --server.port=8083 > logs\router.log 2>&1

echo All services started. Waiting 60s for full readiness...
timeout /t 60 /nobreak > nul
echo Done.
