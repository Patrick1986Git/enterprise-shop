package com.company.shop.module.product.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import com.company.shop.module.product.exception.ProductStockInvalidException;

import com.company.shop.module.product.api.internal.CheckoutProduct;
import com.company.shop.module.product.api.internal.ProductCatalogFacade;
import com.company.shop.module.product.api.internal.ReservedInventoryItem;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.exception.ProductNotFoundException;
import com.company.shop.module.product.repository.ProductRepository;

@Service
public class ProductCatalogFacadeImpl implements ProductCatalogFacade {

    private final ProductRepository productRepository;
    private final JdbcTemplate jdbcTemplate;

    public ProductCatalogFacadeImpl(ProductRepository productRepository, JdbcTemplate jdbcTemplate) {
        this.productRepository = productRepository;
        this.jdbcTemplate = jdbcTemplate;
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
                    if (item.quantity() <= 0) throw new ProductStockInvalidException("restore", item.quantity());
                    productRepository.findByIdWithLock(item.productId()).ifPresentOrElse(
                            product -> product.restoreReservedStock(item.quantity()),
                            () -> restoreHiddenProduct(item));
                });
    }

    private void restoreHiddenProduct(ReservedInventoryItem item) {
        Integer current = jdbcTemplate.query("SELECT stock FROM products WHERE id = ? FOR UPDATE",
                rs -> rs.next() ? rs.getInt(1) : null, item.productId());
        if (current == null) throw new ProductNotFoundException(item.productId());
        final int restored;
        try { restored = Math.addExact(current, item.quantity()); }
        catch (ArithmeticException ex) { throw new ProductStockInvalidException("Restored product stock exceeds the supported range"); }
        jdbcTemplate.update("UPDATE products SET stock = ?, version = version + 1 WHERE id = ?", restored, item.productId());
    }
}
