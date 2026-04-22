FROM eclipse-temurin:21-jre-jammy

# Install GStreamer and required plugins
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
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY target/javartc-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "--add-opens=java.base/java.lang=ALL-UNNAMED", \
  "--add-opens=java.base/java.util=ALL-UNNAMED", \
  "-Dorg.ice4j.ice.harvest.DISABLE_AWS_HARVESTER=true", \
  "-jar", "app.jar"]
