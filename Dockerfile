# syntax=docker/dockerfile:1

# ── 1) build stage ── Gradle 래퍼로 bootJar만 만든다. 테스트는 CI에서 별도 수행 (-x test).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# ── 2) runtime stage ── JRE + 비루트 사용자. 헬스체크용 curl만 설치.
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system spring \
    && useradd --system --gid spring --home-dir /app spring

COPY --from=build /workspace/build/libs/*.jar app.jar
RUN chown -R spring:spring /app
USER spring

EXPOSE 8092
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
