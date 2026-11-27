# Original Docker 29 / BuildKit publisher evidence

`ci1290.tar.gz` contains six unchanged files from Enterprise Shop CI #1290
(run `41065203754`, HEAD `72767623b43d75d08c81debfdd106965556c0d2c`),
artifact `12982729399`, `docker-builder-security-evidence`.
The original artifact ZIP SHA-256 is
`2122691ddce561c2797f5551a14a8af96ccb1fe523f291cdd705851a03b94ae9`.
The fixture wrapper SHA-256 is
`f0a696cf28c94dc1638d6b518d805c50625e1eb64b8a713b5dc51e68054ab210`.

| Original member | SHA-256 |
| --- | --- |
| builder-build.json | `6f6b36adea91086d6f424bf12dcb3a12aff5064bad99b7f2e1119f3c0a0f7afd` |
| builder-image.json | `3525f141ef76f408739fb24feb5381a5b93204c006fdceddf1369cbb14de2ed0` |
| builder-identity.tar | `77551bc6060004b0fdd70ffec3b9aa3867650f4f409ea8ded0915232ddf890c9` |
| app-build.json | `7aedca483d4708f35074e29a70bf27760882c30093a7dcca848a9f656795008f` |
| app-image.json | `f957d676a75102ab99b7cc931143aaf62d3ea76c894335b0a7500db285a31552` |
| app-identity.tar | `3a8d8f07f895be37749610d710fa199cf5658dd4a5f9a29c17fbf1a363fb1d52` |

The BuildKit documents publish OCI descriptors and image digests without
`containerimage.config.digest`. The builder's original index is
`sha256:5092ac198ae7a266d7ed01b31e5d861085ac2f820fd2143a1ee4da7f4bd7124e`,
its selected linux/amd64 manifest is
`sha256:31096cb459b6c0bd39b2a19d3ecf572fc619b003f663d20812c25a846e10a5da`,
and its original configuration is
`sha256:df149d15bf89d247d666287f385251902156ddd8527cb10c780dc32fcd80139c`.
The app's original index is
`sha256:affb44ba30c0d2af18846343f98febe4a3f6574e19627554b6d2564d4bbb15b9`.

Only the outer fixture wrapper is newly assembled. Original JSON and retained
OCI archive bytes are preserved without normalization. These metadata proofs
retain descriptor/manifest/configuration and layer identities; full layer bytes
were checked during original collection and are not included here. Layer-byte
tampering tests additionally use the existing complete Docker identity fixture.
