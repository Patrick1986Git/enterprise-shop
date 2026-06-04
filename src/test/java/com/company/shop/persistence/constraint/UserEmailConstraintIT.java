package com.company.shop.persistence.constraint;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.company.shop.module.user.entity.User;
import com.company.shop.persistence.support.PersistenceFixtures;
import com.company.shop.persistence.support.PostgresContainerSupport;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class UserEmailConstraintIT extends PostgresContainerSupport {

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persist_shouldThrowWhenEmailDiffersOnlyByCase() {
        User firstUser = buildUser("john.doe@example.com", "encoded-pass");
        PersistenceFixtures.persistAndFlush(entityManager, firstUser);

        User duplicateByCase = buildUser("JOHN.DOE@EXAMPLE.COM", "encoded-pass-2");
        PersistenceFixtures.setCreatedAt(duplicateByCase);

        assertThatThrownBy(() -> {
            entityManager.persist(duplicateByCase);
            entityManager.flush();
        }).isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ux_users_email_lower");
    }

    private User buildUser(String email, String password) {
        return new User(email, password, "John", "Doe");
    }
}