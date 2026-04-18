# Expects a pre-built jar in backend/target/ (built by Maven before docker build).
# CI builds the jar on a GitHub-hosted runner, then passes it here via --build-arg.
# Local builds: run `mvn package -DskipTests` first, then `docker build`.

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S kinkeeper && adduser -S kinkeeper -G kinkeeper
USER kinkeeper

ARG JAR_FILE=target/kin-keeper-*.jar
COPY ${JAR_FILE} app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
