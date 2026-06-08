ALTER TABLE notifications
    ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_notifications_pending_next_attempt_at
    ON notifications (status, next_attempt_at, created_at);
