FROM eclipse-temurin:17-jre

WORKDIR /app

# curl 用于容器健康检查，tzdata 保证时区正确
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/*

COPY blog.jar /app/blog.jar

ENV TZ=Asia/Shanghai \
    PORT=8080

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/blog.jar"]
