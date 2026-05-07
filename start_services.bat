@echo off
set AI_DASHSCOPE_API_KEY=sk-c423a8f289e04d07b6061c73fdc15c5c
set SERVER_PORT=8083

echo Starting Router...
start /B java -Dfile.encoding=UTF-8 -Dserver.port=8083 -jar D:\workspace\SmartAssistant\smart-assistant-router\target\smart-assistant-router-1.0.0-SNAPSHOT.jar > D:\workspace\SmartAssistant\logs\router-cmd.log 2>&1
echo Router started.

timeout /t 15 /nobreak >nul

echo Starting General...
set SERVER_PORT=8087
start /B java -Dfile.encoding=UTF-8 -Dserver.port=8087 -jar D:\workspace\SmartAssistant\smart-assistant-general\target\smart-assistant-general-1.0.0-SNAPSHOT.jar > D:\workspace\SmartAssistant\logs\general-cmd.log 2>&1
echo General started.

echo All services started.
