# Public certificate identity fixtures

The four CA source files are unmodified public upstream files independently downloaded from
`adoptium/jdk21u` commit `85974eed594aa5a9e13a6a20713156ed8d9cd0ed`
(`8395142: CA Enhancements`). They are certificate identity fixtures, not measured
Temurin image contents or security-acceptance evidence. No private key or fabricated
certificate is included. Existing upstream notices and comments are preserved.

Two original disclosure chains were independently downloaded from
`galadran/we-have-a-problem.com` commit
`2086d67fb486982ab58aab2c965855aadf4c4a24`, linked by Adoptium's guidance. Their
source-file SHA-256 and Git blob identities match the previously reviewed
provenance; no #495 mitigation or transformed certificate is transplanted.

`manifest.json` pins each source URL, file size and SHA-256, and identifies each
selected PEM block by index, DER SHA-256, public-key SPKI SHA-256 and certificate
purpose. Its byte hash is pinned by the collector, whose source and the Java probe
are covered by the maintained execution-source census. Tests independently load
all 19 selected certificates with Java 21 and compare DER/SPKI fingerprints and
extended-key usages to the manifest.

| Source | Role |
| --- | --- |
| `comodoaaaca` | AAA Certificate Services root identity, withdrawn by Adoptium's remediation |
| `emsigneccrootcag3` | emSign ECC Root CA - G3 root identity, withdrawn by Adoptium's remediation |
| `emsignrootcag1` | Vendor-permitted G1 positive control; its presence alone is not a failure |
| `blocked.certs.pem` | Complete original CA-enhancement input; 16 eMudhra intermediate identities selected for these tests |

The intermediate selections comprise 12 TLS certificates representing six public
keys, and four code-signing certificates representing two public keys. Their
cross-signed forms are retained separately. Other historical certificates in the
original blocked-certificates source are preserved but are not relabeled as part
of this incident.

The root fixtures establish identities; their presence in an OpenJDK source tree
does not establish their presence or applicability in any Temurin image. The
authority context links Adoptium's official remediation guidance and CA update,
and pins the already reviewed November 17 VDR and both advisory record hashes.
No VDR record is edited or inferred from CVE prose.

The temporary stores created by tests contain these genuine public certificates.
They are explicitly `OFFLINE_FIXTURE` measurements and cannot be replayed as
actual-image evidence. Importing a reviewed certificate into a temporary test
store does not create a new certificate, private key, or attestation.

Java verifies both original chains' signatures and certificate dates at the
explicit historical fixture clock. A different genuine root fails signature
verification. This clock is a test parameter, not a collection timestamp or
attestation. These checks do not exercise an actual TLS handshake, PKIX rejection
in a candidate image, complete chain/encoding coverage, or Java signed-code
validation. No authenticated TLS transcripts or signed adversarial JARs were
available in these reviewed sources. The collector therefore records
TLS-chain behavior, Java signed-code behavior and alternate certificate encoding
coverage as `NOT_TESTED`. Network failures are never treated as rejection results.
No live connectivity test is performed. Both CRITICAL CVEs remain blocking.
