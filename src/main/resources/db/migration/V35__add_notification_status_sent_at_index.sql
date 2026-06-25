CREATE INDEX idx_notifications_status_sent_at
    ON notifications (status, sent_at);
