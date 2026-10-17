# Tally server. Build context is the repo root; the Android app is skipped (no SDK here).

# Gradle's daemon is pinned to Java 25 (gradle/gradle-daemon-jvm.properties); bytecode is 17.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
ENV TALLY_SKIP_ANDROID=true
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --version
COPY core ./core
COPY server ./server
RUN ./gradlew --no-daemon :server:installDist -x test

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S tally && adduser -S -G tally tally
WORKDIR /app
COPY --from=build /src/server/build/install/server ./
USER tally
EXPOSE 8080
CMD ["bin/server"]
