#!/usr/bin/env bash
# Run the upstream reachability wrapper against the installed binary's Go version.
set -euo pipefail
evidence="${PWD}/.tmp/container-security/gosu"
mkdir -p "$evidence"
container="$(docker create enterprise-shop/postgres:ci)"
trap 'docker rm "$container" >/dev/null' EXIT
docker cp "$container:/usr/local/bin/gosu" "$evidence/gosu"
docker run --rm --platform linux/amd64 --network none \
  -v "$evidence:/evidence:ro" "$GOSU_GOVULNCHECK_IMAGE" \
  go version -m /evidence/gosu | tee "$evidence/buildinfo.txt"
GOSU_BINARY_GO_VERSION="$(sed -n '1s|^/evidence/gosu: \(go[0-9][0-9.]*\)$|\1|p' "$evidence/buildinfo.txt")"
[[ "$GOSU_BINARY_GO_VERSION" =~ ^go[0-9]+\.[0-9]+\.[0-9]+$ ]]
export GOSU_BINARY_GO_VERSION
govulncheck() {
  docker run --rm --interactive --init --platform linux/amd64 \
    --user "$(id -u):$(id -g)" --env HOME=/tmp --env GOPATH=/tmp/go \
    --env CGO_ENABLED=0 --env "GOVERSION=$GOSU_BINARY_GO_VERSION" \
    --volume govulncheck:/tmp --mount "type=bind,src=$PWD,dst=/wd,ro" --workdir /wd \
    "$GOSU_GOVULNCHECK_IMAGE" sh -euc \
    'go install golang.org/x/vuln/cmd/govulncheck@v1.1.4 >/dev/null; exec "$GOPATH/bin/govulncheck" "$@"' -- "$@"
}
export -f govulncheck
cd .tmp/gosu-source
./govulncheck-with-excludes.sh ./... | tee "$evidence/govulncheck.txt"
