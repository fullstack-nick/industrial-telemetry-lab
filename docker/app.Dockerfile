FROM maven:3.9.16-eclipse-temurin-21 AS build

ARG MODULE
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw mvnw.cmd pom.xml ./
COPY telemetry-contracts/pom.xml telemetry-contracts/pom.xml
COPY controller-simulator/pom.xml controller-simulator/pom.xml
COPY edge-collector/pom.xml edge-collector/pom.xml
COPY telemetry-platform/pom.xml telemetry-platform/pom.xml
RUN ./mvnw --no-transfer-progress -pl "${MODULE}" -am dependency:go-offline

COPY contracts contracts
COPY config config
COPY database database
COPY telemetry-contracts telemetry-contracts
COPY controller-simulator controller-simulator
COPY edge-collector edge-collector
COPY telemetry-platform telemetry-platform
RUN ./mvnw --no-transfer-progress -pl "${MODULE}" -am package -DskipTests

FROM otel/autoinstrumentation-java:2.28.1 AS otel-agent

FROM eclipse-temurin:21.0.8_9-jre-jammy

ARG MODULE
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 telemetry \
    && useradd --uid 10001 --gid telemetry --home-dir /app --shell /usr/sbin/nologin telemetry \
    && mkdir -p /app /data /var/log/telemetry \
    && touch /var/log/telemetry/.volume-owner \
    && chown -R telemetry:telemetry /app /data /var/log/telemetry

WORKDIR /app
COPY --from=otel-agent /javaagent.jar /app/opentelemetry-javaagent.jar
COPY --from=build --chown=telemetry:telemetry /workspace/${MODULE}/target/${MODULE}-1.0.0-SNAPSHOT.jar /app/application.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-jar", "/app/application.jar"]
