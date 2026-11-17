# Collector context regression input

`temurin-vdr-20261117.json.gz` contains the exact previously verified published
JSON, compressed deterministically with gzip mtime zero. Decompressed SHA-256:

`3787567d614f5dbed896f26c7a1e2d80c00b8b167431ade0c03c95ac5e442852`

Release: `temurin-vdr-17-11-2026-44079996529`; release ID 448589257;
JSON asset ID 758945784. The provenance JSON is the corresponding retained
production fetch receipt. The existing pinned authority manifest independently
checks the whole VDR and exact canonical record hashes for both CRITICAL CVEs.
No fixture hash or production authority is mocked in the positive tests.

Source: https://github.com/adoptium/temurin-vdr-generator/releases/tag/temurin-vdr-17-11-2026-44079996529

Tests combine this input with existing original-byte CI1290 builder/runtime OCI
metadata fixtures and an explicitly controlled source marker. They do not claim
that those historical images came from #509 or that their certificate state was
measured. Collection/replay transport tests mock Docker capture and actual-image
probe validation only; real context, authority, hashing, original OCI validation,
compilation and bundle replay execute. Temporary synthetic transport records are
not qualification evidence. All results remain UNQUALIFIED.

Negative tests clone a controlled authority and recompute its test-only pin to
exercise missing or incorrect advisory-record hashes after whole-file checks.
This does not change the maintained production authority or vulnerability policy.
