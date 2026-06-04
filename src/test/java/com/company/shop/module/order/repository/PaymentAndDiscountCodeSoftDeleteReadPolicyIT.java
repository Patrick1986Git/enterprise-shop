package com.company.shop.module.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.Payment;
import com.company.shop.module.user.entity.User;
import com.company.shop.persistence.support.PersistenceFixtures;
import com.company.shop.persistence.support.PostgresContainerSupport;

/**
 * Verifies repository read policy for soft-deleted {@link Payment} and {@link com.company.shop.module.order.entity.DiscountCode}.
 * <p>
 * Both repositories rely on entity-level {@code @SQLRestriction("deleted = false")}.
 * </p>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class PaymentAndDiscountCodeSoftDeleteReadPolicyIT extends PostgresContainerSupport {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private DiscountCodeRepository discountCodeRepository;

    @Test
    void paymentRepository_shouldReturnActivePaymentAndHideSoftDeletedPayment() {
        User user = PersistenceFixtures.persistUser(entityManager, "soft.payment@example.com");
        Order activeOrder = PersistenceFixtures.persistOrder(entityManager, user);
        Order deletedOrder = PersistenceFixtures.persistOrder(entityManager, user);

        PersistenceFixtures.persistPayment(entityManager, activeOrder, "STRIPE", BigDecimal.valueOf(50));

        Payment deletedPayment = new Payment(deletedOrder, "STRIPE", BigDecimal.valueOf(50));
        PersistenceFixtures.setCreatedAt(deletedPayment);
        deletedPayment.markDeleted();
        entityManager.persist(deletedPayment);
        entityManager.flush();
        entityManager.clear();

        assertThat(paymentRepository.findByOrderId(activeOrder.getId())).isPresent();
        assertThat(paymentRepository.findByOrderIdForUpdate(activeOrder.getId())).isPresent();

        assertThat(paymentRepository.findByOrderId(deletedOrder.getId())).isEmpty();
        assertThat(paymentRepository.findByOrderIdForUpdate(deletedOrder.getId())).isEmpty();
    }

    @Test
    void discountCodeRepository_shouldReturnActiveCodeAndHideSoftDeletedCode() {
        String activeCode = "ACTIVE10-" + UUID.randomUUID().toString().substring(0, 8);
        String deletedCode = "DELETED10-" + UUID.randomUUID().toString().substring(0, 8);

        insertDiscountCode(activeCode, false);
        insertDiscountCode(deletedCode, true);

        assertThat(discountCodeRepository.findByCodeIgnoreCase(activeCode.toLowerCase())).isPresent();
        assertThat(discountCodeRepository.findByCodeIgnoreCase(deletedCode.toLowerCase())).isEmpty();
    }

    private void insertDiscountCode(String code, boolean deleted) {
        LocalDateTime now = LocalDateTime.now();

        entityManager.getEntityManager().createNativeQuery("""
                INSERT INTO discount_codes (
                    id, code, discount_percent, valid_from, valid_to, usage_limit, used_count, active,
                    created_at, deleted, deleted_at
                ) VALUES (
                    :id, :code, :discountPercent, :validFrom, :validTo, :usageLimit, :usedCount, :active,
                    :createdAt, :deleted, :deletedAt
                )
                """)
                .setParameter("id", UUID.randomUUID())
                .setParameter("code", code)
                .setParameter("discountPercent", 10)
                .setParameter("validFrom", now.minusDays(1))
                .setParameter("validTo", now.plusDays(1))
                .setParameter("usageLimit", 100)
                .setParameter("usedCount", 0)
                .setParameter("active", true)
                .setParameter("createdAt", now)
                .setParameter("deleted", deleted)
                .setParameter("deletedAt", deleted ? now : null)
                .executeUpdate();

        entityManager.flush();
        entityManager.clear();
    }
}
