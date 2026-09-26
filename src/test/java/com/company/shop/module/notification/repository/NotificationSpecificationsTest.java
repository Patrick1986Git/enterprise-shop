package com.company.shop.module.notification.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.company.shop.module.notification.NotificationAdminSearchCriteria;
import com.company.shop.module.notification.NotificationDeliveryState;

class NotificationSpecificationsTest {

    @Test
    void adminFilters_shouldRejectDeliveryStateWithoutAuthoritativeObservationTime() {
        NotificationAdminSearchCriteria criteria = NotificationAdminSearchCriteria.builder()
                .deliveryState(NotificationDeliveryState.DUE_PENDING)
                .build();

        assertThatThrownBy(() -> NotificationSpecifications.adminFilters(criteria))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Delivery-state filters require an authoritative observation time");
    }
}
