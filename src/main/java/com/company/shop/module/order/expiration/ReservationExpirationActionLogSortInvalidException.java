package com.company.shop.module.order.expiration;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class ReservationExpirationActionLogSortInvalidException extends BusinessException {
    public ReservationExpirationActionLogSortInvalidException(String property) {
        super(HttpStatus.BAD_REQUEST, "RESERVATION_EXPIRATION_ACTION_LOG_SORT_INVALID",
                "error.business.order.reservationExpirationActionLogSortInvalid", new Object[] { property },
                "Unsupported reservation expiration action log sort property: " + property);
    }
}
