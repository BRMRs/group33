FROM eclipse-temurin:17-jdk-jammy@sha256:81336a07f69ac361c7f278183b195e32037ebdb532684996806265a3b47ef516 AS build
WORKDIR /work
COPY gradlew ./gradlew
COPY gradle/ ./gradle/
COPY deploy/render/settings.gradle.kts deploy/render/build.gradle.kts ./
COPY server/build.gradle.kts ./server/build.gradle.kts
COPY shared/build.gradle.kts ./shared/build.gradle.kts
COPY server/src/ ./server/src/
COPY shared/src/ ./shared/src/
COPY docs/contracts/examples.json ./docs/contracts/examples.json
COPY deploy/render/RenderConfigWriter.java ./RenderConfigWriter.java
RUN --mount=type=cache,target=/root/.gradle chmod 0555 gradlew && \
    ./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx768m -Dfile.encoding=UTF-8" \
    -Pkotlin.compiler.execution.strategy=in-process :shared:test :server:test :server:bootJar && \
    javac -d bootstrap RenderConfigWriter.java

FROM eclipse-temurin:17-jre-jammy@sha256:c35794d1348777e6bc310b9b7f420098ef8f37e988199549b545fded79a895a2
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /app app
WORKDIR /app
COPY --from=build /work/server/build/libs/server-0.1.0.jar ./server.jar
COPY --from=build /work/bootstrap/ ./bootstrap/
COPY --chmod=0555 deploy/render/start.sh ./start.sh
USER 10001:10001
EXPOSE 10000
ENTRYPOINT ["/app/start.sh"]
