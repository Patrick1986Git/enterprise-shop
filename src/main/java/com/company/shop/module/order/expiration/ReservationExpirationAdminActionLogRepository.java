package com.company.shop.module.order.expiration;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ReservationExpirationAdminActionLogRepository
        extends JpaRepository<ReservationExpirationAdminActionLog, UUID>,
        JpaSpecificationExecutor<ReservationExpirationAdminActionLog> {
}
