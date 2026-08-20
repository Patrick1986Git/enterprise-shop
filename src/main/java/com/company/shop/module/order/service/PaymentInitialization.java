package com.company.shop.module.order.service;

import java.math.BigDecimal;
import java.util.UUID;

record PaymentInitialization(UUID orderId, BigDecimal amount, String existingClientSecret) {
    boolean isAttached() { return existingClientSecret != null && !existingClientSecret.isBlank(); }
}
