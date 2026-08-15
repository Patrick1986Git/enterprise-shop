package com.company.shop.module.product.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.company.shop.module.product.api.internal.CheckoutProduct;
import com.company.shop.module.product.api.internal.ProductCatalogFacade;
import com.company.shop.module.product.api.internal.ReservedInventoryItem;
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

        return new CheckoutProduct(product.getId(), product.getName(), product.getSku(), product.getPrice());
    }

    @Override
    public void releaseReservedInventory(List<ReservedInventoryItem> items) {
        items.stream()
                .sorted(Comparator.comparing(ReservedInventoryItem::productId))
                .forEach(item -> {
                    Product product = productRepository.findByIdWithLock(item.productId())
                            .orElseThrow(() -> new ProductNotFoundException(item.productId()));
                    product.restoreReservedStock(item.quantity());
                });
    }
}
