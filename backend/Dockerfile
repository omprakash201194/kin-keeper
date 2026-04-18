# Expects a pre-built jar in backend/target/ (built by Maven before docker build).
# CI builds the jar on a GitHub-hosted runner, then passes it here via --build-arg.
# Local builds: run `mvn package -DskipTests` first, then `docker build`.
#
# Base image is glibc-based (Jammy) rather than Alpine: the Firebase Admin SDK
# pulls in netty-tcnative-boringssl-static, whose native libs are linked against
# glibc and SIGSEGV on musl/Alpine during TLS handshake.

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN groupadd -r kinkeeper && useradd -r -g kinkeeper kinkeeper
USER kinkeeper

ARG JAR_FILE=target/kin-keeper-*.jar
COPY ${JAR_FILE} app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
