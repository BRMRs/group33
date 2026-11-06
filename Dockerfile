FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY shared shared
COPY server server

RUN chmod +x gradlew \
    && ./gradlew -PserverOnly :server:bootJar --no-daemon \
    && cp "$(find server/build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -n 1)" /tmp/server.jar

FROM eclipse-temurin:17-jre-jammy

RUN groupadd --system app \
    && useradd --system --gid app --home-dir /app app \
    && mkdir -p /app /tmp/group33-preview-photos \
    && chown -R app:app /app /tmp/group33-preview-photos

WORKDIR /app
COPY --from=build --chown=app:app /tmp/server.jar /app/server.jar

USER app

ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:InitialRAMPercentage=20 -XX:MaxRAMPercentage=70 -XX:MaxMetaspaceSize=128m -Djava.security.egd=file:/dev/urandom"

EXPOSE 10000

ENTRYPOINT ["java", "-jar", "/app/server.jar"]
