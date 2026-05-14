package com.company.shop.module.order.controller;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.common.exception.GlobalExceptionHandler;
import com.company.shop.module.order.exception.WebhookProcessingException;
import com.company.shop.module.order.exception.WebhookSignatureInvalidException;
import com.company.shop.module.order.service.PaymentService;
import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.company.shop.support.TestMeterRegistryConfig;

@WebMvcTest(
		controllers = StripeWebhookController.class,
		excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class)
)
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
@Import({ GlobalExceptionHandler.class, TestMeterRegistryConfig.class })
class StripeWebhookControllerWebMvcTest {

	private static final String WEBHOOK_URL = "/api/v1/webhooks/stripe";
	private static final String STRIPE_SIGNATURE = "Stripe-Signature";
	private static final String TIMESTAMP_REGEX = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?$";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PaymentService paymentService;

	@Test
	void handleStripeWebhook_shouldReturnOkAndDelegateWhenRequestIsValid() throws Exception {
		String payload = "{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\"}";
		String signature = "sig_test";

		mockMvc.perform(post(WEBHOOK_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.header(STRIPE_SIGNATURE, signature)
				.content(payload))
				.andExpect(status().isOk())
				.andExpect(content().string(""));

		verify(paymentService).handleWebhook(eq(payload), eq(signature));
	}

	@Test
	void handleStripeWebhook_shouldReturnBadRequestWhenStripeSignatureHeaderMissing() throws Exception {
		String payload = "{\"id\":\"evt_1\"}";

		mockMvc.perform(post(WEBHOOK_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Missing required request header: Stripe-Signature"))
				.andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
				.andExpect(jsonPath("$.errors.parameter").value("Stripe-Signature"))
				.andExpect(jsonPath("$.errors.expectedType").value("String"))
				.andExpect(jsonPath("$.timestamp", matchesPattern(TIMESTAMP_REGEX)));

		verifyNoInteractions(paymentService);
	}

	@Test
	void handleStripeWebhook_shouldReturnBadRequestWhenRequestBodyMissing() throws Exception {
		String signature = "sig_test";

		mockMvc.perform(post(WEBHOOK_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.header(STRIPE_SIGNATURE, signature))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Request body contains invalid values."))
				.andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
				.andExpect(jsonPath("$.timestamp", matchesPattern(TIMESTAMP_REGEX)));

		verifyNoInteractions(paymentService);
	}

	@Test
	void handleStripeWebhook_shouldReturnBadRequestApiErrorWhenSignatureInvalid() throws Exception {
		String payload = "{\"id\":\"evt_1\"}";
		String signature = "sig_invalid";

		doThrow(new WebhookSignatureInvalidException())
				.when(paymentService)
				.handleWebhook(eq(payload), eq(signature));

		mockMvc.perform(post(WEBHOOK_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.header(STRIPE_SIGNATURE, signature)
				.content(payload))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message", not(emptyOrNullString())))
				.andExpect(jsonPath("$.errorCode").value("STRIPE_WEBHOOK_SIGNATURE_INVALID"))
				.andExpect(jsonPath("$.errors").value(nullValue()))
				.andExpect(jsonPath("$.timestamp", matchesPattern(TIMESTAMP_REGEX)));
	}

	@Test
	void handleStripeWebhook_shouldReturnInternalServerErrorApiErrorWhenWebhookProcessingFails() throws Exception {
		String payload = "{\"id\":\"evt_2\"}";
		String signature = "sig_test";

		doThrow(new WebhookProcessingException("Unable to process Stripe webhook event."))
				.when(paymentService)
				.handleWebhook(eq(payload), eq(signature));

		mockMvc.perform(post(WEBHOOK_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.header(STRIPE_SIGNATURE, signature)
				.content(payload))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.status").value(500))
				.andExpect(jsonPath("$.message", not(emptyOrNullString())))
				.andExpect(jsonPath("$.errorCode").value("STRIPE_WEBHOOK_PROCESSING_ERROR"))
				.andExpect(jsonPath("$.errors").value(nullValue()))
				.andExpect(jsonPath("$.timestamp", matchesPattern(TIMESTAMP_REGEX)));
	}

	@Test
	void handleStripeWebhook_shouldReturnInternalServerErrorFallbackWhenUnexpectedExceptionOccurs() throws Exception {
		String payload = "{\"id\":\"evt_3\"}";
		String signature = "sig_test";

		doThrow(new RuntimeException("boom"))
				.when(paymentService)
				.handleWebhook(eq(payload), eq(signature));

		mockMvc.perform(post(WEBHOOK_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.header(STRIPE_SIGNATURE, signature)
				.content(payload))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.status").value(500))
				.andExpect(jsonPath("$.message")
						.value("An unexpected server error occurred. Please contact support if the problem persists."))
				.andExpect(jsonPath("$.errorCode").value(nullValue()))
				.andExpect(jsonPath("$.errors").value(nullValue()))
				.andExpect(jsonPath("$.timestamp", matchesPattern(TIMESTAMP_REGEX)));
	}
}
