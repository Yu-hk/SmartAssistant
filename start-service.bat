@echo off
setlocal enabledelayedexpansion

set JAVA_HOME=C:\Program Files\Java\jdk-21
set MAVEN_HOME=C:\Users\于海阔\.m2\wrapper\dists\apache-maven-3.9.6\8b309f6e91bd096261e2d839c34abe89ef71f3e5075460adad8cc51b22413c3e
set PATH=%JAVA_HOME%\bin;%PATH%
set PROJECT_DIR=D:\workspace\SmartAssistant

:: Load .env
for /f "usebackq delims=" %%a in ("%PROJECT_DIR%\.env") do (
    for /f "tokens=1,* delims==" %%b in ("%%a") do (
        if not "%%b"=="" if not "%%b"=="#" (
            set "%%b=%%c"
        )
    )
)

cd /d "%PROJECT_DIR%"

echo Starting %1 on port %2...
"%JAVA_HOME%\bin\java.exe" ^
    -classpath "%MAVEN_HOME%\boot\plexus-classworlds-2.7.0.jar" ^
    -Dclassworlds.conf="%MAVEN_HOME%\bin\m2.conf" ^
    -Dmaven.home="%MAVEN_HOME%" ^
    -Dmaven.multiModuleProjectDirectory="%PROJECT_DIR%" ^
    org.codehaus.plexus.classworlds.launcher.Launcher ^
    spring-boot:run -pl %3 -am -DskipTests > "%PROJECT_DIR%\logs\%3.log" 2>&1

exit /b %errorlevel%
