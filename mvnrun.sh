#!/usr/bin/env bash
# classworlds direct-launch wrapper for SmartAssistant (Git Bash / Windows)
MVNH=/d/maven/apache-maven-3.9.12
JH="/c/Program Files/Java/jdk-21"
REPO="/c/Users/于海阔/.m2/repository"
SETTINGS="/c/Users/于海阔/.m2/settings.xml"
export JAVA_HOME="$(cygpath -w "$JH")"
exec "$(cygpath -w "$JH")/bin/java.exe" \
  -classpath "$(cygpath -w "$MVNH/boot/plexus-classworlds-2.9.0.jar")" \
  "-Dclassworlds.conf=$(cygpath -w "$MVNH/bin/m2.conf")" \
  "-Dmaven.home=$(cygpath -w "$MVNH")" \
  "-Dmaven.multiModuleProjectDirectory=$(cygpath -w "$PWD")" \
  -Dmaven.repo.local="$(cygpath -w "$REPO")" \
  org.codehaus.plexus.classworlds.launcher.Launcher \
  -s "$(cygpath -w "$SETTINGS")" "$@"
