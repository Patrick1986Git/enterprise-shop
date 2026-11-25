# Source-only Go remediation input fixture

`upstream-locks.tar.gz` is a deterministic, gzip-mtime-zero wrapper containing
only unmodified `go.mod` and `go.sum` from reviewed Trivy v0.75.0 commit
`591e9799316a602e703f0b484f6c6d7b234ec8f3`, the original #509 `x-net.patch`, and
the recovered compress-only composition patch. It contains no scan, binary or
runtime evidence. The original codeload archive SHA-256 is
`4a5bb1c16ccc55368843eb95ee9a25519a0bfef26609a8b4295f2060af43077e`.

Fixture SHA-256: `73188cfa9d9442a303b78e5e422dbb591ba06cb5e36508b0b064bc5637908f7a`.
Original go.mod SHA-256:
`f15ca60ee515b9139c61617560fd90a09df60aaeae23961b086d9c4eccdc4aee`.
Original go.sum SHA-256:
`a5bab0c8c4dee3365bb874f845112df45e5114639d62f7d35ecf57eff1fbe251`.

Tests execute real patch application and source-lock validation. Their small
two-lock-file source archive is an explicitly controlled wrapper; its hash pin
is test-only. Maintained patch and resulting-lock expectations remain real.
The recovered x/net and compress composition provides the baseline for the
November 24 additions: containerd v2.4.2 and the OpenTelemetry v1.47.0 fixes.
The tests bind the original composition, require only the reviewed new lock
entries and preserve the x/crypto/ProtonMail package boundary. Negative cases
reject changed original bytes, a rehashed altered patch and admission using old
runtime receipts.

The production candidate manifests bind the independently measured Trivy and
Ryuk builds. Source-lock fixtures exercise only the upstream locks and patch
composition. Production admission requires independent builds, original OCI
bytes, full advisory and compatibility checks, and terminal resource evidence
from the current HEAD.
