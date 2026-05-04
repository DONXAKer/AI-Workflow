FROM gradle:8.12-jdk21-alpine AS builder
WORKDIR /app
COPY build.gradle.kts .
COPY settings.gradle.kts .
RUN gradle dependencies --no-daemon || true
COPY src src
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jdk-alpine
RUN apk add --no-cache git nodejs npm
RUN npm install -g @anthropic-ai/claude-code --unsafe-perm 2>&1 | tail -5
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
COPY config config
VOLUME ["/app/.workflow"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
