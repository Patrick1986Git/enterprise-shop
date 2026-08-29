package com.company.shop.config;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        if (allowedOrigins == null) {
            allowedOrigins = List.of();
        } else {
            LinkedHashSet<String> normalizedOrigins = new LinkedHashSet<>();
            for (String configuredOrigin : allowedOrigins) {
                String origin = configuredOrigin == null ? "" : configuredOrigin.trim();
                if (origin.isEmpty()) {
                    continue;
                }
                validateOrigin(origin);
                normalizedOrigins.add(origin);
            }
            allowedOrigins = List.copyOf(normalizedOrigins);
        }
    }

    private static void validateOrigin(String origin) {
        URI uri;
        try {
            uri = URI.create(origin);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("CORS allowed origin must be a valid HTTP(S) origin: " + origin,
                    exception);
        }

        String scheme = uri.getScheme();
        boolean httpScheme = scheme != null
                && (scheme.toLowerCase(Locale.ROOT).equals("http")
                    || scheme.toLowerCase(Locale.ROOT).equals("https"));
        boolean originOnly = uri.getHost() != null
                && uri.getUserInfo() == null
                && (uri.getPath().isEmpty())
                && uri.getQuery() == null
                && uri.getFragment() == null;
        if (!httpScheme || !originOnly || "*".equals(origin)) {
            throw new IllegalArgumentException("CORS allowed origin must be an explicit HTTP(S) origin: " + origin);
        }
    }
}
