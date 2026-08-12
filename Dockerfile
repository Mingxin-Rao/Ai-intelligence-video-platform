# =============================================================================
# DoVideoAI backend — multi-stage build
#
# Stage 1 compiles the Spring Boot jar; stage 2 ships only a JRE plus the two
# native tools the app shells out to (FFmpeg for audio extraction, yt-dlp for
# link import). Keeping the Maven toolchain out of the final image cuts its size
# roughly in half and removes the build-time attack surface.
#
# Build from the REPO ROOT (the build context must include server/):
#   docker build -t dovideo-app .
# =============================================================================

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Copy the pom alone first so the dependency layer is cached and only
# re-downloaded when pom.xml actually changes (not on every source edit).
COPY server/pom.xml .
RUN mvn -B -q dependency:go-offline

COPY server/src ./src
# Tests run in CI, not here — keeps image builds fast and reproducible.
RUN mvn -B -q clean package -DskipTests


# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:17-jre

# FFmpeg and yt-dlp are hard runtime dependencies: GeminiWhisperStrategy shells
# out to `ffmpeg` for audio extraction, YtDlpUtils to yt-dlp for link import.
# curl is kept for the container healthcheck.
RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg \
        curl \
        ca-certificates \
    && curl -fsSL https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux \
        -o /usr/local/bin/yt-dlp \
    && chmod +x /usr/local/bin/yt-dlp \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/target/*.jar app.jar

# Run unprivileged — the app only needs to read its jar and write temp files.
RUN useradd -r -u 1001 -m appuser && chown -R appuser:appuser /app
USER appuser

EXPOSE 9090

# MaxRAMPercentage lets the JVM respect the container memory limit instead of
# guessing from host RAM.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
