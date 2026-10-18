# Hardened multi-stage build for firefly-plaid-connector-2.
#  - Base images pinned by digest (bump deliberately; see README "Security").
#  - Gradle wrapper distribution is verified by distributionSha256Sum in gradle-wrapper.properties.
#  - Runtime is distroless (no shell, no package manager) + a jlink'd minimal JRE, running as non-root (65532).
#  - No secrets are baked in: mount your application.yml at runtime (see docker-compose-*.yml).

# ---- build stage ----
# eclipse-temurin:17.0.20_8-jdk-noble (published 2026-09-16)
FROM eclipse-temurin:17.0.20_8-jdk-noble@sha256:c2c0deda43a14dea9d5c6c5349d22538c385c7d678d03dc6a133fe0656ed0d64 AS build
WORKDIR /src

# Download and verify Gradle first so this layer is cached independently of source changes
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN ./gradlew --no-daemon --version

COPY specs specs
COPY src src
# Tests run in CI / before release; pass --build-arg RUN_TESTS=true to run them here too
ARG RUN_TESTS=false
RUN if [ "$RUN_TESTS" = "true" ]; then ./gradlew --no-daemon test; fi \
 && ./gradlew --no-daemon bootJar -x test \
 && cp build/libs/fireflyPlaidConnector2-*.jar /app.jar

# Minimal Java runtime with only the modules the app needs
RUN jlink \
      --add-modules java.base,java.logging,java.naming,java.desktop,java.management,java.net.http,java.instrument,java.security.jgss,java.sql,java.xml,jdk.crypto.ec,jdk.unsupported,jdk.zipfs \
      --strip-debug --no-man-pages --no-header-files --compress=2 \
      --output /jre

# ---- runtime stage ----
# gcr.io/distroless/java-base-debian12:nonroot
FROM gcr.io/distroless/java-base-debian12:nonroot@sha256:a9930cad62d02853d7f3dede7281c4b916cbf74493c2d8d38564121aad92bf6c
COPY --from=build /jre /opt/java
COPY --from=build /app.jar /opt/app/app.jar
ENV JAVA_HOME=/opt/java \
    PATH=/opt/java/bin:/usr/bin:/bin
WORKDIR /opt/app
USER 65532:65532
ENTRYPOINT ["/opt/java/bin/java", "-XX:MaxRAMPercentage=75", "-Djava.security.egd=file:/dev/urandom", "-jar", "/opt/app/app.jar"]
