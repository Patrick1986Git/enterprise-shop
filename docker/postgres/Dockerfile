FROM docker.io/library/golang:1.26.10-alpine3.24@sha256:9ce231b28998b32e116927c61aa5c406183061d4f3f2d61b292c88e7f6bdf7ca AS gosu-builder

ENV CGO_ENABLED=0 \
    GOOS=linux \
    GOARCH=amd64 \
    GOAMD64=v1 \
    GOTOOLCHAIN=local \
    GOENV=off \
    GOSUMDB=sum.golang.org \
    GOPROXY=https://proxy.golang.org \
    GOPRIVATE="" \
    GONOSUMDB="" \
    GONOPROXY=""

ADD --checksum=sha256:33d7537d588ea49458b9509bcf4554bdf5ceacc66da71e5caa1058ea3b689c3b https://codeload.github.com/tianon/gosu/tar.gz/6456aaa0f3c854d199d0f037f068eb97515b7513 /tmp/gosu-source.tar.gz
WORKDIR /usr/src/gosu
ARG TARGETARCH
RUN test "$TARGETARCH" = amd64 \
    && tar -xzf /tmp/gosu-source.tar.gz --strip-components=1 \
    && rm /tmp/gosu-source.tar.gz
COPY gosu/go.mod gosu/go.sum ./

RUN --mount=type=secret,id=proxy_ca \
    set -eu; \
    if [ -f /run/secrets/proxy_ca ]; then export SSL_CERT_FILE=/run/secrets/proxy_ca; fi; \
    test "$(go env GOVERSION)" = go1.26.10; \
    go mod download; \
    go mod verify; \
    mkdir /out; \
    GOCACHE=/tmp/gosu-cache-first go build -mod=readonly -trimpath -buildvcs=false -ldflags='-d -w' -o /out/gosu .; \
    GOCACHE=/tmp/gosu-cache-repeat go build -mod=readonly -trimpath -buildvcs=false -ldflags='-d -w' -o /out/gosu.repeat .; \
    cmp /out/gosu /out/gosu.repeat; \
    printf '%s\n' '077de39980df05ec3897fbadf39a3105ddbd430e8fb714e9eb97b5bc57a8f094  /out/gosu' > /out/gosu.sha256; \
    sha256sum -c /out/gosu.sha256; \
    go version -m /out/gosu

FROM postgres:18-alpine

LABEL org.opencontainers.image.title="Enterprise Shop PostgreSQL" \
      org.opencontainers.image.description="PostgreSQL 18 image with Polish full-text search dictionary files" \
      org.opencontainers.image.source="https://github.com/Patrick1986Git/enterprise-shop"

ARG TSEARCH_DIR=/usr/local/share/postgresql/tsearch_data

RUN --mount=type=secret,id=proxy_ca,target=/etc/ssl/certs/ca-certificates.crt \
    apk upgrade --no-cache brotli-libs c-ares libcrypto3 libssl3 libuuid

COPY --from=gosu-builder --chmod=0755 /out/gosu /usr/local/bin/gosu

# Polish Hunspell dictionary files required by the polish_hunspell dictionary
# created by Flyway migration V5 and mirrored by PostgresContainerSupport tests.
COPY --chmod=0644 tsearch_data/polish.dict tsearch_data/polish.affix tsearch_data/polish.stop ${TSEARCH_DIR}/
