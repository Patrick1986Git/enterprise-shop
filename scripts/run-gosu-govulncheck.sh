#!/usr/bin/env bash
# Run the upstream reachability wrapper against the installed binary's Go version.
set -euo pipefail
evidence="${PWD}/.tmp/container-security/gosu"
mkdir -p "$evidence"
container="$(docker create enterprise-shop/postgres:ci)"
trap 'docker rm -v "$container" >/dev/null' EXIT
docker cp "$container:/usr/local/bin/gosu" "$evidence/gosu"
docker run --rm --platform linux/amd64 --network none \
  -v "$evidence:/evidence:ro" "$GOSU_GOVULNCHECK_IMAGE" \
  go version -m /evidence/gosu | tee "$evidence/buildinfo.txt"
GOSU_BINARY_GO_VERSION="$(sed -n '1s|^/evidence/gosu: \(go[0-9][0-9.]*\)$|\1|p' "$evidence/buildinfo.txt")"
[[ "$GOSU_BINARY_GO_VERSION" =~ ^go[0-9]+\.[0-9]+\.[0-9]+$ ]]
export GOSU_BINARY_GO_VERSION
python scripts/validate-gosu-provenance.py
export GOSU_BINARY_EVIDENCE="$evidence"
govulncheck() {
  local certificate=()
  if [ -n "${AUXILIARY_SCAN_CA_BUNDLE:-}" ]; then
    certificate=(-v "$AUXILIARY_SCAN_CA_BUNDLE:/run/ca.pem:ro" --env SSL_CERT_FILE=/run/ca.pem)
  fi
  docker run --rm --interactive --init --platform linux/amd64 \
    --read-only --cap-drop ALL --security-opt no-new-privileges \
    --user "$(id -u):$(id -g)" --env HOME=/tmp --env GOPATH=/tmp/go \
    --env CGO_ENABLED=0 --env "GOVERSION=$GOSU_BINARY_GO_VERSION" \
    --env GOENV=off --env GOTOOLCHAIN=local --env GOSUMDB=sum.golang.org \
    --env GOPROXY=https://proxy.golang.org \
    --env GOPRIVATE= --env GONOSUMDB= --env GONOPROXY= \
    --tmpfs /tmp:rw,exec,nosuid,nodev,size=1g,mode=1777 \
    -v "$GOSU_BINARY_EVIDENCE:/evidence:ro" "${certificate[@]}" \
    --mount "type=bind,src=$PWD,dst=/wd,ro" --workdir /wd \
    "$GOSU_GOVULNCHECK_IMAGE" sh -euc \
    'go install golang.org/x/vuln/cmd/govulncheck@v1.1.4 >/dev/null; exec "$GOPATH/bin/govulncheck" "$@"' -- "$@"
}
export -f govulncheck
cd .tmp/gosu-source
govulncheck -json ./... > "$evidence/source-govulncheck.json"
./govulncheck-with-excludes.sh ./... | tee "$evidence/govulncheck.txt"
govulncheck -mode=binary -json /evidence/gosu > "$evidence/binary-govulncheck.json"
govulncheck -mode=binary /evidence/gosu | tee "$evidence/binary-govulncheck.txt"
