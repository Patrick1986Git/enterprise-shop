package com.company.shop.security.jwt;

import static com.company.shop.security.SecurityConstants.AUTHORIZATION_HEADER;
import static com.company.shop.security.SecurityConstants.ROLE_ADMIN;
import static com.company.shop.security.SecurityConstants.ROLE_USER;
import static com.company.shop.security.SecurityConstants.TOKEN_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.Collection;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String VALID_TOKEN = "valid-token";
    private static final String INVALID_TOKEN = "invalid-token";
    private static final String USER_EMAIL = "john.doe@example.com";
    private static final String NON_BEARER_AUTHORIZATION_HEADER = "Basic abc";

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private UserDetailsService userDetailsService;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        filter = new JwtAuthenticationFilter(jwtTokenProvider, userDetailsService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilter_shouldKeepAuthenticationNullAndSkipTokenProvider_whenAuthorizationHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertRequestPassedThroughFilterChain(filterChain, request, response);
        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    void doFilter_shouldKeepAuthenticationNullAndSkipTokenProvider_whenAuthorizationHeaderDoesNotStartWithBearerPrefix()
            throws Exception {
        MockHttpServletRequest request = requestWithAuthorizationHeader(NON_BEARER_AUTHORIZATION_HEADER);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertRequestPassedThroughFilterChain(filterChain, request, response);
        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    void doFilter_shouldKeepAuthenticationNull_whenBearerTokenIsInvalid() throws Exception {
        MockHttpServletRequest request = requestWithAuthorizationHeader(TOKEN_PREFIX + INVALID_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();
        when(jwtTokenProvider.validate(INVALID_TOKEN)).thenReturn(false);

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertRequestPassedThroughFilterChain(filterChain, request, response);
        verify(jwtTokenProvider).validate(INVALID_TOKEN);
        verifyNoMoreInteractions(jwtTokenProvider);
    }

    @Test
    void doFilter_shouldSetAuthentication_whenBearerTokenIsValid() throws Exception {
        MockHttpServletRequest request = requestWithAuthorizationHeader(TOKEN_PREFIX + VALID_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();
        when(jwtTokenProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtTokenProvider.getUsername(VALID_TOKEN)).thenReturn(USER_EMAIL);
        when(userDetailsService.loadUserByUsername(USER_EMAIL)).thenReturn(activeUser(ROLE_USER, ROLE_ADMIN));

        filter.doFilter(request, response, filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo(USER_EMAIL);
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getCredentials()).isNull();
        assertThat(authentication.getDetails()).isNotNull();

        assertThat(authentication.getPrincipal()).isInstanceOf(UserDetails.class);
        UserDetails principal = (UserDetails) authentication.getPrincipal();
        assertThat(principal.getUsername()).isEqualTo(USER_EMAIL);
        assertThat(authorityNames(principal.getAuthorities())).isEqualTo(Set.of(ROLE_USER, ROLE_ADMIN));
        assertThat(authorityNames(authentication)).isEqualTo(Set.of(ROLE_USER, ROLE_ADMIN));

        assertRequestPassedThroughFilterChain(filterChain, request, response);

        verify(jwtTokenProvider).validate(VALID_TOKEN);
        verify(jwtTokenProvider).getUsername(VALID_TOKEN);
        verifyNoMoreInteractions(jwtTokenProvider);
        verify(userDetailsService).loadUserByUsername(USER_EMAIL);
    }

    @Test
    void doFilter_shouldKeepAuthenticationNull_whenTokenSubjectDoesNotExist() throws Exception {
        MockHttpServletRequest request = requestWithAuthorizationHeader(TOKEN_PREFIX + VALID_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();
        when(jwtTokenProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtTokenProvider.getUsername(VALID_TOKEN)).thenReturn(USER_EMAIL);
        when(userDetailsService.loadUserByUsername(USER_EMAIL))
                .thenThrow(new UsernameNotFoundException("User not found"));

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertRequestPassedThroughFilterChain(filterChain, request, response);
    }

    @Test
    void doFilter_shouldKeepAuthenticationNull_whenTokenSubjectIsDisabled() throws Exception {
        MockHttpServletRequest request = requestWithAuthorizationHeader(TOKEN_PREFIX + VALID_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();
        when(jwtTokenProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtTokenProvider.getUsername(VALID_TOKEN)).thenReturn(USER_EMAIL);
        when(userDetailsService.loadUserByUsername(USER_EMAIL)).thenReturn(
                org.springframework.security.core.userdetails.User.withUsername(USER_EMAIL)
                        .password("")
                        .authorities(ROLE_ADMIN)
                        .disabled(true)
                        .build());

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertRequestPassedThroughFilterChain(filterChain, request, response);
    }


    private Set<String> authorityNames(Authentication authentication) {
        return authorityNames(authentication.getAuthorities());
    }

    private UserDetails activeUser(String... roles) {
        return org.springframework.security.core.userdetails.User.withUsername(USER_EMAIL)
                .password("")
                .authorities(roles)
                .build();
    }

    private Set<String> authorityNames(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    private MockHttpServletRequest requestWithAuthorizationHeader(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AUTHORIZATION_HEADER, value);
        return request;
    }

    private void assertRequestPassedThroughFilterChain(
            MockFilterChain filterChain,
            MockHttpServletRequest request,
            MockHttpServletResponse response) {
        assertThat(filterChain.getRequest()).isSameAs(request);
        assertThat(filterChain.getResponse()).isSameAs(response);
    }
}
