package com.company.shop.security;

public final class SecurityConstants {

    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_USER = "ROLE_USER";

    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String TOKEN_PREFIX = "Bearer ";

    public static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1",
            "/api/v1/auth/**",
            "/css/**",
            "/js/**",
            "/images/**",
            "/swagger-ui/**",
            "/api-docs",
            "/api-docs/**",
            "/v3/api-docs/**"
    };

    private SecurityConstants() {
    }
}
