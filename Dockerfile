# Platform-agnostic: the target platform is chosen at build time (native by default, or via
# DOCKER_DEFAULT_PLATFORM / docker buildx --platform), so the same file builds on amd64 and arm64.
FROM eclipse-temurin:25-jre-alpine

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
