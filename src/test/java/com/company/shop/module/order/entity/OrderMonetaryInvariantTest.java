package com.company.shop.module.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.company.shop.module.order.exception.OrderAmountInvalidException;

class OrderMonetaryInvariantTest {

    @Test
    void addItem_shouldAcceptExactMaximumPersistedTotal() {
        Order order = order();

        order.addItem(item(1, "9999999999.99"));

        assertThat(order.getTotalAmount()).isEqualByComparingTo("9999999999.99");
    }

    @Test
    void addItem_shouldRejectOneLineTotalOutsidePersistedRange() {
        Order order = order();

        assertThatThrownBy(() -> order.addItem(item(2, "5000000000.00")))
                .isInstanceOf(OrderAmountInvalidException.class);
    }

    @Test
    void addItem_shouldRejectMultiLineTotalOutsidePersistedRange() {
        Order order = order();
        order.addItem(item(1, "5000000000.00"));

        assertThatThrownBy(() -> order.addItem(item(1, "5000000000.00")))
                .isInstanceOf(OrderAmountInvalidException.class);
    }

    @Test
    void applyDiscount_shouldRejectOneHundredPercentDiscountWithoutConsumingUsage() {
        Order order = order();
        order.addItem(item(1, "10.00"));
        DiscountCode discount = usableDiscount(100);

        assertThatThrownBy(() -> order.applyDiscount(discount))
                .isInstanceOf(OrderAmountInvalidException.class);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("10.00");
        verify(discount, never()).incrementUsage();
    }

    @Test
    void applyDiscount_shouldRejectAmountThatRoundsToZeroWithoutConsumingUsage() {
        Order order = order();
        order.addItem(item(1, "0.01"));
        DiscountCode discount = usableDiscount(99);

        assertThatThrownBy(() -> order.applyDiscount(discount))
                .isInstanceOf(OrderAmountInvalidException.class);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("0.01");
        verify(discount, never()).incrementUsage();
    }

    @Test
    void applyDiscount_shouldPreservePositivePayableTotal() {
        Order order = order();
        order.addItem(item(1, "100.00"));
        DiscountCode discount = usableDiscount(10);

        order.applyDiscount(discount);

        assertThat(order.getTotalAmount()).isEqualByComparingTo("90.00");
        verify(discount).incrementUsage();
    }

    private Order order() {
        return new Order(UUID.randomUUID(), "buyer@example.com");
    }

    private OrderItem item(int quantity, String price) {
        return new OrderItem(UUID.randomUUID(), "Product", "SKU", quantity, new BigDecimal(price));
    }

    private DiscountCode usableDiscount(int percent) {
        DiscountCode discount = mock(DiscountCode.class);
        when(discount.canBeUsed()).thenReturn(true);
        when(discount.getDiscountPercent()).thenReturn(percent);
        return discount;
    }
}
