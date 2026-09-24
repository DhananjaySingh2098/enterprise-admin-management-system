# syntax=docker/dockerfile:1.7
# Enterprise Admin backend — production image.
#   build:   docker build -t enterprise-admin-backend ./backend
#   run:     see docker-compose.prod.yml (secrets are injected at runtime, never baked in)
# Tests are not run here: they need Docker (Testcontainers) and run in CI (.github/workflows/ci.yml).

# ---------------------------------------------------------------- build
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Dependencies first, so source changes do not invalidate the (large) dependency layer.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q dependency:go-offline

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q -DskipTests package \
 && java -Djarmode=tools -jar target/enterprise-admin-backend-*.jar extract --layers --application-filename app.jar --destination /extracted

# ---------------------------------------------------------------- runtime
FROM eclipse-temurin:21-jre-alpine AS runtime

LABEL org.opencontainers.image.title="enterprise-admin-backend" \
      org.opencontainers.image.description="Enterprise Admin Management System — Spring Boot API" \
      org.opencontainers.image.licenses="Proprietary"

# Fixed, unprivileged identity (no shell login, no home directory contents).
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app -s /sbin/nologin app
WORKDIR /app

# Layer order: least to most frequently changing.
COPY --from=build --chown=app:app /extracted/dependencies/ ./
COPY --from=build --chown=app:app /extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /extracted/application/ ./

USER 10001:10001

# Container-aware JVM defaults: heap sized from the container memory limit (no hard-coded -Xmx), fail fast on OOM.
# Override or extend with JDK_JAVA_OPTIONS / JAVA_TOOL_OPTIONS at runtime (see docs/DEPLOYMENT.md).
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom" \
    SERVER_PORT=8080

EXPOSE 8080

# Liveness only: /api/health never touches the database and returns no secrets.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/api/health || exit 1

# Exec form: java is PID 1 and receives SIGTERM for Spring's graceful shutdown.
# Migration job: docker run … java -cp /app/app.jar com.enterprise.admin.migration.MigrationMain
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
