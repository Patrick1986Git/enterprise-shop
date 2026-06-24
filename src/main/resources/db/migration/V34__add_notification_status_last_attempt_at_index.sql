CREATE INDEX idx_notifications_status_last_attempt_at
    ON notifications (status, last_attempt_at);
