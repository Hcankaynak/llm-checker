# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN ./gradlew --no-daemon --console=plain dependencies > /dev/null 2>&1 || true
COPY src src
RUN ./gradlew --no-daemon --console=plain buildFatJar -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --create-home llmchecker \
    && mkdir -p /data \
    && chown llmchecker /data
COPY --from=build /src/build/libs/llm-checker.jar /app/llm-checker.jar

ENV PORT=18642 \
    HOST=0.0.0.0 \
    DATA_DIR=/data \
    JAVA_OPTS="-XX:MaxRAMPercentage=60"

USER llmchecker
VOLUME /data
EXPOSE 18642

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${PORT} && printf "GET /api/health HTTP/1.0\r\n\r\n" >&3 && grep -q "\"ok\":true" <&3' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/llm-checker.jar"]
