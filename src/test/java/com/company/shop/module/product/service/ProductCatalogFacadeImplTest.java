package com.company.shop.module.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.company.shop.common.model.BaseEntity;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.product.api.internal.CheckoutProduct;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.exception.ProductInsufficientStockException;
import com.company.shop.module.product.exception.ProductNotFoundException;
import com.company.shop.module.product.repository.ProductRepository;

@ExtendWith(MockitoExtension.class)
class ProductCatalogFacadeImplTest {

    @Mock
    private ProductRepository productRepository;

    @Test
    void reserveProductForCheckout_shouldReserveAndReturnSnapshotWhenStockIsSufficient() {
        UUID productId = UUID.randomUUID();
        Product product = product(productId, 5, BigDecimal.valueOf(19.99));
        ProductCatalogFacadeImpl facade = new ProductCatalogFacadeImpl(productRepository);

        when(productRepository.findByIdWithLock(productId)).thenReturn(Optional.of(product));

        CheckoutProduct result = facade.reserveProductForCheckout(productId, 2);

        assertThat(product.getStock()).isEqualTo(3);
        assertThat(result.id()).isEqualTo(product.getId());
        assertThat(result.name()).isEqualTo(product.getName());
        assertThat(result.price()).isEqualByComparingTo("19.99");
        verify(productRepository).findByIdWithLock(productId);
    }

    @Test
    void reserveProductForCheckout_shouldThrowWhenProductDoesNotExist() {
        UUID productId = UUID.randomUUID();
        ProductCatalogFacadeImpl facade = new ProductCatalogFacadeImpl(productRepository);
        when(productRepository.findByIdWithLock(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facade.reserveProductForCheckout(productId, 1))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void reserveProductForCheckout_shouldThrowWhenStockIsInsufficient() {
        UUID productId = UUID.randomUUID();
        Product product = product(productId, 1, BigDecimal.valueOf(19.99));
        ProductCatalogFacadeImpl facade = new ProductCatalogFacadeImpl(productRepository);

        when(productRepository.findByIdWithLock(productId)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> facade.reserveProductForCheckout(productId, 2))
                .isInstanceOf(ProductInsufficientStockException.class);
    }

    private Product product(UUID id, int stock, BigDecimal price) {
        Category category = new Category("Tech", "tech", "Test category");
        Product product = new Product("Phone", "phone", "SKU-1", "desc", price, stock, category);
        setEntityId(product, id);
        return product;
    }

    private void setEntityId(Object entity, UUID id) {
        try {
            Field field = BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
