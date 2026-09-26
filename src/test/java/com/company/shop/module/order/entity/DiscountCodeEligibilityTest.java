package com.company.shop.module.order.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DiscountCodeEligibilityTest {

    private static final LocalDateTime VALID_FROM = LocalDateTime.of(2026, 10, 25, 1, 0);
    private static final LocalDateTime VALID_TO = LocalDateTime.of(2026, 10, 25, 2, 0);

    @Test
    void canBeUsed_shouldRejectImmediatelyBeforeValidFrom() {
        assertThat(discount(true, null, 0).canBeUsed(VALID_FROM.minusNanos(1))).isFalse();
    }

    @Test
    void canBeUsed_shouldAcceptExactlyAtValidFrom() {
        assertThat(discount(true, null, 0).canBeUsed(VALID_FROM)).isTrue();
    }

    @Test
    void canBeUsed_shouldAcceptInsideValidityWindow() {
        assertThat(discount(true, null, 0).canBeUsed(VALID_FROM.plusMinutes(30))).isTrue();
    }

    @Test
    void canBeUsed_shouldAcceptExactlyAtValidTo() {
        assertThat(discount(true, null, 0).canBeUsed(VALID_TO)).isTrue();
    }

    @Test
    void canBeUsed_shouldRejectImmediatelyAfterValidTo() {
        assertThat(discount(true, null, 0).canBeUsed(VALID_TO.plusNanos(1))).isFalse();
    }

    @Test
    void canBeUsed_shouldRejectInactiveCode() {
        assertThat(discount(false, null, 0).canBeUsed(VALID_FROM.plusMinutes(30))).isFalse();
    }

    @Test
    void canBeUsed_shouldRejectExhaustedUsageLimit() {
        assertThat(discount(true, 2, 2).canBeUsed(VALID_FROM.plusMinutes(30))).isFalse();
    }

    @Test
    void canBeUsed_shouldNotDependOnJvmDefaultTimeZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            DiscountCode discountCode = discount(true, null, 0);
            LocalDateTime evaluationTime = VALID_FROM.plusMinutes(30);

            TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Pacific/Kiritimati")));
            boolean easternResult = discountCode.canBeUsed(evaluationTime);
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Pacific/Pago_Pago")));
            boolean westernResult = discountCode.canBeUsed(evaluationTime);

            assertThat(easternResult).isTrue();
            assertThat(westernResult).isEqualTo(easternResult);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private DiscountCode discount(boolean active, Integer usageLimit, int usedCount) {
        DiscountCode discountCode = new DiscountCode();
        ReflectionTestUtils.setField(discountCode, "validFrom", VALID_FROM);
        ReflectionTestUtils.setField(discountCode, "validTo", VALID_TO);
        ReflectionTestUtils.setField(discountCode, "active", active);
        ReflectionTestUtils.setField(discountCode, "usageLimit", usageLimit);
        ReflectionTestUtils.setField(discountCode, "usedCount", usedCount);
        return discountCode;
    }
}
