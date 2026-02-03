# =============================================================================
# MCP Red Hat Knowledge Base Server - JVM Multi-stage Build
# =============================================================================
# Full SSL/TLS support using JVM mode with multi-stage build
#
# Build:
#   docker build -t mcp-redhat-kb .
#
# Run:
#   docker run -i --rm -p 9081:9081 -e REDHAT_TOKEN=xxx mcp-redhat-kb
# =============================================================================

# Stage 1: Build
FROM registry.access.redhat.com/ubi9/openjdk-21:1.21 AS build

USER root
RUN microdnf install -y gzip tar && microdnf clean all
USER 185

WORKDIR /build

# Copy Maven wrapper and pom.xml first (for layer caching)
COPY --chown=185 mvnw .
COPY --chown=185 .mvn .mvn
COPY --chown=185 pom.xml .

# Download dependencies (cached if pom.xml unchanged)
RUN ./mvnw dependency:go-offline -B

# Copy source code
COPY --chown=185 src src

# Build the application
RUN ./mvnw package -DskipTests -B

# Stage 2: Runtime
FROM registry.access.redhat.com/ubi9/openjdk-21:1.21

LABEL io.modelcontextprotocol.server.name="io.github.jeanlopezxyz/mcp-redhat-kb"
LABEL io.k8s.display-name="MCP Red Hat Knowledge Base Server"
LABEL io.openshift.tags="mcp,redhat,knowledge-base,kb,quarkus"
LABEL maintainer="Jean Lopez"
LABEL description="MCP Server for Red Hat Knowledge Base (JVM)"

# Copy the built application from build stage
COPY --from=build --chown=185 /build/target/quarkus-app/lib/ /deployments/lib/
COPY --from=build --chown=185 /build/target/quarkus-app/*.jar /deployments/
COPY --from=build --chown=185 /build/target/quarkus-app/app/ /deployments/app/
COPY --from=build --chown=185 /build/target/quarkus-app/quarkus/ /deployments/quarkus/

EXPOSE 9081

USER 185

ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Dquarkus.http.port=9081 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV JAVA_APP_JAR="/deployments/quarkus-run.jar"

ENTRYPOINT ["java", "-jar", "/deployments/quarkus-run.jar"]
