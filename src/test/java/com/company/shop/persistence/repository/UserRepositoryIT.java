package com.company.shop.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.company.shop.module.user.entity.Role;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.persistence.support.PersistenceFixtures;
import com.company.shop.persistence.support.PostgresContainerSupport;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class UserRepositoryIT extends PostgresContainerSupport {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void findActiveByEmailWithRoles_shouldFindUserIgnoringEmailCase() {
        User user = PersistenceFixtures.persistUser(entityManager, "john.repository@example.com");
        Role role = persistRole("ROLE_TEST");
        User managedUser = entityManager.getEntityManager().find(User.class, user.getId());
        managedUser.addRole(role);
        entityManager.flush();
        entityManager.clear();

        Optional<User> found = userRepository.findActiveByEmailWithRoles("JOHN.REPOSITORY@EXAMPLE.COM");

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getEmail()).isEqualTo("john.repository@example.com");
        assertThat(found.orElseThrow().getRoles())
                .extracting(Role::getName)
                .contains(role.getName());
    }

    @Test
    void findActiveByEmailWithRoles_shouldReturnEmptyForSoftDeletedUser() {
        User user = PersistenceFixtures.persistUser(entityManager, "deleted.repository@example.com");
        Role role = persistRole("ROLE_DELETED_TEST");
        User managedUser = entityManager.getEntityManager().find(User.class, user.getId());
        managedUser.addRole(role);
        managedUser.markDeleted();
        entityManager.flush();
        entityManager.clear();

        Optional<User> found = userRepository.findActiveByEmailWithRoles("DELETED.REPOSITORY@EXAMPLE.COM");

        assertThat(found).isEmpty();
    }

    private Role persistRole(String baseName) {
        Role role = new Role(baseName + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        entityManager.persist(role);
        entityManager.flush();
        return role;
    }
}
