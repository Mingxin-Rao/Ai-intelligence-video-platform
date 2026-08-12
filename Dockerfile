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

# Provided automatically by BuildKit (amd64 / arm64).
ARG TARGETARCH

# FFmpeg and yt-dlp are hard runtime dependencies: GeminiWhisperStrategy shells
# out to ffmpeg/ffprobe, YtDlpUtils to yt-dlp for link import.
#
# Static builds are used instead of `apt install ffmpeg`, which drags in ~450MB
# of codec and development libraries. Only the two binaries the app actually
# invokes are kept — ffplay is discarded. curl stays for the healthcheck.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl xz-utils ca-certificates; \
    case "${TARGETARCH}" in \
      amd64) FF_ARCH=linux64;    YT_BIN=yt-dlp_linux ;; \
      arm64) FF_ARCH=linuxarm64; YT_BIN=yt-dlp_linux_aarch64 ;; \
      *) echo "unsupported TARGETARCH: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    curl -fsSL "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-${FF_ARCH}-gpl.tar.xz" \
        -o /tmp/ffmpeg.tar.xz; \
    tar -xJf /tmp/ffmpeg.tar.xz -C /tmp; \
    mv "/tmp/ffmpeg-master-latest-${FF_ARCH}-gpl/bin/ffmpeg"  /usr/local/bin/; \
    mv "/tmp/ffmpeg-master-latest-${FF_ARCH}-gpl/bin/ffprobe" /usr/local/bin/; \
    chmod +x /usr/local/bin/ffmpeg /usr/local/bin/ffprobe; \
    rm -rf /tmp/ffmpeg*; \
    curl -fsSL "https://github.com/yt-dlp/yt-dlp/releases/latest/download/${YT_BIN}" \
        -o /usr/local/bin/yt-dlp; \
    chmod +x /usr/local/bin/yt-dlp; \
    apt-get purge -y --auto-remove xz-utils; \
    rm -rf /var/lib/apt/lists/*

# Create the unprivileged user BEFORE copying, then set ownership in the COPY
# itself. A separate `chown -R /app` rewrites the whole jar into an extra layer,
# which silently doubled the jar's cost in the image.
RUN useradd -r -u 1001 -m appuser
WORKDIR /app
COPY --from=build --chown=appuser:appuser /build/target/*.jar app.jar
USER appuser

EXPOSE 9090

# MaxRAMPercentage lets the JVM respect the container memory limit instead of
# guessing from host RAM.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
