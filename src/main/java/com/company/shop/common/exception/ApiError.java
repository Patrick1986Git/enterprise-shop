/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.common.exception;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Standardized API error response container for production-grade applications.
 * <p>
 * This record ensures that all error responses across the system maintain 
 * a consistent structure. The addition of {@code errorCode} allows client 
 * applications to perform programmatic logic based on specific error types 
 * rather than parsing human-readable messages.
 * </p>
 *
 * @param status    the HTTP status code value (e.g., 400, 404, 500).
 * @param message   a human-readable description of the error (may be localized).
 * @param errorCode a unique, machine-readable string identifying the specific error (e.g., "USER_NOT_FOUND").
 * @param errors    optional detailed information (e.g., field-level validation errors).
 * @param timestamp the exact time the error occurred.
 * @since 1.1.0
 */
@Schema(name = "ApiError", description = "Standard API error response.")
public record ApiError(
        @Schema(description = "Numeric HTTP status code.", example = "400")
        int status,

        @Schema(description = "Human-readable error message, possibly localized.", example = "Validation failed")
        String message,

        @Schema(description = "Stable machine-readable error code.", example = "VALIDATION_FAILED")
        String errorCode,

        @Schema(description = "Optional structured error details, such as field validation errors.")
        Object errors,

        @Schema(description = "Time the error response was created.", example = "2026-06-22T12:30:00")
        LocalDateTime timestamp
) {

    public ApiError(int status, String message) {
        this(status, message, null, null, LocalDateTime.now());
    }

    public ApiError(int status, String message, String errorCode) {
        this(status, message, errorCode, null, LocalDateTime.now());
    }

    public ApiError(int status, String message, String errorCode, Object errors) {
        this(status, message, errorCode, errors, LocalDateTime.now());
    }

    public ApiError(int status, String message, Object errors) {
        this(status, message, "VALIDATION_FAILED", errors, LocalDateTime.now());
    }
}