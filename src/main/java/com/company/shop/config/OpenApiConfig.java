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
import org.springframework.http.MediaType;

import com.company.shop.common.exception.ApiError;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
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

    private static final String SECURITY_SCHEME_NAME = "bearerAuth";
    private static final String API_ERROR_SCHEMA_NAME = "ApiError";
    private static final String API_ERROR_SCHEMA_REF = "#/components/schemas/" + API_ERROR_SCHEMA_NAME;

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
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSchemas(API_ERROR_SCHEMA_NAME, ModelConverters.getInstance()
                                .read(ApiError.class)
                                .get(API_ERROR_SCHEMA_NAME))
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(SECURITY_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Provide a JWT bearer token in the Authorization header."))
                        .addResponses("BadRequestError", errorResponse("Invalid request or validation failure"))
                        .addResponses("UnauthorizedError", errorResponse("Authentication is required or the token is invalid/missing"))
                        .addResponses("ForbiddenError", errorResponse("Authenticated user does not have permission"))
                        .addResponses("NotFoundError", errorResponse("Requested resource or endpoint was not found"))
                        .addResponses("ConflictError", errorResponse("Request conflicts with the current resource state"))
                        .addResponses("InternalServerError", errorResponse("Unexpected server error")));
    }

    private ApiResponse errorResponse(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(MediaType.APPLICATION_JSON_VALUE,
                        new io.swagger.v3.oas.models.media.MediaType()
                                .schema(new Schema<>().$ref(API_ERROR_SCHEMA_REF))));
    }
}
