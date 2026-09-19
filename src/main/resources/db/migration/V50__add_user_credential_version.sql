ALTER TABLE users
    ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_users_credential_version_non_negative CHECK (credential_version >= 0);
