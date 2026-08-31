package com.company.shop.module.order.expiration;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class LegacyReservationAdoptionNotAllowedException extends BusinessException {
    public LegacyReservationAdoptionNotAllowedException(UUID orderId, String reason) {
        super(HttpStatus.CONFLICT, "LEGACY_RESERVATION_ADOPTION_NOT_ALLOWED",
                "error.business.order.legacyReservationAdoptionNotAllowed", new Object[] { orderId, reason },
                "Legacy reservation adoption is not allowed for order " + orderId + ": " + reason);
    }
}
