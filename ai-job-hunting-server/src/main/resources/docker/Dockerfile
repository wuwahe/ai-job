# Pin the official Java 17 multi-architecture image, including fontconfig and curl.
FROM eclipse-temurin:17-jre-jammy@sha256:b3054a20e1a851574cdb1692dd0db5310c647e62214a2a7abecbec1b9e3ae8f1
WORKDIR /app/

# Build with -Pproduction-image and use ai-job-hunting-server/target as the context.
COPY --chown=10001:10001 ./ai-job-production.jar /app/ai-job.jar
USER 10001:10001

# The reverse proxy reaches the API; SmartConfig is not published by Compose.
EXPOSE 9100

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent --show-error --max-time 4 http://127.0.0.1:9100/actuator/health || exit 1

# Keep JVM options before -jar; require a mounted config instead of bundled credentials.
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-Duser.home=/tmp", "-jar", "/app/ai-job.jar"]
CMD ["--spring.config.location=/app/config/application.properties"]
