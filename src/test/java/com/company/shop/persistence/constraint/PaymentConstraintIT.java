package com.company.shop.persistence.constraint;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.hibernate.exception.ConstraintViolationException;
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

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class PaymentConstraintIT extends PostgresContainerSupport {

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persist_shouldThrowWhenSecondPaymentReferencesSameOrder() {
        User user = PersistenceFixtures.persistUser(entityManager, "payment.user@example.com");
        Order order = PersistenceFixtures.persistOrder(entityManager, user);

        PersistenceFixtures.persistPayment(entityManager, order, "STRIPE", BigDecimal.valueOf(150.00));
        entityManager.clear();

        Order managedOrder = entityManager.getEntityManager().getReference(Order.class, order.getId());

        assertThatThrownBy(() -> {
            Payment secondPayment = new Payment(managedOrder, "STRIPE", BigDecimal.valueOf(150.00));
            PersistenceFixtures.setCreatedAt(secondPayment);
            entityManager.persist(secondPayment);
            entityManager.flush();
        }).isInstanceOf(ConstraintViolationException.class);
    }
}
