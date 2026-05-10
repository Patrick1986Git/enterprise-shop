# Release checklist (lightweight, pre-merge)

Use this checklist before merging a larger change into `master`.  
Scope: current repository maturity only (no deployment/release automation assumptions).

## 1) Branch and repository hygiene

- [ ] Branch is up to date with `master` (rebase or merge latest `master` intentionally).
- [ ] `git status` is clean (no uncommitted or unintended files).
- [ ] Commit history is reviewable (no accidental WIP/debug commits).

## 2) Build/runtime baseline (Wrapper + toolchain)

- [ ] Use Maven Wrapper (`./mvnw`), not a random local Maven binary.
- [ ] Java 21 is used locally.
- [ ] Enforcer rules pass (Java/Maven version constraints are validated in `validate` phase).

## 3) Mandatory local verification

Run from repository root:

```bash
./mvnw -B validate
./mvnw clean verify
```

Checklist:

- [ ] `./mvnw -B validate` passes.
- [ ] `./mvnw clean verify` passes.
- [ ] No failing unit/integration tests introduced by the change.

## 4) CI and merge-readiness

- [ ] GitHub Actions checks are green for the branch/PR.
- [ ] Required checks are completed before merge (do not merge on red/pending).

## 5) DB/Flyway safety (only when DB is touched)

If the change affects persistence (`entity`, repository logic, SQL assumptions, constraints):

- [ ] New schema changes are in a new Flyway migration under `src/main/resources/db/migration`.
- [ ] Existing historical migrations were not edited.
- [ ] Entity mapping and migration changes are consistent (no schema drift).
- [ ] `clean verify` covered migration startup path without errors.

If DB is not touched, mark as N/A.

## 6) Security and secrets hygiene

- [ ] No credentials, tokens, API keys, or secrets were committed.
- [ ] No security configuration was weakened unintentionally.
- [ ] Sensitive config values remain externalized (env/secret store), not hardcoded.

## 7) Profile/configuration safety

- [ ] `dev`, `test`, and `prod` profile behavior is still intentional.
- [ ] No accidental production fallback defaults were introduced.
- [ ] Production-required variables remain explicit (no silent insecure fallback).

## 8) API / security / DB docs consistency

If the change modifies API contracts, security behavior, or database/migration behavior:

- [ ] Corresponding docs in `docs/` were updated in the same PR.
- [ ] Error/validation semantics in docs are still accurate.

If not applicable, mark as N/A.

## 9) Observability regression sanity check

- [ ] Request correlation is still present (`requestId` in MDC/log pattern).
- [ ] No obvious logging/traceability regression was introduced by the change.

## 10) Dependency update caution

When PR is Dependabot-driven:

- [ ] Review impact manually (security/runtime/test impact), do not auto-merge blindly.
- [ ] Ensure full CI is green and relevant behavior is verified before merge.

---

## Suggested PR comment snippet

```text
Release checklist: branch synced, workspace clean, validate+verify passed, CI green,
DB/Flyway reviewed (or N/A), secrets check done, profile safety reviewed,
docs updated where required, requestId/MDC check done.
```
