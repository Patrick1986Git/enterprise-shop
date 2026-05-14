package com.company.shop.common.exception;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.company.shop.support.TestMeterRegistryConfig;

import io.micrometer.core.instrument.MeterRegistry;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Web MVC contract tests for {@link GlobalExceptionHandler}.
 * <p>
 * This test suite verifies that the global exception layer exposes a stable and
 * consistent {@link ApiError} contract for the most important MVC-layer failure
 * scenarios.
 * </p>
 *
 * <p>
 * Covered scenarios include validation failures, business exceptions, request
 * binding errors, malformed JSON payloads, access denied responses, and
 * unexpected fallback exceptions.
 * </p>
 *
 * <p>
 * The test uses a dedicated in-test controller to trigger representative
 * exception flows in an isolated and deterministic way. Security servlet
 * filters are disabled to keep the scope focused on exception translation.
 * </p>
 */
@WebMvcTest(controllers = GlobalExceptionHandlerWebMvcTest.TestExceptionController.class, excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import({ GlobalExceptionHandler.class, TestMeterRegistryConfig.class })
class GlobalExceptionHandlerWebMvcTest {

	private static final String TIMESTAMP_REGEX = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?$";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MeterRegistry meterRegistry;

	@BeforeEach
	void resetMetrics() {
		meterRegistry.clear();
	}

	@Test
	void validationException_shouldReturnApiErrorContract() throws Exception {
		mockMvc.perform(post("/test-exceptions/validation").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Validation failed"))
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors", notNullValue())).andExpect(jsonPath("$.errors.name").isArray())
				.andExpect(jsonPath("$.errors.name[0]").value("name must not be blank"))
				.andExpect(jsonPath("$.timestamp", matchesPattern(TIMESTAMP_REGEX)));
	}

	@Test
	void validationExceptionWithGlobalError_shouldReturnApiErrorWithGlobalEntry() throws Exception {
		mockMvc.perform(post("/test-exceptions/validation").contentType(MediaType.APPLICATION_JSON).content("""
				{
				  "name": "John",
				  "termsAccepted": false
				}
				""")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Validation failed"))
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors._global").isArray())
				.andExpect(jsonPath("$.errors._global[0]").value("terms must be accepted"))
				.andExpect(jsonPath("$.timestamp", matchesPattern(TIMESTAMP_REGEX)));
	}

	@Test
	void businessException_shouldReturnApiErrorContract() throws Exception {
		mockMvc.perform(get("/test-exceptions/business")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.message").value("Business resource not found"))
				.andExpect(jsonPath("$.errorCode").value("BUSINESS_NOT_FOUND"))
				.andExpect(jsonPath("$.errors").value(nullValue()))
				.andExpect(jsonPath("$.timestamp", matchesPattern(TIMESTAMP_REGEX)));

		assertBusinessExceptionMetric("BUSINESS_NOT_FOUND", "4xx", 1.0d);
	}

	@Test
	void businessExceptionWithoutErrorCode_shouldReturnFallbackErrorCode() throws Exception {
		mockMvc.perform(get("/test-exceptions/business-without-code")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Business validation failed"))
				.andExpect(jsonPath("$.errorCode").value("UNKNOWN_BUSINESS_ERROR"))
				.andExpect(jsonPath("$.errors").value(nullValue()))
				.andExpect(jsonPath("$.timestamp", matchesPattern(TIMESTAMP_REGEX)));

		assertBusinessExceptionMetric("UNKNOWN_BUSINESS_ERROR", "4xx", 1.0d);
	}


	@Test
	void businessException5xx_shouldReturnApiErrorContractAndIncrementMetric() throws Exception {
		mockMvc.perform(get("/test-exceptions/business-server-error")).andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.status").value(500))
				.andExpect(jsonPath("$.message").value("Business server error"))
				.andExpect(jsonPath("$.errorCode").value("BUSINESS_SERVER_ERROR"))
				.andExpect(jsonPath("$.errors").value(nullValue()))
				.andExpect(jsonPath("$.timestamp", matchesPattern(TIMESTAMP_REGEX)));

		assertBusinessExceptionMetric("BUSINESS_SERVER_ERROR", "5xx", 1.0d);
	}

	@Test
	void methodArgumentTypeMismatch_shouldReturnRequestInvalidContract() throws Exception {
		mockMvc.perform(get("/test-exceptions/type-mismatch").param("count", "abc")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Invalid request parameter: count"))
				.andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
				.andExpect(jsonPath("$.errors.parameter").value("count"))
				.andExpect(jsonPath("$.errors.expectedType").value("Integer"))
				.andExpect(jsonPath("$.timestamp", matchesPattern(TIMESTAMP_REGEX)));
	}

	@Test
	void missingServletRequestParameter_shouldReturnRequestInvalidContract() throws Exception {
		mockMvc.perform(get("/test-exceptions/missing-param")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Missing required request parameter: page"))
				.andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
				.andExpect(jsonPath("$.errors.parameter").value("page"))
				.andExpect(jsonPath("$.errors.expectedType").value("int"))
				.andExpect(jsonPath("$.timestamp", matchesPattern(TIMESTAMP_REGEX)));
	}


	@Test
	void missingRequestHeader_shouldReturnRequestInvalidContract() throws Exception {
		mockMvc.perform(get("/test-exceptions/missing-header")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Missing required request header: X-Trace-Id"))
				.andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
				.andExpect(jsonPath("$.errors.parameter").value("X-Trace-Id"))
				.andExpect(jsonPath("$.errors.expectedType").value("String"))
				.andExpect(jsonPath("$.timestamp", matchesPattern(TIMESTAMP_REGEX)));
	}

	@Test
	void malformedJson_shouldReturnRequestInvalidMalformedMessage() throws Exception {
		mockMvc.perform(
				post("/test-exceptions/validation").contentType(MediaType.APPLICATION_JSON).content("{ invalid json }"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Request body is malformed."))
				.andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
				.andExpect(jsonPath("$.errors").value(nullValue()))
				.andExpect(jsonPath("$.timestamp", matchesPattern(TIMESTAMP_REGEX)));
	}

	@Test
	void invalidJsonValueType_shouldReturnRequestInvalidValuesMessage() throws Exception {
		mockMvc.perform(post("/test-exceptions/validation").contentType(MediaType.APPLICATION_JSON).content("""
				{
				  "name": "John",
				  "termsAccepted": "not-a-boolean"
				}
				""")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Request body contains invalid values."))
				.andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
				.andExpect(jsonPath("$.errors").value(nullValue()))
				.andExpect(jsonPath("$.timestamp", matchesPattern(TIMESTAMP_REGEX)));
	}

	@Test
	void accessDenied_shouldReturnApiErrorContract() throws Exception {
		mockMvc.perform(get("/test-exceptions/access-denied")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.status").value(403))
				.andExpect(jsonPath("$.message").value("Insufficient permissions to access this resource"))
				.andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
				.andExpect(jsonPath("$.errors").value(nullValue()))
				.andExpect(jsonPath("$.timestamp", matchesPattern(TIMESTAMP_REGEX)));
	}

	@Test
	void fallbackException_shouldReturnApiErrorContract() throws Exception {
		mockMvc.perform(get("/test-exceptions/unhandled")).andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.status").value(500))
				.andExpect(jsonPath("$.message")
						.value("An unexpected server error occurred. Please contact support if the problem persists."))
				.andExpect(jsonPath("$.errorCode").value(nullValue()))
				.andExpect(jsonPath("$.errors").value(nullValue()))
				.andExpect(jsonPath("$.timestamp", matchesPattern(TIMESTAMP_REGEX)));
	}

	private void assertBusinessExceptionMetric(String errorCode, String statusClass, double expectedCount) {
		double counter = meterRegistry
				.get("shop.business_exception.total")
				.tags("error_code", errorCode, "status_class", statusClass)
				.counter()
				.count();
		assertThat(counter).isEqualTo(expectedCount);
	}

	@RestController
	@RequestMapping("/test-exceptions")
	@Validated
	public static class TestExceptionController {

		@PostMapping("/validation")
		void validation(@Valid @RequestBody ValidationRequest request) {
		}

		@GetMapping("/business")
		void business() {
			throw new TestBusinessException(HttpStatus.NOT_FOUND, "Business resource not found", "BUSINESS_NOT_FOUND");
		}

		@GetMapping("/business-without-code")
		void businessWithoutCode() {
			throw new TestBusinessExceptionWithoutErrorCode(HttpStatus.BAD_REQUEST, "Business validation failed");
		}

		@GetMapping("/business-server-error")
		void businessServerError() {
			throw new TestBusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "Business server error", "BUSINESS_SERVER_ERROR");
		}

		@GetMapping("/type-mismatch")
		void typeMismatch(@RequestParam Integer count) {
		}

		@GetMapping("/missing-param")
		void missingParam(@RequestParam int page) {
		}


		@GetMapping("/missing-header")
		void missingHeader(@RequestHeader("X-Trace-Id") String traceId) {
		}

		@GetMapping("/access-denied")
		void accessDenied() {
			throw new AccessDeniedException("forbidden");
		}

		@GetMapping("/unhandled")
		void unhandled() {
			throw new IllegalStateException("boom");
		}
	}

	@TermsAccepted
	record ValidationRequest(@NotBlank(message = "name must not be blank") String name, boolean termsAccepted) {
	}

	@Target(ElementType.TYPE)
	@Retention(RetentionPolicy.RUNTIME)
	@Constraint(validatedBy = TermsAcceptedValidator.class)
	@interface TermsAccepted {
		String message() default "terms must be accepted";

		Class<?>[] groups() default {};

		Class<? extends Payload>[] payload() default {};
	}

	static class TermsAcceptedValidator implements ConstraintValidator<TermsAccepted, ValidationRequest> {
		@Override
		public boolean isValid(ValidationRequest value, ConstraintValidatorContext context) {
			if (value == null || value.termsAccepted()) {
				return true;
			}

			context.disableDefaultConstraintViolation();
			context.buildConstraintViolationWithTemplate("terms must be accepted").addConstraintViolation();
			return false;
		}
	}

	static class TestBusinessException extends BusinessException {
		TestBusinessException(HttpStatus status, String message, String errorCode) {
			super(status, message, errorCode);
		}
	}

	static class TestBusinessExceptionWithoutErrorCode extends BusinessException {
		TestBusinessExceptionWithoutErrorCode(HttpStatus status, String message) {
			super(status, message);
		}
	}
}
