ALTER TABLE outbox_events
    ADD COLUMN event_version INTEGER NOT NULL DEFAULT 1;

ALTER TABLE outbox_events
    ADD CONSTRAINT chk_outbox_events_event_version_positive CHECK (event_version >= 1);
