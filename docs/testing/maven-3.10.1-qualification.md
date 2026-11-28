# Apache Maven 3.10.1 distribution qualification

The 2026-11-16 review starts from protected master `b4f26b2e69759ff94c1b8a174aa494696e263384`. It qualifies the unmodified official Maven binary distribution for the existing Wrapper 3.3.4 `only-script` contract. Application dependencies, plugins, Java 21, PostgreSQL, runtime images and security thresholds retain their existing policies. Historical custom Maven archives are not an input.

## Release and signer authority

Apache's [download page](https://maven.apache.org/download.cgi) identifies 3.10.1 as the current stable release and links its binary ZIP to `https://dlcdn.apache.org/maven/maven-3/3.10.1/binaries/apache-maven-3.10.1-bin.zip`. The [release notes](https://maven.apache.org/docs/3.10.1/release-notes.html) describe the bugfix release, Resolver changes and removal of the JLine terminal stack. Upstream annotated tag object `2e86d9296e06b540a72e50f5311ac19834c9f983` identifies source commit [`3afd8084b5629f80b97750906a719879ed4cd283`](https://github.com/apache/maven/tree/3afd8084b5629f80b97750906a719879ed4cd283). The tag is unsigned; it is not used as a substitute for the release signature.

The managed environment denied CONNECT requests to `downloads.apache.org`. Acquisition instead used the official CDN linked by Apache and the ASF-owned `archive.apache.org/dist/maven/` release archive. Both ZIPs are byte-identical to the exact [Maven Central ZIP](https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.1/apache-maven-3.10.1-bin.zip) selected by the wrapper. The two Apache `.asc`, `.sha512` and `KEYS` copies also agree. No third-party mirror, JAR replacement or repackaging is involved.

GnuPG in an isolated keyring genuinely verified the detached signature over those ZIP bytes, reporting `GOODSIG` and `VALIDSIG` for Tamas Cservenak, fingerprint **`88BE34F94BDB2B5357044E2E3A387D43964143E3`**, signature time 2026-11-11 22:49:43 UTC. Identity was independently established through the [ASF committer fingerprint directory](https://people.apache.org/keys/committer/) entry for `cstamas` and the [Maven team directory](https://maven.apache.org/team.html), which identifies him as a PMC member. His separately acquired [GitHub public key](https://github.com/cstamas.gpg) contains the same full fingerprint. This trust provenance, rather than importing an arbitrary key or setting ownertrust, binds the cryptographically valid signature to the Apache release manager. GnuPG's local ownertrust remains undefined.

| Distribution | Identity |
| --- | --- |
| Previous Maven 3.10.0 ZIP SHA-256 | `1f6d9909266510f039f59aa0e13dcd2c66da85f043e56276e41f2918f8bddaff` |
| Previous Maven 3.10.0 ZIP SHA-512 | `22d31676d5b92ed53308c19ecded3245d0efd0cddfec51ec7bab62f4f13ad70ddddceff8e62940e0e8e2421f3cde8106737f4becec0aa034ffeff2a754e88337` |
| Maven 3.10.1 ZIP size | 9,386,023 bytes |
| Maven 3.10.1 ZIP SHA-256 | `9c1ef9174ef250a5009ed0dec97cbe8699acf7479add4e62bf7b0ea791c0adb5` |
| Maven 3.10.1 ZIP SHA-512 | `7a9950b642448423c71b86f5ca006018af93cece84b81be2463571be530e9200fa4ed8b1f60ff9781007b28dc64a7edba435544c877b3bb9a4a7af7657c39c03` |
| Authoritative distribution POM SHA-256 | `082f1e143269728b02e284a91de7b8686e4c5718a408c71a018449a9fa026300` |

The ZIP SHA-256 and SHA-512 were computed independently. SHA-512 agrees with both Apache checksum files. Archive inspection found 99 entries, no duplicates, unsafe paths, symlinks or CRC failures, and exactly 51 JARs under `boot/` and `lib/`. Launch scripts and `m2.conf` agree with immutable source, allowing the assembly's documented Windows line endings. The wrapper scripts are unchanged.

## Complete bootstrap correspondence and changes

The published [distribution POM](https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.1/apache-maven-3.10.1.pom) agrees with immutable upstream source. Its actual compile/runtime dependency resolution, including classifiers, produces exactly the 51 archive JARs. Every resolved artifact uniquely byte-matches its archive member; there are no extra or missing runtime identities. The maintained [inventory contract](../../.github/security/build-tool-inventory.json) records every path, exact coordinate, authority and SHA-256.

Five JARs lack embedded `pom.properties`: AOP Alliance 1.0, ASM 9.11.1, Guice 5.1.0 **classifier `classes`**, Javax Inject 1, and JSpecify 1.0.1. Their identities come from authoritative resolution plus byte equality, never filename inference. `boot/plexus-classworlds-2.12.1.jar` launches the core realm; the reviewed `bin/m2.conf` loads `lib/*.jar`. Java class-loading evidence from actual Maven execution identifies Guava classes loaded from `lib/guava-33.8.0-jre.jar`; Guava 33.7.1-jre is absent.

Relative to the maintained Maven 3.10.0 distribution, 32 artifacts upgrade, one is added, six are removed, and 18 remain byte-identical: 56 JARs become 51.

| Group/artifact | Old | New |
| --- | --- | --- |
| `org.apache.maven`: `maven-artifact`, `maven-builder-support`, `maven-compat`, `maven-core`, `maven-embedder`, `maven-jline`, `maven-model`, `maven-model-builder`, `maven-plugin-api`, `maven-repository-metadata`, `maven-resolver-provider`, `maven-settings`, `maven-settings-builder`, `maven-slf4j-provider` | 3.10.0 | 3.10.1 |
| `org.apache.maven.resolver`: `maven-resolver-api`, `maven-resolver-connector-basic`, `maven-resolver-impl`, `maven-resolver-named-locks`, `maven-resolver-spi`, `maven-resolver-transport-apache`, `maven-resolver-transport-file`, `maven-resolver-transport-wagon`, `maven-resolver-util` | 2.0.24 | 2.0.25 |
| `com.google.guava:guava` | 33.7.1-jre | 33.8.0-jre |
| `com.google.code.gson:gson` | 2.14.0 | 2.15.0 |
| `com.google.errorprone:error_prone_annotations` | 2.48.0 | 2.50.0 |
| `commons-codec:commons-codec` | 1.22.1 | 1.23.0 |
| `org.eclipse.sisu:org.eclipse.sisu.inject`, `org.eclipse.sisu:org.eclipse.sisu.plexus` | 1.1.0 | 1.1.2 |
| `org.ow2.asm:asm` | 9.10.1 | 9.11.1 |
| `org.slf4j:jcl-over-slf4j`, `org.slf4j:slf4j-api` | 2.0.19 | 2.0.20 |
| `jakarta.annotation:jakarta.annotation-api` | Absent | 1.3.5 |
| `javax.annotation:javax.annotation-api` | 1.3.2 | Removed |
| `org.jline`: `jansi-core`, `jline-native`, `jline-terminal`, `jline-terminal-ffm`, `jline-terminal-jni` | 3.30.17 | Removed |

The Resolver row contains nine artifacts. The aggregate Maven package additionally changes from `org.apache.maven:apache-maven:pom::3.10.0` to `org.apache.maven:apache-maven:pom::3.10.1`. The retained `maven-jline` module is the upstream compatibility shim; the removed `org.jline` libraries are not retained under another inventory name.

The complete component graph changes from 267 to 263 identities. ASM 9.10.1 remains owned outside the distribution, alongside the new bootstrap ASM 9.11.1; upgrading the distribution does not authorize deleting another owner's dependency. Actual `dependency:go-offline` evidence confirms every configured/direct plugin resolution and realm is unchanged. No plugin, processor, provider, booter or agent expectation is edited.

## Fresh advisory review and evidence boundary

On 2026-11-16, complete GitHub reviewed package-advisory responses were acquired for all 51 JAR coordinates and Maven's aggregate package identity. All 52 acquisitions succeeded without omitted pagination; 19 matching package ranges were reviewed. Current versions fall outside every retrieved HIGH/CRITICAL affected range. Full package responses, rather than empty version-filtered responses, establish which advisories were considered.

Google's [CNA record for CVE-2026-102554](https://github.com/CVEProject/cvelistV5/blob/main/cves/2026/102xxx/CVE-2026-102554.json) and [Guava advisory GHSA-xxph-c9ww-hj94](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94) identify affected releases through 33.7.1 and fixed release 33.7.2. The byte-identified 33.8.0-jre distribution artifact is beyond that fix. Guava's JRE/Android suffixes describe the artifact variants; Google's unsuffixed product-version range establishes applicability. This is a dependency-version remediation, without a reachability waiver or severity downgrade.

This review does not replace the repository's complete build-tool CycloneDX, pinned-Trivy raw package readback and HIGH/CRITICAL gate, or successful final-candidate CI/CodeQL. Those controls continue to require fresh successful execution evidence, complete plugin realms, processor/provider/booter/agent paths, and the exact reviewed graph. Docker 29 builder/configuration defects addressed in #504/#509, Adoptium VDR applicability, strict Trivy resource qualification and complete combined-HEAD enforcement remain independent integration requirements. Maven qualification alone does not make #509 merge-ready.
