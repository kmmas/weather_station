# rain-detector/Dockerfile
FROM openjdk:17-jdk-slim
COPY target/rain-detector.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]