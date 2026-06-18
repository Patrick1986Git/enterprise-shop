package com.company.shop.module.order.outbox;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventAdminActionLogRepository extends JpaRepository<OutboxEventAdminActionLog, UUID> {

    List<OutboxEventAdminActionLog> findByOutboxEventIdOrderByCreatedAtDesc(UUID outboxEventId);

    Page<OutboxEventAdminActionLog> findByOutboxEventId(UUID outboxEventId, Pageable pageable);
}
