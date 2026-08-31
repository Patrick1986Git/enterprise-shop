package com.company.shop.module.order.expiration;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class LegacyReservationSortInvalidException extends BusinessException {
    public LegacyReservationSortInvalidException(String property) {
        super(HttpStatus.BAD_REQUEST,
                "Unsupported legacy reservation sort property: " + property,
                "LEGACY_RESERVATION_SORT_INVALID");
    }
}
