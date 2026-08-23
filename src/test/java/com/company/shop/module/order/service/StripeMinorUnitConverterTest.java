package com.company.shop.module.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.company.shop.module.order.exception.PaymentAmountInvalidException;

class StripeMinorUnitConverterTest {

    @Test
    void fromPln_shouldConvertExactlyRepresentableAmounts() {
        assertThat(StripeMinorUnitConverter.fromPln(new BigDecimal("19.99"))).isEqualTo(1999L);
        assertThat(StripeMinorUnitConverter.fromPln(new BigDecimal("19"))).isEqualTo(1900L);
        assertThat(StripeMinorUnitConverter.fromPln(new BigDecimal("19.90"))).isEqualTo(1990L);
    }

    @Test
    void fromPln_shouldRejectFractionalGrosz() {
        assertThatThrownBy(() -> StripeMinorUnitConverter.fromPln(new BigDecimal("19.999")))
                .isInstanceOf(PaymentAmountInvalidException.class);
    }

    @Test
    void fromPln_shouldRejectLongOverflow() {
        assertThatThrownBy(() -> StripeMinorUnitConverter.fromPln(new BigDecimal("92233720368547758.08")))
                .isInstanceOf(PaymentAmountInvalidException.class);
    }

}
