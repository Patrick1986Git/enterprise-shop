/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 */

package com.company.shop.common.exception;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import com.company.shop.common.i18n.MessageService;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import jakarta.validation.ConstraintViolationException;

/**
 * Global interceptor for application-wide exception handling.
 *
 * <p>
 * This class is purely infrastructural. It does not contain domain knowledge
 * and does not map domain exceptions manually. Domain-specific errors must
 * extend {@link BusinessException}.
 * </p>
 *
 * @since 2.0.0
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private static final String BUSINESS_EXCEPTION_METRIC = "shop.business_exception.total";
	private static final String ERROR_CODE_TAG = "error_code";
	private static final String STATUS_CLASS_TAG = "status_class";
	private static final String UNKNOWN_BUSINESS_ERROR = "UNKNOWN_BUSINESS_ERROR";

	private final MeterRegistry meterRegistry;
	private final MessageService messageService;

	public GlobalExceptionHandler(MeterRegistry meterRegistry, MessageService messageService) {
		this.meterRegistry = meterRegistry;
		this.messageService = messageService;
	}

	/**
	 * Handles validation failures for {@code @Valid} annotated parameters.
	 *
	 * @param ex the exception containing binding and validation results.
	 * @return a 400 Bad Request response with detailed field errors.
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleValidationExceptions(MethodArgumentNotValidException ex) {

		Map<String, List<String>> errors = new HashMap<>();

		ex.getBindingResult().getFieldErrors().forEach(error -> addValidationError(errors, error.getField(), error.getDefaultMessage()));
		ex.getBindingResult().getGlobalErrors()
				.forEach(error -> addValidationError(errors, "_global", error.getDefaultMessage()));

		log.warn("Input validation failed fields={}", errors.keySet());

		ApiError apiError = new ApiError(HttpStatus.BAD_REQUEST.value(), messageService.getMessage("error.validation.failed"), "VALIDATION_FAILED", errors);

		return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
	}

	/**
	 * Handles all domain/business exceptions.
	 *
	 * <p>
	 * Every domain exception must extend {@link BusinessException} and define its
	 * own HTTP status and message.
	 * </p>
	 *
	 * @param ex the business exception
	 * @return mapped HTTP response defined by the exception itself
	 */
	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ApiError> handleBusinessException(BusinessException ex) {

		String errorCode = ex.getErrorCode() != null ? ex.getErrorCode() : UNKNOWN_BUSINESS_ERROR;
		if (ex.getStatus().is5xxServerError()) {
			log.error("Business invariant/server exception occurred [{}] status={} type={}",
				errorCode,
				ex.getStatus().value(),
				ex.getClass().getSimpleName(),
				ex);
		} else {
			log.warn("Business exception occurred [{}] status={} type={}",
				errorCode,
				ex.getStatus().value(),
				ex.getClass().getSimpleName());
		}

		incrementBusinessExceptionMetric(errorCode, ex.getStatus());

		ApiError apiError = new ApiError(ex.getStatus().value(), ex.getMessage(), errorCode);

		return new ResponseEntity<>(apiError, ex.getStatus());
	}


	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	public ResponseEntity<ApiError> handleOptimisticLockingFailure(ObjectOptimisticLockingFailureException ex) {

		log.warn("Optimistic lock conflict status={} type={}",
				HttpStatus.CONFLICT.value(),
				ex.getClass().getSimpleName());

		ApiError apiError = new ApiError(HttpStatus.CONFLICT.value(),
				messageService.getMessage("error.optimistic.lock.conflict"),
				"OPTIMISTIC_LOCK_CONFLICT");

		return new ResponseEntity<>(apiError, HttpStatus.CONFLICT);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiError> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {

		String expectedType = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown";
		Map<String, String> details = Map.of("parameter", ex.getName(), "expectedType", expectedType);

		ApiError apiError = new ApiError(HttpStatus.BAD_REQUEST.value(),
				messageService.getMessage("error.request.invalid.parameter", ex.getName()),
				"REQUEST_INVALID",
				details);

		return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiError> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {

		Throwable rootCause = findCause(ex, JsonParseException.class, InvalidFormatException.class);
		String message = messageService.getMessage("error.request.invalid.body.values");
		if (rootCause instanceof JsonParseException) {
			message = messageService.getMessage("error.request.invalid.body.malformed");
		}

		ApiError apiError = new ApiError(HttpStatus.BAD_REQUEST.value(),
				message,
				"REQUEST_INVALID");

		return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ApiError> handleConstraintViolationException(ConstraintViolationException ex) {

		Map<String, String> errors = new HashMap<>();
		ex.getConstraintViolations().forEach(v -> errors.put(v.getPropertyPath().toString(), v.getMessage()));

		ApiError apiError = new ApiError(HttpStatus.BAD_REQUEST.value(),
				messageService.getMessage("error.validation.failed"),
				"VALIDATION_FAILED",
				errors);

		return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ApiError> handleMissingServletRequestParameter(MissingServletRequestParameterException ex) {

		Map<String, String> details = Map.of("parameter", ex.getParameterName(), "expectedType", ex.getParameterType());

		ApiError apiError = new ApiError(HttpStatus.BAD_REQUEST.value(),
				messageService.getMessage("error.request.missing.parameter", ex.getParameterName()),
				"REQUEST_INVALID",
				details);

		return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
	}


	@ExceptionHandler(MissingRequestHeaderException.class)
	public ResponseEntity<ApiError> handleMissingRequestHeader(MissingRequestHeaderException ex) {

		String expectedType = "unknown";
		if (ex.getParameter() != null && ex.getParameter().getParameterType() != null) {
			expectedType = ex.getParameter().getParameterType().getSimpleName();
		}

		Map<String, String> details = Map.of("parameter", ex.getHeaderName(), "expectedType", expectedType);

		ApiError apiError = new ApiError(HttpStatus.BAD_REQUEST.value(),
				messageService.getMessage("error.request.missing.header", ex.getHeaderName()),
				"REQUEST_INVALID",
				details);

		return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
	}

	/**
	 * Handles authorization failures.
	 *
	 * @param ex the access denied exception.
	 * @return a 403 Forbidden response.
	 */
	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {

		ApiError apiError = new ApiError(HttpStatus.FORBIDDEN.value(),
				messageService.getMessage("error.access.denied"),
				"ACCESS_DENIED");

		return new ResponseEntity<>(apiError, HttpStatus.FORBIDDEN);
	}

	/**
	 * Handles requests targeting undefined endpoints.
	 *
	 * @param ex the exception for a missing request mapping.
	 * @return a 404 Not Found response.
	 */
	@ExceptionHandler(NoHandlerFoundException.class)
	public ResponseEntity<ApiError> handleNoHandlerFound(NoHandlerFoundException ex) {

		ApiError apiError = new ApiError(HttpStatus.NOT_FOUND.value(),
				messageService.getMessage("error.endpoint.not.found", ex.getHttpMethod(), ex.getRequestURL()),
				"ENDPOINT_NOT_FOUND");

		return new ResponseEntity<>(apiError, HttpStatus.NOT_FOUND);
	}

	private void incrementBusinessExceptionMetric(String errorCode, HttpStatus status) {
		meterRegistry.counter(BUSINESS_EXCEPTION_METRIC,
				ERROR_CODE_TAG, errorCode,
				STATUS_CLASS_TAG, mapStatusClass(status))
				.increment();
	}

	private String mapStatusClass(HttpStatus status) {
		if (status.is4xxClientError()) {
			return "4xx";
		}
		if (status.is5xxServerError()) {
			return "5xx";
		}
		return "other";
	}

	private Throwable findCause(Throwable throwable, Class<?>... types) {
		Throwable current = throwable;
		while (current != null) {
			for (Class<?> type : types) {
				if (type.isInstance(current)) {
					return current;
				}
			}
			current = current.getCause();
		}
		return null;
	}


	private void addValidationError(Map<String, List<String>> errors, String key, String message) {
		errors.computeIfAbsent(key, k -> new ArrayList<>()).add(message);
	}

	/**
	 * Ultimate safety net for all unhandled exceptions.
	 *
	 * <p>
	 * Logs full stack trace internally while hiding implementation details from API
	 * consumers.
	 * </p>
	 *
	 * @param ex the caught exception
	 * @return a 500 Internal Server Error response
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleAllExceptions(Exception ex) {

		log.error("Unhandled application exception type={}", ex.getClass().getSimpleName(), ex);

		ApiError apiError = new ApiError(HttpStatus.INTERNAL_SERVER_ERROR.value(),
				messageService.getMessage("error.unexpected.server"));

		return new ResponseEntity<>(apiError, HttpStatus.INTERNAL_SERVER_ERROR);
	}
}
