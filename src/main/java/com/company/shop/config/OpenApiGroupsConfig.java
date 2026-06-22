/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.config;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Defines stable SpringDoc groups for the generated OpenAPI documents.
 */
@Configuration
public class OpenApiGroupsConfig {

    public static final String ALL_API_GROUP = "all-api";
    public static final String PUBLIC_API_GROUP = "public-api";
    public static final String CUSTOMER_API_GROUP = "customer-api";
    public static final String ADMIN_API_GROUP = "admin-api";
    public static final String WEBHOOKS_API_GROUP = "webhooks-api";
    public static final String SYSTEM_API_GROUP = "system-api";

    private static final String ALL_API_PATHS = "/api/v1/**";
    private static final String AUTH_PATHS = "/api/v1/auth/**";
    private static final String PRODUCTS_PATH = "/api/v1/products";
    private static final String PRODUCTS_PATHS = "/api/v1/products/**";
    private static final String CATEGORIES_PATH = "/api/v1/categories";
    private static final String CATEGORIES_PATHS = "/api/v1/categories/**";
    private static final String SYSTEM_PATHS = "/api/v1/system/**";
    private static final String SYSTEM_STATUS_PATH = "/api/v1/system/status";
    private static final String CURRENT_USER_PATH = "/api/v1/me";
    private static final String CURRENT_USER_PATHS = "/api/v1/me/**";
    private static final String ORDERS_PATHS = "/api/v1/orders/**";
    private static final String REVIEWS_PATH = "/api/v1/reviews";
    private static final String REVIEWS_PATHS = "/api/v1/reviews/**";
    private static final String ADMIN_PATHS = "/api/v1/admin/**";
    private static final String WEBHOOKS_PATHS = "/api/v1/webhooks/**";

    @Bean
    public GroupedOpenApi allApiGroup() {
        return GroupedOpenApi.builder()
                .group(ALL_API_GROUP)
                .pathsToMatch(ALL_API_PATHS)
                .build();
    }

    @Bean
    public GroupedOpenApi publicApiGroup() {
        return GroupedOpenApi.builder()
                .group(PUBLIC_API_GROUP)
                .pathsToMatch(
                        AUTH_PATHS,
                        PRODUCTS_PATH,
                        PRODUCTS_PATHS,
                        CATEGORIES_PATH,
                        CATEGORIES_PATHS,
                        SYSTEM_STATUS_PATH)
                .pathsToExclude(ADMIN_PATHS, CURRENT_USER_PATHS, ORDERS_PATHS, REVIEWS_PATHS, WEBHOOKS_PATHS)
                .build();
    }

    @Bean
    public GroupedOpenApi customerApiGroup() {
        return GroupedOpenApi.builder()
                .group(CUSTOMER_API_GROUP)
                .pathsToMatch(CURRENT_USER_PATH, CURRENT_USER_PATHS, ORDERS_PATHS, REVIEWS_PATH, REVIEWS_PATHS)
                .build();
    }

    @Bean
    public GroupedOpenApi adminApiGroup() {
        return GroupedOpenApi.builder()
                .group(ADMIN_API_GROUP)
                .pathsToMatch(ADMIN_PATHS)
                .build();
    }

    @Bean
    public GroupedOpenApi webhooksApiGroup() {
        return GroupedOpenApi.builder()
                .group(WEBHOOKS_API_GROUP)
                .pathsToMatch(WEBHOOKS_PATHS)
                .build();
    }

    @Bean
    public GroupedOpenApi systemApiGroup() {
        return GroupedOpenApi.builder()
                .group(SYSTEM_API_GROUP)
                .pathsToMatch(SYSTEM_PATHS)
                .build();
    }
}
