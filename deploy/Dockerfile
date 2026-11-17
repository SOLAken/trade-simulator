# 通用服务镜像：docker build -f deploy/Dockerfile --build-arg MODULE=trade-service -t tradesim/trade-service .
# 先在宿主机 mvn package（CI 中同理），镜像只做分层打包，不在镜像里跑 Maven。
FROM eclipse-temurin:21-jre AS layers
ARG MODULE
WORKDIR /app
COPY ${MODULE}/target/${MODULE}-0.1.0-SNAPSHOT.jar app.jar
# Spring Boot 分层：依赖（极少变）/ loader / 快照依赖 / 业务代码（常变）分别成层，改代码只推最上层几百 KB
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre
# 需要 home 目录：Sentinel、Nacos 客户端默认把日志/缓存写到 ~/logs、~/nacos
RUN groupadd -r app && useradd -r -m -d /home/app -g app -u 10001 app
WORKDIR /app
COPY --from=layers /app/dependencies/ ./
COPY --from=layers /app/spring-boot-loader/ ./
COPY --from=layers /app/snapshot-dependencies/ ./
COPY --from=layers /app/application/ ./
USER app
# 容器感知：堆 = 容器内存上限的 50%，堆外各区域显式封顶。K8s 部署实测：不封顶时堆外约 400Mi
# （glibc 每线程 malloc arena、代码缓存、Netty 直接内存），堆 70% 的 640Mi 容器启动即 OOMKilled。
# 默认 G1（小堆更省内存）；trade-service 在 K8s 清单里覆盖为分代 ZGC
ENV MALLOC_ARENA_MAX=2
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50 -XX:+UseG1GC -XX:MaxDirectMemorySize=64m -XX:ReservedCodeCacheSize=96m -XX:MaxMetaspaceSize=192m -Xss512k -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
