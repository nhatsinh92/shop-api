# syntax=docker/dockerfile:1.7
# Builds one Spring Boot app of the Gradle multi-project. Used by infra/docker-compose.yml so
# `make demo` needs only Docker on the host (no local JDK).
#   docker build -f infra/app.Dockerfile --build-arg MODULE=core .

FROM eclipse-temurin:21-jdk-noble AS build
ARG MODULE
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY config ./config
COPY gateway/build.gradle.kts gateway/gradle.lockfile ./gateway/
COPY core/build.gradle.kts core/gradle.lockfile ./core/
COPY cart-service/build.gradle.kts cart-service/gradle.lockfile ./cart-service/
RUN --mount=type=cache,target=/root/.gradle,sharing=locked ./gradlew --no-daemon -q :${MODULE}:dependencies > /dev/null
COPY ${MODULE}/src ./${MODULE}/src
RUN --mount=type=cache,target=/root/.gradle,sharing=locked ./gradlew --no-daemon -q :${MODULE}:bootJar \
    && find ${MODULE}/build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} /app.jar \;

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /app.jar app.jar
# Heap and metaspace are capped by JAVA_TOOL_OPTIONS in docker-compose.yml.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
