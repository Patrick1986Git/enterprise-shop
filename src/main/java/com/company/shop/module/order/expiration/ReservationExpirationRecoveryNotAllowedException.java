package com.company.shop.module.order.expiration;

import org.springframework.http.HttpStatus;
import com.company.shop.common.exception.BusinessException;

public class ReservationExpirationRecoveryNotAllowedException extends BusinessException {
    public ReservationExpirationRecoveryNotAllowedException() {
        super(HttpStatus.CONFLICT, "Only failed reservation expiration work can be recovered.",
                "RESERVATION_EXPIRATION_RECOVERY_NOT_ALLOWED");
    }
}
