# Java certificate evidence collector

Status: **IMPLEMENTED; OFFLINE TESTED; UNQUALIFIED**. Actual-image measurements
are **NOT YET VERIFIED**. No acceptance mechanism exists in this collector.

The standalone `scripts/collect-jdk-certificate-evidence.py` supplements the
existing builder recorder. It never edits a VDR, changes severity or exempts a
CVE. Its process exit code reports collection/replay success only; every output
retains `qualification: UNQUALIFIED` and incomplete behavioral coverage. It is
not invoked by the production workflow or vulnerability gate. The historical
November 17 replay at Temurin 21.0.13 recorded CRITICAL CVE-2026-114820 and
CRITICAL CVE-2026-91403. The collector now pins the reviewed November 24 report.
Application-image qualification still requires fresh evidence for both stages.

## Execution and source boundary

Use a clean exact reviewed HEAD, Java 21 platform/compiler modules, and original
builder evidence from the same source. The compiler and platform must both be
21; the helper uses source/target 21 against those actual platform modules,
without requiring `javac` inside either image or the host's optional `ct.sym`.
Warnings are errors, the class-file version is 65, and source/class/compiler
receipts are retained. No Maven build or external Java library is needed.

The collector/probe source bytes are included in the execution-source census.
Both `builder` and `app` input records are mandatory. Existing original-byte OCI
and BuildKit verification binds native identity, configuration identity,
Linux/amd64, build target, fresh base resolution and shared JDK base. The exact
reviewed November 24 VDR bytes, canonical production fetch receipt and two
certificate advisory-record hashes must match the authority manifest. A newer
or altered authority requires separate source review. The November 17 inputs
remain reproducible with the previous reviewed source and are rejected by the
current collector. Java4Shell fixed-build qualification is outside this
collector's certificate behavior scope.

Each image is inspected/export-verified again using the existing identity helper,
then addressed by immutable native ID. The probe runs with the image's user,
working directory, environment and reviewed security-relevant JVM options.
Runtime invocation must match the reviewed application entrypoint; the builder
scope corresponds to Java used by the build RUN instructions, not its inherited
interactive entrypoint. A custom Maven JVM/configuration file is unsupported.

Only the compiled probe is mounted, read-only, at `/evidence-probe`. The container
has no network or Docker socket, a read-only rootfs, dropped capabilities,
no-new-privileges, and fixed CPU, memory, PID, time and output limits. Actual
container configuration is checked before execution. A random label and exact
returned CID bind ownership; timeout cleanup removes only a container whose CID,
label and image still match. Failed ownership inspection never authorizes a
foreign deletion. No shared-name selection or prune operation exists.

## What the Java probe measures

The reviewed `CertificateEvidence.java.source` runs as compiled Java 21 class
bytes, including in a JRE-only runtime image. It does not modify image files,
stores or application state. Its host fixture mode is explicitly labeled and
is rejected by actual-image validation.

- Actual Java version, full runtime/build and vendor identity; selected release
  fields; release, executable and security-file hashes; provider order/classes/
  versions; and allowlisted effective Java security properties.
- Default `cacerts` requested/canonical paths, symlinks on the requested path,
  mode/owner, exact public-store bytes, type and SHA-256. JKS and certificate-only
  PKCS12 parsing are supported with the standard public-store password. Private
  key entries, unknown passwords, changing files and oversized data fail.
- Every store certificate parsed through `KeyStore`/`X509Certificate`: exact
  DER, DER/SPKI SHA-256, subject/issuer/serial, validity and extended-key usages.
- Actual default `TrustManagerFactory` and `SSLContext` provider identities.
  The effective accepted-issuer set must equal the selected store inventory.
  The supported provider profile is the standard twelve-provider Java 21
  configuration with SunJSSE/PKIX. Other profiles fail as unsupported.
- Public `blocked.certs` file bytes/hash when present, without claiming that
  inventory proves blocked-certificate or signed-code behavior.

Optional `jssecacerts`, explicit/custom stores, provider overlays, agents,
unreviewed JVM/environment options, system-CA imports and unknown configurations
are rejected rather than treated as an equivalent default JVM. Relevant image
environment and command checks happen before Java can echo injected options.
Raw option values, unfiltered environment, private trust-store passwords and
private keys are not logged. Failure messages withhold command output.

This is **image-default JSSE evidence**, not application/deployment behavior.
Programmatic SSL contexts, mounted stores, external configuration and later
provider changes are outside this supported context. Transitive filesystem-link
and image-file inclusion review still require original image bytes; a metadata
tar alone is not proof of trust-store contents.

## Replay and integrity

A new destination outside the input evidence is required. The bundle retains
original provenance/VDR inputs, fresh original image metadata, source/class/
compiler receipts, both probe snapshots, sanitized owned-container execution
records, and a complete file-size/SHA-256 census. Missing runtime evidence,
symlinks in the bundle, unlisted/changed files, stale authority, conflicting DER,
or changed source/image/configuration/stage bindings fail closed.

Replay requires the externally expected original context. It recompiles current
reviewed probe source independently and checks retained class bytes against it;
it never executes classes supplied by the evidence bundle. Retained public
stores are reparsed by the trusted fresh helper and compared to every recorded
DER/metadata field and effective issuer set. The source HEAD must be exact and
clean for both collection and replay. Self-consistent hashes do not authenticate
who collected evidence; trusted execution provenance and original images remain
necessary for independent qualification. No timestamp or signed attestation is
fabricated by this implementation.

Context regression tests execute the complete `context()` function against the
exact retained published VDR and original two-stage OCI metadata. They assert the
returned source and image/configuration identities, then exercise collection and
CLI replay while mocking only source-cleanliness and actual Docker/probe transport
boundaries. Authority loading, canonical record hashing, original-byte OCI checks,
probe compilation and bundle replay remain real. Negative cases include each
incorrect/missing authorized CVE, wrong VDR identity and stale/malformed provenance.
These tests prevent advisory digest iteration from overwriting the image context.
Observed Temurin identity requires a multi-component Java 21 version; feature-only
advisory bounds remain valid, but bare observed `JAVA_VERSION="21"` fails closed.

## Certificate authority and CVE boundaries

[The fixture provenance](../../scripts/tests/fixtures/jdk-certificates/README.md)
and pinned manifest retain unmodified public upstream source bytes. Two withdrawn
roots (AAA Certificate Services and emSign ECC Root CA - G3), twelve TLS
intermediate identities and four code-signing intermediate identities are
distinguished from vendor-permitted G1. Reintroducing any reviewed withdrawn/
compromised DER or public-key identity into effective trust is a collection
failure. G1 alone is not rejected. No private key is inferred or generated.

[Adoptium's guidance](https://adoptium.net/news/2026/10/temurin-october-2026-emudhra)
and [CA update](https://github.com/adoptium/temurin-build/pull/4587) explain the
Temurin-specific certificate-store remediation. A version string or successful
inventory is not enough to accept CVE-2026-114820. Two independently pinned
original disclosure chains have offline signature/date checks at an explicit
historical fixture clock, not candidate-image TLS rejection tests. Complete
adversarial chain coverage and signed JARs are still missing; those parts are
stopped rather than approximated with synthetic certificates or connectivity tests.

CVE-2026-91403 has a separate CA-enhancement/blocked-certificate boundary.
Recording its relevant TLS and code-signing intermediate identities does not
establish alternate-encoding rejection, Java signed-code validation or complete
path coverage. It remains blocking even if root-removal evidence for 114820 is
later independently established. The collector exposes no way to label either
CVE accepted and rejects fabricated coverage/acceptance fields.

All bundles explicitly retain:

| Coverage | Result |
| --- | --- |
| Compromised TLS-chain behavior | NOT_TESTED |
| Java signed-code behavior | NOT_TESTED |
| Alternate certificate encodings | NOT_TESTED |
| Qualification | UNQUALIFIED |

## Separately authorized actual-image run

After independent source review and authorization, use one genuine fresh
immutable builder/runtime pair with original recorder/BuildKit proof at the same
clean exact HEAD. Do not substitute a mutable tag, copied #495 evidence or the
unavailable historical CI artifact. That artifact never retained store contents.
No genuine candidate images were available for this implementation's tests.

From that reviewed repository root, a future evidence-only operation is:

```bash
python scripts/collect-jdk-certificate-evidence.py \
  --directory .tmp/container-security/builder \
  --output .tmp/jdk-certificate-evidence \
  --expected-source "$(git rev-parse HEAD)"
```

Add `--replay` to verify the existing output against the original input context.
Both commands require the clean exact reviewed HEAD. Actual Docker execution
and original-image file inclusion still need
qualification; offline orchestration mocks do not establish those properties.

Focused tests use genuine public certificates in explicitly temporary fixture
stores and actual Java parsing. Docker lifecycle tests are mocks and never start
containers. Negative tests cover wrong/stale/copied identities, absent proof,
missing runtime, changed stores/DER, overrides, reintroduced certificate material,
false coverage/acceptance claims, untrusted retained classes and owned-CID cleanup.

No workflow or vulnerability-acceptance integration is introduced. Future
#509/#511 combination must regenerate affected source receipts from combined
bytes and reconcile the prior receipt/documentation overlaps. Existing Trivy
resource qualification, full raw vulnerability enforcement and successful
combined-HEAD CI/CodeQL remain separate requirements. All Draft PRs remain
NOT MERGE-READY.
