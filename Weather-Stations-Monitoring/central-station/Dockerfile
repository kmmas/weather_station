FROM eclipse-temurin:17-jdk-jammy

WORKDIR /app

# Copy the built JAR file (fixed path to match CMD)
COPY ../target/central-station.jar /app/app.jar

# Install curl for health checks (with cleanup to reduce image size)
RUN apt-get update && \
    apt-get install -y curl && \
    rm -rf /var/lib/apt/lists/*

# Environment variables (can be overridden)
ENV BITCASK_DATA_PATH=/data/bitcask
ENV PARQUET_DATA_PATH=/data/parquet
ENV ELASTICSEARCH_HOST=elasticsearch
ENV ELASTICSEARCH_PORT=9200
ENV KAFKA_BOOTSTRAP_SERVERS=kafka-service:9092
ENV JAVA_OPTS=""

# Create data directories (with correct permissions)
RUN mkdir -p ${BITCASK_DATA_PATH} ${PARQUET_DATA_PATH} && \
    chmod -R 777 /data  # Ensure write permissions in Kubernetes

# Health check (using the actual endpoint)
HEALTHCHECK --interval=30s --timeout=3s \
  CMD curl -f http://localhost:8080/q/health/live || exit 1

# Use ENTRYPOINT + CMD for better Java argument handling
ENTRYPOINT ["java"]
CMD ["-XX:+FlightRecorder", \
     "-XX:StartFlightRecording=filename=/data/central-station.jfr,dumponexit=true,settings=profile", \
     "-jar", "/app/app.jar"]