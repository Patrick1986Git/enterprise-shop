# Release checklist

Use this lightweight checklist before merging larger changes into `master`.

## 1) Repository hygiene

- [ ] Branch is up to date with `master` or intentionally based on the expected target.
- [ ] `git status` is clean except for intended changes before committing.
- [ ] Commit history is reviewable and does not contain accidental debug/WIP commits.

## 2) Toolchain

- [ ] Java 21 is used locally.
- [ ] Maven Wrapper (`./mvnw`) is used for verification.
- [ ] Enforcer/toolchain checks pass in the `validate` phase.

## 3) Mandatory verification

Run from repository root:

```bash
./mvnw -B validate
./mvnw clean verify
```

- [ ] `./mvnw -B validate` passes.
- [ ] `./mvnw clean verify` passes.
- [ ] GitHub Actions checks are green; CI uses `./mvnw -B clean verify`.

## 4) DB/Flyway safety when persistence changes

- [ ] Schema changes are represented by a new migration under `src/main/resources/db/migration`.
- [ ] Historical migrations were not edited.
- [ ] Entity mappings and migrations remain aligned with Hibernate validation.
- [ ] Relevant persistence/migration tests were added or updated.

Mark this section N/A when persistence is not touched.

## 5) Security/configuration/secrets

- [ ] No credentials, tokens, API keys, webhook secrets, or passwords were committed.
- [ ] Authentication/authorization rules were not weakened unintentionally.
- [ ] Production-required values remain externalized and explicit.
- [ ] CSRF/CORS/webhook exposure changes, if any, were intentionally reviewed.

## 6) Docs consistency

If API contracts, security behavior, database/migration behavior, observability, or local workflow changed:

- [ ] The corresponding `docs/` files were updated in the same PR.
- [ ] Endpoint inventories, access rules, migration sequence, and metric names still match code/configuration.

## 7) Observability sanity check

- [ ] `X-Request-Id` response behavior still works.
- [ ] Logs still include `requestId=%X{requestId}`.
- [ ] New metrics, if any, avoid high-cardinality tags and sensitive data.

## Suggested PR comment snippet

```text
Release checklist: Java 21 + Maven Wrapper used, validate+verify passed,
DB/Flyway reviewed (or N/A), security/secrets checked, docs updated where needed,
request-id/observability sanity checked.
```
