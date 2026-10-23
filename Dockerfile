# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace

RUN apt-get update \
    && apt-get install --no-install-recommends -y unzip libssl3 \
    && rm -rf /var/lib/apt/lists/*

COPY --chmod=0444 docker/java-security/blocked.certs /tmp/enterprise-shop-blocked.certs
COPY --chmod=0555 docker/java-security/with-verified-blocklist.sh /usr/local/bin/with-verified-blocklist
RUN test "$(sha256sum /opt/java/openjdk/lib/security/blocked.certs)" = '96572f243f31c2ef81a6e627542e596f6a9295cff3c7ae095c1b595cb1457ded  /opt/java/openjdk/lib/security/blocked.certs' \
    && cp /tmp/enterprise-shop-blocked.certs /opt/java/openjdk/lib/security/blocked.certs \
    && chmod 0444 /opt/java/openjdk/lib/security/blocked.certs \
    && rm /tmp/enterprise-shop-blocked.certs \
    && /usr/local/bin/with-verified-blocklist /opt/java/openjdk/lib/security/blocked.certs true

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x ./mvnw

RUN --mount=type=cache,target=/root/.m2 /usr/local/bin/with-verified-blocklist /opt/java/openjdk/lib/security/blocked.certs ./mvnw -B -DskipTests dependency:go-offline

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 /usr/local/bin/with-verified-blocklist /opt/java/openjdk/lib/security/blocked.certs ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime

COPY --chmod=0444 docker/java-security/blocked.certs /tmp/enterprise-shop-blocked.certs
COPY --chmod=0555 docker/java-security/with-verified-blocklist.sh /usr/local/bin/with-verified-blocklist
RUN test "$(sha256sum /opt/java/openjdk/lib/security/blocked.certs)" = '96572f243f31c2ef81a6e627542e596f6a9295cff3c7ae095c1b595cb1457ded  /opt/java/openjdk/lib/security/blocked.certs' \
    && cp /tmp/enterprise-shop-blocked.certs /opt/java/openjdk/lib/security/blocked.certs \
    && chmod 0444 /opt/java/openjdk/lib/security/blocked.certs \
    && rm /tmp/enterprise-shop-blocked.certs \
    && /usr/local/bin/with-verified-blocklist /opt/java/openjdk/lib/security/blocked.certs true

LABEL org.opencontainers.image.title="Enterprise Shop" \
      org.opencontainers.image.description="Spring Boot application for the Enterprise Shop backend" \
      org.opencontainers.image.source="https://github.com/Patrick1986Git/enterprise-shop" \
      org.opencontainers.image.licenses="UNLICENSED"

RUN apt-get update \
    && apt-get install --no-install-recommends -y curl libssl3 openssl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=builder /workspace/target/enterprise-shop-*.jar /app/app.jar

USER app:app
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/with-verified-blocklist", "/opt/java/openjdk/lib/security/blocked.certs", "/opt/java/openjdk/bin/java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
