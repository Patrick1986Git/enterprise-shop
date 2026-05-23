package com.company.shop.module.order.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.module.order.service.PaymentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/webhooks/stripe")
@Tag(name = "Webhooks", description = "Stripe payment webhook endpoint.")
public class StripeWebhookController {

	private final PaymentService paymentService;

	public StripeWebhookController(PaymentService paymentService) {
		this.paymentService = paymentService;
	}

	@PostMapping
	@Operation(summary = "Handle Stripe webhook events")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Webhook processed successfully."),
			@ApiResponse(responseCode = "400", description = "Invalid payload or signature."),
			@ApiResponse(responseCode = "404", description = "Order associated with the payment was not found."),
			@ApiResponse(responseCode = "409", description = "Payment or order state conflict.")
	})
	public ResponseEntity<Void> handleStripeWebhook(@RequestBody String payload,
			@RequestHeader("Stripe-Signature") String sigHeader) {

		paymentService.handleWebhook(payload, sigHeader);
		return ResponseEntity.ok().build();
	}
}
