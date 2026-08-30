package com.company.shop.module.order.expiration;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class ReservationExpirationWorkSortInvalidException extends BusinessException {
    public ReservationExpirationWorkSortInvalidException(String property) {
        super(HttpStatus.BAD_REQUEST, "Unsupported reservation expiration work sort property: " + property,
                "RESERVATION_EXPIRATION_WORK_SORT_INVALID");
    }
}
