# Offline certificate fixtures

These public certificates are test inputs only. Never install the restored roots
or the original vendor blocklist in an application image or deployment trust store.
`provenance.json` pins each retained file and its source identity. No private key
is retained; the runner generates and deletes its own loopback TLS keys.

The two source bundles are under `chains/` at
`galadran/we-have-a-problem.com@2086d67fb486982ab58aab2c965855aadf4c4a24`.
For each bundle, certificate index 1 is the incident intermediate. The official
`BlockedCertsConverter.altEncodings` method at
`adoptium/jdk21u@85974eed594aa5a9e13a6a20713156ed8d9cd0ed` produces the retained
variants 0 and 1; the converter's path/blob/SHA-256 are recorded in
`docker/java-security/provenance.json`. The other certificates in each chain are
unchanged. DER is encoded as PEM with 64-character Base64 lines and LF endings.

- `ecdsa-0.pem` / `ecdsa-1.pem`: original signature and ECDSA s to n-s.
- `rsa-0.pem` / `rsa-1.pem`: outer AlgorithmIdentifier NULL present / absent;
  signed TBSCertificate and signature bytes remain identical.
- Root fixtures: the DER certificates match the official Java 21 cacerts source
  paths/blob identities in the receipt. Their presence in a current vendor trust
  store is not assumed. The RSA root is absent from the measured Temurin base.
- `vendor-blocked.certs`: the exact inherited file from the pinned JDK image,
  used only as an unmitigated control in an isolated, network-disabled container.

For regeneration, verify the pinned source SHA-256 and Git blob values first,
compile the unmodified official converter using the documented `--add-exports`
build-tool option, and invoke its private static `altEncodings(X509Certificate)`
method reflectively on the intermediate. Replace only that intermediate with
returned variants 0/1 and encode the chain as described above. Regeneration is a
reviewed maintenance operation, never a network dependency of regression tests.

The Java harness independently checks signatures, unchanged signed content and
the expected signature/encoding distinction. Its fixed incident date prevents
fixture expiry from masking blocklist behavior. An original-blocklist control
must accept all four chains; the mitigation must reject each specifically as an
untrusted certificate. The maintained four variants are representative regression
coverage, not the earlier 42-variant research suite or proof of all bypass classes.
