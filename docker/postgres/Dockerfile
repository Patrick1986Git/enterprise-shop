FROM postgres:18-alpine

LABEL org.opencontainers.image.title="Enterprise Shop PostgreSQL" \
      org.opencontainers.image.description="PostgreSQL 18 image with Polish full-text search dictionary files" \
      org.opencontainers.image.source="https://github.com/Patrick1986Git/enterprise-shop"

ARG TSEARCH_DIR=/usr/local/share/postgresql/tsearch_data

RUN apk upgrade --no-cache c-ares libcrypto3 libssl3 libuuid

# Polish Hunspell dictionary files required by the polish_hunspell dictionary
# created by Flyway migration V5 and mirrored by PostgresContainerSupport tests.
COPY --chmod=0644 tsearch_data/polish.dict tsearch_data/polish.affix tsearch_data/polish.stop ${TSEARCH_DIR}/
