@echo off
set JAVA_HOME=C:\Program Files\Java\jdk-21
set MAVEN_HOME=C:\Users\~1\.m2\wrapper\dists\apache-maven-3.9.6\8b309f6e91bd096261e2d839c34abe89ef71f3e5075460adad8cc51b22413c3e
set PATH=%JAVA_HOME%\bin;%PATH%

:: Load .env
for /f "usebackq delims=" %%a in ("D:\workspace\SmartAssistant\.env") do (
    for /f "tokens=1,* delims==" %%b in ("%%a") do (
        if not "%%b"=="" set "%%b=%%c"
    )
)

cd /d D:\workspace\SmartAssistant
echo Starting user-service...
"%JAVA_HOME%\bin\java.exe" -classpath "%MAVEN_HOME%\boot\plexus-classworlds-2.7.0.jar" -Dclassworlds.conf="%MAVEN_HOME%\bin\m2.conf" -Dmaven.home="%MAVEN_HOME%" -Dmaven.multiModuleProjectDirectory="D:\workspace\SmartAssistant" org.codehaus.plexus.classworlds.launcher.Launcher spring-boot:run -pl smart-assistant-user -am -DskipTests > D:\workspace\SmartAssistant\logs\user-service.log 2>&1
