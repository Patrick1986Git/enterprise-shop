package com.company.shop.module.product.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.company.shop.module.product.api.internal.CheckoutProduct;
import com.company.shop.module.product.api.internal.ProductCatalogFacade;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.exception.ProductNotFoundException;
import com.company.shop.module.product.repository.ProductRepository;

@Service
public class ProductCatalogFacadeImpl implements ProductCatalogFacade {

    private final ProductRepository productRepository;

    public ProductCatalogFacadeImpl(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public CheckoutProduct reserveProductForCheckout(UUID productId, int quantity) {
        Product product = productRepository.findByIdWithLock(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        product.decreaseStock(quantity);

        return new CheckoutProduct(product.getId(), product.getName(), product.getPrice());
    }
}
