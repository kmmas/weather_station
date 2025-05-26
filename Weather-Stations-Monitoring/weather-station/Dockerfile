# weather-station/Dockerfile
FROM openjdk:17-jdk-slim
COPY target/weather-station.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]