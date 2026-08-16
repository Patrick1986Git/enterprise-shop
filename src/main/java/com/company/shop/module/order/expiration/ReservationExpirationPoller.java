package com.company.shop.module.order.expiration;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "spring.datasource.url")
public class ReservationExpirationPoller {
    private final ReservationExpirationProcessor processor;
    public ReservationExpirationPoller(ReservationExpirationProcessor processor) { this.processor = processor; }
    @Scheduled(fixedDelayString = "${app.order.reservation-expiration.fixed-delay:PT10S}")
    public void expireDueReservations() { processor.processDueBatch(); }
}
