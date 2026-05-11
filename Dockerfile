FROM eclipse-temurin:17-jre-alpine
LABEL maintainer="Yu-hk <github.com/Yu-hk>"
LABEL description="SmartAssistant - Multi-Agent AI Conversation Platform"

WORKDIR /app

# 创建日志目录
RUN mkdir -p /app/logs

# 复制 JAR（构建时由 CI/CD 传入）
ARG SERVICE_NAME
COPY target/${SERVICE_NAME}-*.jar /app/app.jar

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:${PORT:-8080}/actuator/health || exit 1

# 运行时必须通过环境变量注入密码
ENV JWT_SECRET="" \
    POSTGRES_PASSWORD="" \
    REDIS_PASSWORD="" \
    NACOS_PASSWORD="" \
    DEEPSEEK_API_KEY="" \
    DASHSCOPE_API_KEY="" \
    JAVA_OPTS="-Dfile.encoding=UTF-8 -Xmx512m -Xms256m"

EXPOSE ${PORT:-8080}

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --server.port=${PORT:-8080}"]
