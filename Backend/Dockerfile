# syntax=docker/dockerfile:1

########################################
# Build stage (Debian / Temurin JDK 21)
########################################
FROM eclipse-temurin:21-jdk AS build

WORKDIR /build

# Dependency layer: only re-resolves when the POM changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

# Source layer
COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests \
 && mv target/*.jar /build/app.jar

########################################
# Runtime stage (Debian / Temurin JRE 21)
########################################
FROM eclipse-temurin:21-jre AS runtime

# curl is needed for the container healthcheck; nothing else is added.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Run as an unprivileged, non-login account.
RUN groupadd --system --gid 10001 nimbus \
 && useradd  --system --uid 10001 --gid nimbus --shell /usr/sbin/nologin --no-create-home nimbus

WORKDIR /app
COPY --from=build --chown=root:root --chmod=444 /build/app.jar /app/app.jar

USER 10001:10001
EXPOSE 8080

# Container-aware heap sizing; exit the JVM on OOM instead of limping along.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
