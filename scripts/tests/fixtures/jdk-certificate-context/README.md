# Collector context regression input

`temurin-vdr-20261124.json.gz` contains the current reviewed report, compressed
with gzip mtime zero. The decompressed SHA-256 is
`7029638014454d0362ff5524da94bbf62bd68b540f84c91cc0e5704b582c8b8c`.
GitHub release `456724257` (`temurin-vdr-24-11-2026-45390304790`) contains JSON
asset `787080638` and checksum asset `787080642`. The retained production fetch
receipt is `jdk-advisory-provenance-20261124.json`.

[November 24 upstream release](https://github.com/adoptium/temurin-vdr-generator/releases/tag/temurin-vdr-24-11-2026-45390304790)

`temurin-vdr-20261117.json.gz` contains the exact previously verified published
JSON, compressed deterministically with gzip mtime zero. Decompressed SHA-256:

`3787567d614f5dbed896f26c7a1e2d80c00b8b167431ade0c03c95ac5e442852`

Release: `temurin-vdr-17-11-2026-44079996529`; release ID 448589257;
JSON asset ID 758945784. `jdk-advisory-provenance.json` retains the matching
receipt for replay at `41499ea21b2c33bc79e152c2036be295ae1a27d3`. Current context
and replay reject the November 17 authority.

Source: https://github.com/adoptium/temurin-vdr-generator/releases/tag/temurin-vdr-17-11-2026-44079996529

Tests combine this input with existing original-byte CI1290 builder/runtime OCI
metadata fixtures and an explicitly controlled source marker. They do not claim
that those historical images came from #509 or that their certificate state was
measured. Collection/replay transport tests mock Docker capture and actual-image
probe validation only; real context, authority, hashing, original OCI validation,
compilation and bundle replay execute. Temporary synthetic transport records are
not qualification evidence. All results remain UNQUALIFIED.

Negative tests use the pinned production authority and independently exercise the
record check with altered, missing or duplicated records.
