# JWT signing-key rotation

## Model

The application uses one active HMAC signing key and at most one previous verification key.

- New JWTs are always signed with the active key and include its non-secret `kid` header.
- Verification accepts the active `kid` and, when configured, exactly one previous `kid`.
- Unknown key IDs fail authentication.
- Key material remains RFC 4648 Base64 encoding of at least 32 random bytes.
- Key IDs are operator-owned identifiers limited to 1-64 characters from `A-Z`, `a-z`, `0-9`, dot, underscore, and hyphen.
- The previous key is verification-only. It never signs new tokens.
- No refresh-token, session, revocation-list, JWKS, KMS, or database key store is introduced.

JJWT's supported JWS-specific key-locator API selects the verification key from the protected JWT header before claims are returned or the signature is accepted. Authorization still reloads the current account and roles from PostgreSQL on every authenticated request.

## First rollout from no-`kid` tokens

The first deployment must keep the existing active signing-key bytes unchanged.

When no previous key is configured, the new application accepts a legacy JWT with no `kid` using the active key. Old replicas also continue to accept newly issued `kid` tokens because they verify JWS signatures with the same unchanged key.

After every replica runs the new version, wait at least the maximum access-token lifetime (currently one hour) before beginning a signing-key rotation. Once a previous key is configured, no-`kid` fallback is disabled.

## Planned zero-downtime rotation

Assume active key A and new key B.

### Phase 1 — pre-provision B

Deploy to every replica:

- active key ID/secret: A
- previous key ID/secret: B

All replicas continue signing A but can verify A and B.

### Phase 2 — activate B

Roll out:

- active key ID/secret: B
- previous key ID/secret: A

During the rolling deployment, phase-1 replicas sign A and verify A/B while phase-2 replicas sign B and verify B/A.

### Phase 3 — retire A

After every replica is on phase 2 and at least one maximum access-token lifetime has elapsed since the last replica could issue A, remove both previous-key properties and redeploy.

Do not retain old keys beyond the bounded overlap needed for outstanding access tokens and rollout allowance.

## Emergency compromise

A known-compromised key must not remain configured as the previous key merely to preserve sessions. Generate a new key, remove the compromised key from every verification configuration as quickly as the deployment platform permits, and accept that outstanding tokens signed by the compromised key become invalid.

During emergency rollout, availability is secondary to revocation. Minimize mixed-config time and drain or stop old replicas that still possess the compromised key.

## Rollback

Phase 1 and phase 2 are symmetric: both configurations know A and B, so rolling back phase 2 to phase 1 remains mutually verifiable.

After phase 3 retirement, do not roll back to configuration that can sign only with the retired key.

## Observability

Key IDs are non-secret operator-owned metadata and are visible in JWT headers. Do not log full JWTs or any signing-key material.

The application intentionally does not create metrics or log tags from arbitrary incoming `kid` values because those values are attacker-controlled and would create a cardinality surface. Deployment operators should compare the configured active/previous key IDs across replicas when diagnosing rollout drift.

## Production configuration

Required active properties:

- `JWT_KEY_ID`
- `JWT_SECRET`

Optional rollover properties, configured together:

- `JWT_PREVIOUS_KEY_ID`
- `JWT_PREVIOUS_SECRET`

Duplicate active/previous IDs, invalid IDs, malformed Base64, weak key material, or only one previous-key property cause startup failure without logging secret material.
