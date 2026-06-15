# =============================================================================
# api-private-router Multi-Stage Dockerfile
# =============================================================================
# Stage 1: Build frontend
# Stage 2: Build Java backend
# Stage 3: Final runtime image
# =============================================================================

ARG NODE_IMAGE=node:24-alpine
ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-17-alpine
ARG JRE_IMAGE=eclipse-temurin:17-jre-alpine
ARG APP_VERSION=dev

# -----------------------------------------------------------------------------
# Stage 1: Frontend Builder
# -----------------------------------------------------------------------------
FROM ${NODE_IMAGE} AS frontend-builder

WORKDIR /app/frontend

# Install pnpm
RUN corepack enable && corepack prepare pnpm@9.15.0 --activate

# Install dependencies first (better caching)
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

# Copy frontend source and build
COPY frontend/ ./
RUN pnpm run build

# -----------------------------------------------------------------------------
# Stage 2: Java Backend Builder
# -----------------------------------------------------------------------------
FROM ${MAVEN_IMAGE} AS backend-builder
ARG APP_VERSION=dev

WORKDIR /app/java-backend

COPY java-backend/pom.xml ./
COPY java-backend/.mvn/ ./.mvn/
COPY java-backend/mvnw.cmd ./mvnw.cmd
COPY java-backend/src ./src
COPY --from=frontend-builder /app/frontend/dist ../frontend/dist

RUN java -Dmaven.multiModuleProjectDirectory=/app/java-backend \
    -cp .mvn/wrapper/maven-wrapper.jar \
    org.apache.maven.wrapper.MavenWrapperMain -q -DskipTests -Drevision=${APP_VERSION} package

# -----------------------------------------------------------------------------
# Stage 3: Final Runtime Image
# -----------------------------------------------------------------------------
FROM ${JRE_IMAGE}
ARG APP_VERSION=dev

# Labels
LABEL description="api-private-router - Application Runtime"

ENV DATA_DIR=/app/data
ENV APP_BUILD_TYPE=release
ENV APP_VERSION=${APP_VERSION}

# Install runtime dependencies
RUN apk add --no-cache \
    ca-certificates \
    tzdata \
    curl \
    su-exec \
    libpq \
    zstd-libs \
    lz4-libs \
    krb5-libs \
    libldap \
    libedit \
    && rm -rf /var/cache/apk/*

# Create non-root user
RUN addgroup -g 1000 api-private-router && \
    adduser -u 1000 -G api-private-router -s /bin/sh -D api-private-router

# Set working directory
WORKDIR /app

# Copy packaged application
COPY --from=backend-builder --chown=api-private-router:api-private-router /app/java-backend/target/api-private-router.jar /app/api-private-router.jar
COPY --from=frontend-builder --chown=api-private-router:api-private-router /app/frontend/dist /app/frontend/dist

# Create data directory
RUN mkdir -p /app/data && chown api-private-router:api-private-router /app/data

# Copy entrypoint script (fixes volume permissions then drops to api-private-router)
COPY deploy/docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

# Expose port (can be overridden by SERVER_PORT env var)
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
    CMD wget -q -T 5 -O /dev/null http://localhost:${SERVER_PORT:-8080}/health || exit 1

# Run the application (entrypoint fixes /app/data ownership then execs as api-private-router)
ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["java", "-jar", "/app/api-private-router.jar"]
