package com.company.shop.persistence.support;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.company.shop.common.model.AuditableEntity;
import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.Payment;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.entity.ProductReview;
import com.company.shop.module.user.entity.User;

/**
 * Minimal fixture helpers for persistence integration tests.
 * <p>
 * Keeps tests concise without introducing a full builder framework.
 * </p>
 */
public final class PersistenceFixtures {

    private PersistenceFixtures() {
    }

    public static User persistUser(TestEntityManager entityManager, String email) {
        User user = new User(email, "encoded-pass", "Test", "User");
        return persistAndFlush(entityManager, user);
    }

    public static Category persistCategory(TestEntityManager entityManager, String baseName) {
        String suffix = shortSuffix();
        Category category = new Category(
                baseName + "-" + suffix,
                baseName.toLowerCase(Locale.ROOT) + "-" + suffix,
                "desc"
        );
        return persistAndFlush(entityManager, category);
    }

    public static Product persistProduct(TestEntityManager entityManager,
                                         String name,
                                         String slugBase,
                                         String skuBase,
                                         BigDecimal price,
                                         int stock) {
        Category category = persistCategory(entityManager, "category");
        return persistProduct(entityManager, name, slugBase, skuBase, price, stock, category);
    }

    public static Product persistProduct(TestEntityManager entityManager,
                                         String name,
                                         String slugBase,
                                         String skuBase,
                                         BigDecimal price,
                                         int stock,
                                         Category category) {
        String suffix = shortSuffix();

        Product product = new Product(
                name,
                slugBase + "-" + suffix,
                skuBase + "-" + suffix,
                "desc",
                price,
                stock,
                category
        );
        return persistAndFlush(entityManager, product);
    }

    public static Cart persistCart(TestEntityManager entityManager, User user) {
        Cart cart = new Cart(user);
        return persistAndFlush(entityManager, cart);
    }

    public static Order persistOrder(TestEntityManager entityManager, User user) {
        Order order = new Order(user.getId(), user.getEmail());
        return persistAndFlush(entityManager, order);
    }

    public static Payment persistPayment(TestEntityManager entityManager, Order order, String provider, BigDecimal amount) {
        Payment payment = new Payment(order, provider, amount);
        return persistAndFlush(entityManager, payment);
    }

    public static ProductReview persistProductReview(TestEntityManager entityManager,
                                                     Product product,
                                                     User user,
                                                     int rating,
                                                     String comment) {
        ProductReview review = new ProductReview(product, user, rating, comment);
        return persistAndFlush(entityManager, review);
    }

    public static <T> T persistAndFlush(TestEntityManager entityManager, T entity) {
        setCreatedAt(entity);
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }

    public static void setCreatedAt(Object entity) {
        try {
            Field field = AuditableEntity.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(entity, LocalDateTime.now());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not set createdAt for test entity", e);
        }
    }

    private static String shortSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
