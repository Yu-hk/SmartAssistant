# =============================================================================
# SmartAssistant - 生产环境 Dockerfile
# 基于 Liberica JDK 21（与项目 java.version=21 一致）
# =============================================================================
FROM bellsoft/liberica-openjdk-debian:21-cds

LABEL maintainer="Yu-hk <github.com/Yu-hk>"
LABEL description="SmartAssistant - Multi-Agent AI Conversation Platform"

WORKDIR /app

# 安装网络工具（健康检查 + 等待依赖）
RUN apt-get update && apt-get install -y \
    curl \
    wget \
    && rm -rf /var/lib/apt/lists/*

# 创建日志目录
RUN mkdir -p /app/logs

# 复制 JAR（Maven 构建产物在子模块 target/ 下）
ARG SERVICE_NAME
COPY ${SERVICE_NAME}/target/${SERVICE_NAME}-*.jar /app/app.jar

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=90s --retries=3 \
  CMD wget -qO- http://localhost:${PORT:-8080}/actuator/health || exit 1

# 运行时环境变量（默认值，会被 docker-compose 覆盖）
ENV JWT_SECRET="" \
    POSTGRES_PASSWORD="" \
    REDIS_PASSWORD="" \
    NACOS_PASSWORD="" \
    DEEPSEEK_API_KEY="" \
    JAVA_OPTS="-Dfile.encoding=UTF-8 -Xmx512m -Xms256m"

EXPOSE ${PORT:-8080}

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --server.port=${PORT:-8080}"]
