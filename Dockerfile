# ---- etapa 1: build com Maven e JDK 21 (só existe durante o build) ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
# dependências primeiro, para o cache de camadas aproveitar quando só o código muda
COPY pom.xml .
RUN mvn -B -ntp -q dependency:go-offline
COPY src ./src
RUN mvn -B -ntp -q package -DskipTests

# ---- etapa 2: extrai as camadas do jar (dependências, snapshots, recursos, aplicação) ----
FROM eclipse-temurin:21-jre-alpine AS camadas
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# ---- etapa 3: imagem final enxuta, usuário sem privilégio, camadas na ordem de menor para maior mudança ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app app && apk add --no-cache curl
WORKDIR /app
COPY --from=camadas /app/dependencies/ ./
COPY --from=camadas /app/spring-boot-loader/ ./
COPY --from=camadas /app/snapshot-dependencies/ ./
COPY --from=camadas /app/application/ ./
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -Djava.security.egd=file:/dev/./urandom"
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
