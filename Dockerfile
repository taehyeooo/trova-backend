# syntax=docker/dockerfile:1

# --- builder ---
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

COPY gradlew build.gradle settings.gradle ./
COPY gradle gradle
RUN ./gradlew --version

COPY src src
RUN ./gradlew bootJar --no-daemon

# --- runtime ---
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip ffmpeg \
    && pip3 install --no-cache-dir --break-system-packages yt-dlp \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --shell /usr/sbin/nologin trova
COPY --from=builder /build/build/libs/trova-backend-0.0.1-SNAPSHOT.jar app.jar
COPY pipeline-test/download.py pipeline-test/extract_places.py pipeline-test/frames.py pipeline-test/run_pipeline.py pipeline-test/
RUN mkdir -p pipeline-test/work && chown -R trova:trova /app

USER trova
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
