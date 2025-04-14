FROM --platform=linux/arm64 eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/autotrader.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
