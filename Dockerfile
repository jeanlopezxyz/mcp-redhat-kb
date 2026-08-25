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
FROM registry.access.redhat.com/ubi9/openjdk-25:1.24 AS build

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

# Build the application, running the unit tests as a gate
RUN ./mvnw package -B

# Stage 2: Runtime
FROM registry.access.redhat.com/ubi9/openjdk-25:1.24

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

# The application defaults to binding 127.0.0.1; inside a container the port must be
# reachable from outside the network namespace, so it is widened here. The access boundary
# is then the pod and its NetworkPolicy - do not publish this port without one.
ENV QUARKUS_HTTP_HOST=0.0.0.0 \
    QUARKUS_HTTP_PORT=9081

ENTRYPOINT ["java", \
    "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", \
    "-jar", "/deployments/quarkus-run.jar"]
