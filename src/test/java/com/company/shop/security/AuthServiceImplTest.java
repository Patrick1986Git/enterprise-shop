package com.company.shop.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.company.shop.module.user.dto.LoginRequestDTO;
import com.company.shop.module.user.dto.RegisterRequestDTO;
import com.company.shop.module.user.entity.Role;
import com.company.shop.module.user.exception.UserAlreadyExistsException;
import com.company.shop.module.user.exception.UserRoleNotConfiguredException;
import com.company.shop.module.user.repository.RoleRepository;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.security.jwt.JwtTokenProvider;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtTokenProvider tokenProvider;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(
                authenticationManager,
                tokenProvider,
                userRepository,
                roleRepository,
                passwordEncoder,
                new EmailNormalizer());
    }

    @Test
    void login_shouldAuthenticateUsingNormalizedEmail() {
        Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(authentication);

        service.login(new LoginRequestDTO("  USER@Example.com ", "secret123"));

        ArgumentCaptor<UsernamePasswordAuthenticationToken> tokenCaptor = ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
        verify(authenticationManager).authenticate(tokenCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(tokenCaptor.getValue().getName()).isEqualTo("user@example.com");
    }

    @Test
    void register_shouldThrowWhenDefaultRoleMissing() {
        RegisterRequestDTO request = request("new@example.com");
        when(roleRepository.findByName(SecurityConstants.ROLE_USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(UserRoleNotConfiguredException.class)
                .hasMessageContaining(SecurityConstants.ROLE_USER);

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void register_shouldMapDataIntegrityViolationToBusinessExceptionForEmailConstraint() {
        RegisterRequestDTO request = request("new@example.com");
        when(roleRepository.findByName(SecurityConstants.ROLE_USER)).thenReturn(Optional.of(new Role(SecurityConstants.ROLE_USER)));
        when(passwordEncoder.encode(request.getPassword())).thenReturn("encoded-pass");

        ConstraintViolationException constraint = new ConstraintViolationException(
                "duplicate key",
                new SQLException("duplicate key"),
                "ux_users_email_lower");
        when(userRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("constraint", constraint));

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(UserAlreadyExistsException.class)
                .hasMessage("User account already exists");
    }

    @Test
    void register_shouldRethrowDataIntegrityViolationWhenConstraintIsDifferent() {
        RegisterRequestDTO request = request("new@example.com");
        when(roleRepository.findByName(SecurityConstants.ROLE_USER)).thenReturn(Optional.of(new Role(SecurityConstants.ROLE_USER)));
        when(passwordEncoder.encode(request.getPassword())).thenReturn("encoded-pass");
        when(userRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("some_other_constraint"));

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void register_shouldPersistUserWhenRequestValid() {
        RegisterRequestDTO request = request(" new@example.com ");

        when(roleRepository.findByName(SecurityConstants.ROLE_USER)).thenReturn(Optional.of(new Role(SecurityConstants.ROLE_USER)));
        when(passwordEncoder.encode(request.getPassword())).thenReturn("encoded-pass");

        service.register(request);

        verify(userRepository).saveAndFlush(any());
    }

    private RegisterRequestDTO request(String email) {
        return new RegisterRequestDTO(email, "secret123", "secret123", "John", "Doe");
    }
}
