/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Configuration class for OpenAPI 3.0 documentation.
 * <p>
 * This component defines the global metadata for the API, including contact 
 * information, licensing, and security requirements. It specifically configures 
 * JWT-based authentication to enable authorized requests directly from the Swagger UI.
 * </p>
 *
 * @since 1.0.0
 */
@Configuration
public class OpenApiConfig {

    @Value("${spring.application.name}")
    private String appName;

    /**
     * Creates and configures the global {@link OpenAPI} bean.
     * <p>
     * The configuration includes a "bearerAuth" security scheme, allowing 
     * developers to provide JWT tokens for protected endpoints.
     * </p>
     *
     * @return a fully configured {@link OpenAPI} instance.
     */
    @Bean
    public OpenAPI customOpenAPI() {
        final String securitySchemeName = "bearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title("Enterprise Shop API")
                        .version("1.0.0")
                        .description("REST API for enterprise-grade e-commerce operations.")
                        .contact(new Contact()
                                .name("IT Department")
                                .email("dev-team@company.com")
                                .url("https://company.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Provide a JWT bearer token in the Authorization header.")));
    }
}
