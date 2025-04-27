FROM --platform=linux/arm64 eclipse-temurin:21-jre-alpine

# Install the required tzdata package for Alpine
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Europe/Amsterdam /etc/localtime && \
    echo "Europe/Amsterdam" > /etc/timezone

# Set TZ environment variable
ENV TZ="Europe/Amsterdam"

WORKDIR /app
COPY target/autotrader.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
