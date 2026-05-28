package com.company.shop.module.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.company.shop.common.model.BaseEntity;
import com.company.shop.module.user.api.internal.CurrentUserSnapshot;
import com.company.shop.module.user.entity.Role;
import com.company.shop.module.user.entity.User;
import com.company.shop.security.SecurityConstants;

@ExtendWith(MockitoExtension.class)
class CurrentUserFacadeImplTest {

    @Mock
    private UserService userService;

    private CurrentUserFacadeImpl facade;

    @BeforeEach
    void setUp() {
        facade = new CurrentUserFacadeImpl(userService);
    }

    @Test
    void getCurrentUser_shouldMapIdEmailAndRoles() {
        User user = new User("john@example.com", "encoded", "John", "Doe");
        setEntityId(user, UUID.randomUUID());
        user.addRole(new Role(SecurityConstants.ROLE_USER));
        user.addRole(new Role(SecurityConstants.ROLE_ADMIN));
        when(userService.getCurrentUserEntity()).thenReturn(user);

        CurrentUserSnapshot snapshot = facade.getCurrentUser();

        assertThat(snapshot.id()).isEqualTo(user.getId());
        assertThat(snapshot.email()).isEqualTo("john@example.com");
        assertThat(snapshot.roles()).isEqualTo(Set.of(SecurityConstants.ROLE_USER, SecurityConstants.ROLE_ADMIN));
    }

    @Test
    void currentUserHasRole_shouldUseMappedRoles() {
        User user = new User("john@example.com", "encoded", "John", "Doe");
        setEntityId(user, UUID.randomUUID());
        user.addRole(new Role(SecurityConstants.ROLE_USER));
        when(userService.getCurrentUserEntity()).thenReturn(user);

        assertThat(facade.currentUserHasRole(SecurityConstants.ROLE_USER)).isTrue();
        assertThat(facade.currentUserHasRole(SecurityConstants.ROLE_ADMIN)).isFalse();
    }

    private void setEntityId(Object entity, UUID id) {
        try {
            Field field = BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }
}
