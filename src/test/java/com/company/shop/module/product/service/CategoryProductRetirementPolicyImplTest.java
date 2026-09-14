package com.company.shop.module.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.company.shop.module.product.repository.ProductRepository;

class CategoryProductRetirementPolicyImplTest {

    @Test
    void hasActiveProducts_shouldDelegateToSoftDeleteAwareRepositoryQuery() {
        UUID categoryId = UUID.randomUUID();
        ProductRepository repository = org.mockito.Mockito.mock(ProductRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ProductRepository> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(repository);
        when(repository.existsByCategoryId(categoryId)).thenReturn(true);

        boolean result = new CategoryProductRetirementPolicyImpl(provider).hasActiveProducts(categoryId);

        assertThat(result).isTrue();
        verify(repository).existsByCategoryId(categoryId);
    }
}
