# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app

# Cache Maven dependencies trước khi copy source
COPY pom.xml .
RUN mvn dependency:go-offline -B -q

# Copy source và build (bỏ qua tests và Maven-time Liquibase migration)
COPY src ./src
RUN mvn package -DskipTests -Dliquibase.skip=true -B -q

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Tạo non-root user để tăng bảo mật
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Tạo thư mục logs
RUN mkdir -p logs && chown -R appuser:appgroup logs

# Copy jar từ stage build
COPY --from=builder --chown=appuser:appgroup /app/target/*.jar app.jar

USER appuser

EXPOSE 8081

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
