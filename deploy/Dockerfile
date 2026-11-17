# 通用服务镜像：docker build -f deploy/Dockerfile --build-arg MODULE=trade-service -t tradesim/trade-service .
# 先在宿主机 mvn package（CI 中同理），镜像只做分层打包，不在镜像里跑 Maven。
FROM eclipse-temurin:21-jre AS layers
ARG MODULE
WORKDIR /app
COPY ${MODULE}/target/${MODULE}-0.1.0-SNAPSHOT.jar app.jar
# Spring Boot 分层：依赖（极少变）/ loader / 快照依赖 / 业务代码（常变）分别成层，改代码只推最上层几百 KB
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre
RUN groupadd -r app && useradd -r -g app -u 10001 app
WORKDIR /app
COPY --from=layers /app/dependencies/ ./
COPY --from=layers /app/spring-boot-loader/ ./
COPY --from=layers /app/snapshot-dependencies/ ./
COPY --from=layers /app/application/ ./
USER app
# 容器感知：堆 = 容器内存上限的 70%（其余给元空间、直接内存、线程栈、Netty）
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseZGC -XX:+ZGenerational -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
