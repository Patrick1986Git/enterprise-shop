package com.company.shop.module.order.outbox;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OutboxEventAdminActionLogRepository extends JpaRepository<OutboxEventAdminActionLog, UUID>,
        JpaSpecificationExecutor<OutboxEventAdminActionLog> {

    List<OutboxEventAdminActionLog> findByOutboxEventIdOrderByCreatedAtDesc(UUID outboxEventId);

    Page<OutboxEventAdminActionLog> findByOutboxEventId(UUID outboxEventId, Pageable pageable);
}
