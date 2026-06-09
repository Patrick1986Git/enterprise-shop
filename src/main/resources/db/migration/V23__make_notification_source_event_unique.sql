CREATE UNIQUE INDEX ux_notifications_source_event_id
    ON notifications (source_event_id)
    WHERE source_event_id IS NOT NULL;
