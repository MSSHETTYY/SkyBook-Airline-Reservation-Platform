# syntax=docker/dockerfile:1.7
# One Dockerfile for every Spring Boot service; the module is a build argument. Build from the repo root:
#   docker build -f services/Dockerfile --build-arg SERVICE=booking-service -t skybook-booking-service:dev .

# ---------------------------------------------------------------------------------------------- build
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /src

# Wrapper and POMs first: dependency resolution is cached until a POM changes.
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY config config
COPY api api
COPY libs libs
COPY services services
COPY contracts contracts

ARG SERVICE
# Tests, Checkstyle and coverage ran in CI before the image is built; this stage only packages.
RUN --mount=type=cache,target=/root/.m2 \
    test -n "${SERVICE}" && \
    ./mvnw -B -ntp -pl "services/${SERVICE}" -am package \
      -DskipTests -Dcheckstyle.skip -Djacoco.skip=true

# Split the fat jar into layers (dependencies change rarely, application code often), so image pushes
# and pulls only move the layers that changed.
RUN java -Djarmode=tools -jar "services/${SERVICE}/target/${SERVICE}-"*.jar \
      extract --layers --launcher --destination /layers

# -------------------------------------------------------------------------------------------- runtime
FROM eclipse-temurin:25-jre-noble
ARG SERVICE
LABEL org.opencontainers.image.title="skybook-${SERVICE}" \
      org.opencontainers.image.source="https://github.com/MSSHETTYY/SkyBook-Airline-Reservation-Platform" \
      org.opencontainers.image.licenses="MIT"

RUN groupadd --system --gid 10001 skybook \
 && useradd --system --uid 10001 --gid skybook --no-create-home --shell /usr/sbin/nologin skybook

WORKDIR /app
COPY --from=build --chown=10001:10001 /layers/dependencies/ ./
COPY --from=build --chown=10001:10001 /layers/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /layers/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /layers/application/ ./

# Container-aware heap; crash fast on OOM so the orchestrator restarts a clean JVM.
# /tmp is the only writable path the JVM needs (mount an emptyDir there with a read-only root fs).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -XX:+UseSerialGC -Xss512k" \
    TZ=UTC

USER 10001:10001
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
