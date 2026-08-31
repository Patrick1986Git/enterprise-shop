package com.company.shop.module.order.expiration;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class ReservationExpirationActionLogDateRangeInvalidException extends BusinessException {
    public ReservationExpirationActionLogDateRangeInvalidException() {
        super(HttpStatus.BAD_REQUEST, "RESERVATION_EXPIRATION_ACTION_LOG_DATE_RANGE_INVALID",
                "error.business.order.reservationExpirationActionLogDateRangeInvalid", new Object[0],
                "createdFrom must be before or equal to createdTo.");
    }
}
