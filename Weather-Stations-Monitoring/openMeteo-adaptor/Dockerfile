# weather-station/Dockerfile
FROM openjdk:17-jdk-slim
COPY target/openmeteo-adapter.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]