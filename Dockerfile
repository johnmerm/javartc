FROM eclipse-temurin:21-jdk-jammy AS builder

# Install Maven and the OpenCV Java bindings (JAR + native .so needed at build time).
# On Ubuntu 22.04 (jammy) the packages are libopencv4.5-java (JAR) and libopencv4.5d-jni (native .so).
RUN apt-get update && apt-get install -y --no-install-recommends \
    maven \
    libopencv4.5-java \
    libopencv4.5d-jni \
    && rm -rf /var/lib/apt/lists/*

# Register the system OpenCV JAR in the local Maven repo so the opencv profile can find it.
# libopencv4.5-java installs the JAR at /usr/share/java/opencv-454.jar (version 4.5.4).
# We hard-code version 4.5.4 to match Ubuntu 22.04 (jammy); pom.xml uses the same version.

WORKDIR /build

# Copy pom.xml first and pre-fetch dependencies — this layer is cached unless pom.xml changes.
# install:install-file runs first (inside the cache mount) so opencv-java is visible to mvn package.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn install:install-file \
      -Dfile=$(find /usr/share/java -name "opencv*.jar" | head -1) \
      -DgroupId=org.opencv \
      -DartifactId=opencv-java \
      -Dversion=4.5.4 \
      -Dpackaging=jar \
      -q && \
    mvn dependency:go-offline -Popencv -q 2>/dev/null || true

# Copy sources and build the fat JAR with OpenCV bundled (-Popencv activates the profile).
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -Popencv -q

# ── Runtime image ──────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

# GStreamer plugins + OpenCV native library (.so provided by libopencv4.5d-jni)
RUN apt-get update && apt-get install -y --no-install-recommends \
    gstreamer1.0-tools \
    gstreamer1.0-plugins-base \
    gstreamer1.0-plugins-good \
    gstreamer1.0-plugins-bad \
    gstreamer1.0-plugins-ugly \
    gstreamer1.0-libav \
    gstreamer1.0-nice \
    libxtst6 \
    libxrender1 \
    libxi6 \
    fonts-dejavu-core \
    libopencv4.5-java \
    libopencv4.5d-jni \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=builder /build/target/javartc-*.jar app.jar

EXPOSE 8088

ENTRYPOINT ["java", \
  "--add-opens=java.base/java.lang=ALL-UNNAMED", \
  "--add-opens=java.base/java.util=ALL-UNNAMED", \
  "-Djava.library.path=/usr/lib/jni", \
  "-Dorg.ice4j.ice.harvest.DISABLE_AWS_HARVESTER=true", \
  "-jar", "app.jar"]
