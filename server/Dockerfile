# 围猎后端镜像：Gradle 构建 -> Temurin 17 JRE 运行
# 构建目录即本目录（含 settings.gradle.kts），依赖走阿里云镜像，国内构建不卡。

# ---------- 构建阶段 ----------
FROM gradle:8.9-jdk17 AS builder
WORKDIR /app

# 先复制构建脚本与源码（Docker 层缓存友好：脚本不变时依赖层可复用）
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src

# 打可执行 fat jar（跳过测试，测试已在 CI/本机完成）
RUN gradle fatJar --no-daemon

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# 时区改为东八区，日志与消息时间戳可读
ENV TZ=Asia/Shanghai
# 快照数据目录（docker-compose 挂载卷）
ENV WEILIE_DATA_DIR=/app/data

COPY --from=builder /app/build/libs/weilie-server-all.jar app.jar

EXPOSE 8080
VOLUME ["/app/data"]

# 容器内存受限时按百分比限制堆，避免被 OOM Killer 直接杀掉
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
