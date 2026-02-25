# =============================================================================
# Hazelcast MCP Server — Multi-stage Docker Build
# =============================================================================
# Build:  docker build -t hazelcast-mcp-server .
# Run:    docker run -it hazelcast-mcp-server
# SSE:    docker run -p 3000:3000 -e HAZELCAST_MCP_TRANSPORT=sse hazelcast-mcp-server
# =============================================================================

# --- Stage 1: Build ---
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# Cache Maven dependencies first (layer caching optimization)
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN mvn dependency:go-offline -B 2>/dev/null || true

# Copy source and build fat JAR
COPY src src
RUN mvn clean package -DskipTests -B -q

# --- Stage 2: Runtime ---
FROM eclipse-temurin:17-jre-alpine
LABEL maintainer="bhmortim@gmail.com"
LABEL description="Hazelcast Client MCP Server — AI agent access to Hazelcast clusters"

WORKDIR /app

# Copy fat JAR from build stage
COPY --from=builder /build/target/hazelcast-mcp-server-*-SNAPSHOT.jar hazelcast-mcp-server.jar

# Default config (can be overridden via volume mount or env vars)
COPY docker/hazelcast-mcp-docker.yaml /app/hazelcast-mcp.yaml

# SSE/HTTP port (only used when HAZELCAST_MCP_TRANSPORT=sse)
EXPOSE 3000

# JVM tuning defaults
ENV JAVA_OPTS="-Xmx512m -Xms256m"

# Entrypoint supports overriding the main class for demo-loader
ENV MAIN_CLASS="com.hazelcast.mcp.server.HazelcastMcpServer"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -cp hazelcast-mcp-server.jar $MAIN_CLASS /app/hazelcast-mcp.yaml"]
