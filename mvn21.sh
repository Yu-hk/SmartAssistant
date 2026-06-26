#!/usr/bin/env bash
# JDK 21 + Maven 3.9.6 启动包装脚本（Git Bash 下绕过 unix mvnw 的 Windows 路径不兼容问题）
# 用法: bash mvn21.sh <maven-args>   例如: bash mvn21.sh clean test
set -e
JH="$HOME/.jdks/corretto-21"
MVNH="/d/maven/apache-maven-3.9.6"
export JAVA_HOME="$(cygpath -w "$JH")"
exec "$JH/bin/java.exe" \
  -classpath "$(cygpath -w "$MVNH/boot/plexus-classworlds-2.7.0.jar")" \
  "-Dclassworlds.conf=$(cygpath -w "$MVNH/bin/m2.conf")" \
  "-Dmaven.home=$(cygpath -w "$MVNH")" \
  "-Dmaven.multiModuleProjectDirectory=$(cygpath -w "$PWD")" \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@"
