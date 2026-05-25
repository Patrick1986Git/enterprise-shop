package com.company.shop.module.product.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProductInsufficientStockExceptionTest {

    @Test
    void constructor_shouldExposeProvidedDetailsViaGetters() {
        ProductInsufficientStockException exception = new ProductInsufficientStockException("Phone", 7, 3);

        assertThat(exception.getProductName()).isEqualTo("Phone");
        assertThat(exception.getRequestedQuantity()).isEqualTo(7);
        assertThat(exception.getAvailableQuantity()).isEqualTo(3);
    }
}
