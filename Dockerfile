# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x ./mvnw

RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -DskipTests dependency:go-offline

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime

LABEL org.opencontainers.image.title="Enterprise Shop" \
      org.opencontainers.image.description="Spring Boot application for the Enterprise Shop backend" \
      org.opencontainers.image.source="https://github.com/Patrick1986Git/enterprise-shop" \
      org.opencontainers.image.licenses="UNLICENSED"

RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=builder /workspace/target/enterprise-shop-*.jar /app/app.jar

USER app:app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
