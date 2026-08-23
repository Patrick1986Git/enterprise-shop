package com.company.shop.module.order.service;

import java.math.BigDecimal;

import com.company.shop.module.order.exception.PaymentAmountInvalidException;

public final class StripeMinorUnitConverter {

    private static final int PLN_FRACTION_DIGITS = 2;

    private StripeMinorUnitConverter() {
    }

    public static long fromPln(BigDecimal amount) {
        if (amount == null) {
            throw new PaymentAmountInvalidException();
        }
        try {
            return amount.movePointRight(PLN_FRACTION_DIGITS).longValueExact();
        } catch (ArithmeticException ex) {
            throw new PaymentAmountInvalidException();
        }
    }
}
