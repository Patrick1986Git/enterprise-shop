package com.company.shop.security.jwt;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
@Profile("prod")
public class ProductionJwtExpirationPolicy {

    static final long ACCESS_TOKEN_LIFETIME_MILLISECONDS = 3_600_000L;

    private final JwtProperties properties;

    public ProductionJwtExpirationPolicy(JwtProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validate() {
        if (properties.getExpiration() != ACCESS_TOKEN_LIFETIME_MILLISECONDS) {
            throw new IllegalStateException(
                    "security.jwt.expiration must be exactly 3600000 milliseconds in production");
        }
    }
}
