package com.company.shop.module.product.service;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.company.shop.module.category.api.internal.CategoryProductRetirementPolicy;
import com.company.shop.module.product.repository.ProductRepository;

@Service
public class CategoryProductRetirementPolicyImpl implements CategoryProductRetirementPolicy {

    private final ObjectProvider<ProductRepository> productRepository;

    public CategoryProductRetirementPolicyImpl(ObjectProvider<ProductRepository> productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public boolean hasActiveProducts(UUID categoryId) {
        return productRepository.getObject().existsByCategoryId(categoryId);
    }
}
