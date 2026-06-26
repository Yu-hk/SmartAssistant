$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
$env:MAVEN_HOME = "C:\Users\于海阔\.m2\wrapper\dists\apache-maven-3.9.6\8b309f6e91bd096261e2d839c34abe89ef71f3e5075460adad8cc51b22413c3e"
$env:Path = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:Path"
Set-Location "D:\workspace\SmartAssistant"

# Load .env file
Get-Content "D:\workspace\SmartAssistant\.env" | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), "Process")
    }
}

$mvnArgs = @(
    "-classpath", "$env:MAVEN_HOME\boot\plexus-classworlds-2.7.0.jar"
    "-Dclassworlds.conf=$env:MAVEN_HOME\bin\m2.conf"
    "-Dmaven.home=$env:MAVEN_HOME"
    "-Dmaven.multiModuleProjectDirectory=D:\workspace\SmartAssistant"
    "org.codehaus.plexus.classworlds.launcher.Launcher"
)

# Combine Maven args with additional args
if ($args.Count -gt 0) {
    $mvnArgs += $args
}

$result = & "$env:JAVA_HOME\bin\java.exe" $mvnArgs 2>&1
$result | Out-String | Write-Host
exit $LASTEXITCODE
