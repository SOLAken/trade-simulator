# syntax=docker/dockerfile:1.7
# 纯 Docker 部署用镜像：在镜像内用 Maven 构建，宿主机不需要 JDK / Maven。
#   docker build -f deploy/docker/app.Dockerfile --build-arg MODULE=trade-service -t tradesim/trade-service .
# 7 个服务共用同一个 build 阶段（BuildKit 缓存命中，只构建一次）；~/.m2 用缓存挂载，重复构建不再下载依赖。
# K8s / 本机开发仍使用 deploy/Dockerfile（宿主机先 mvn package，镜像只做分层打包）。

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# Flink 作业提交镜像：jar 打进 Flink 镜像，由 compose 的 flink-submit 一次性容器提交
FROM flink:1.20.0-scala_2.12-java17 AS flink-jobs
COPY --from=build /src/flink-jobs/target/flink-jobs-0.1.0-SNAPSHOT.jar /jobs/flink-jobs.jar
COPY deploy/docker/flink-submit.sh /jobs/submit.sh
ENTRYPOINT ["bash", "/jobs/submit.sh"]

FROM eclipse-temurin:21-jre AS layers
ARG MODULE
WORKDIR /app
COPY --from=build /src/${MODULE}/target/${MODULE}-0.1.0-SNAPSHOT.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre AS service
RUN groupadd -r app && useradd -r -m -d /home/app -g app -u 10001 app
WORKDIR /app
COPY --from=layers /app/dependencies/ ./
COPY --from=layers /app/spring-boot-loader/ ./
COPY --from=layers /app/snapshot-dependencies/ ./
COPY --from=layers /app/application/ ./
USER app
ENV MALLOC_ARENA_MAX=2
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50 -XX:+UseG1GC -XX:MaxDirectMemorySize=64m -XX:ReservedCodeCacheSize=96m -XX:MaxMetaspaceSize=192m -Xss512k -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
