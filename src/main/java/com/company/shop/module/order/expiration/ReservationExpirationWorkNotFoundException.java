package com.company.shop.module.order.expiration;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import com.company.shop.common.exception.BusinessException;

public class ReservationExpirationWorkNotFoundException extends BusinessException {
    public ReservationExpirationWorkNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "Reservation expiration work not found: " + id,
                "RESERVATION_EXPIRATION_WORK_NOT_FOUND");
    }
}
