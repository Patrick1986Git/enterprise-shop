package com.company.shop.module.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.common.model.AuditableEntity;
import com.company.shop.module.cart.service.CartService;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.Payment;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.company.shop.module.order.repository.StripeWebhookEventRepository;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.persistence.support.PostgresContainerSupport;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
@AutoConfigureTestDatabase(replace = Replace.NONE)
class StripeWebhookPersistenceIT extends PostgresContainerSupport {

	private static final String WEBHOOK_URL = "/api/v1/webhooks/stripe";
	private static final String STRIPE_SIGNATURE = "sig";
	private static final String WEBHOOK_PAYLOAD = "payload";
	private static final String SUCCEEDED_EVENT_TYPE = "payment_intent.succeeded";
	private static final String UNSUPPORTED_EVENT_TYPE = "payment_intent.processing";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private StripeWebhookEventRepository stripeWebhookEventRepository;

	@MockitoBean
	private CartService cartService;

	@Test
	void handleStripeWebhook_shouldPersistOrderAndPaymentAsCompletedWhenPaymentIntentSucceeded() throws Exception {
		SeededOrder seededOrder = seedOrderWithPayment(BigDecimal.valueOf(25), "pi_success");

		Event event = succeededEvent(
				"evt_persistence_success",
				seededOrder.order().getId().toString(),
				"pi_success",
				2500L,
				"pln");

		try (var webhookStatic = mockStatic(Webhook.class)) {
			webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_placeholder"))
					.thenReturn(event);

			mockMvc.perform(post(WEBHOOK_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.header("Stripe-Signature", "sig")
					.content("payload"))
					.andExpect(status().isOk());
		}

		Order updatedOrder = orderRepository.findById(seededOrder.order().getId()).orElseThrow();
		Payment updatedPayment = paymentRepository.findByOrderId(seededOrder.order().getId()).orElseThrow();

		assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
		assertStripeWebhookEventPersisted("evt_persistence_success", SUCCEEDED_EVENT_TYPE);
		verify(cartService).clearCartForUser(seededOrder.user().getId());
	}

	@Test
	void handleStripeWebhook_shouldKeepPersistenceStateUnchangedWhenWebhookTypeIsUnsupported() throws Exception {
		SeededOrder seededOrder = seedOrderWithPayment(BigDecimal.valueOf(30), "pi_pending");

		Event event = mock(Event.class);
		when(event.getId()).thenReturn("evt_persistence_unsupported");
		when(event.getType()).thenReturn(UNSUPPORTED_EVENT_TYPE);

		try (var webhookStatic = mockStatic(Webhook.class)) {
			webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_placeholder"))
					.thenReturn(event);

			mockMvc.perform(post(WEBHOOK_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.header("Stripe-Signature", "sig")
					.content("payload"))
					.andExpect(status().isOk());
		}

		Order unchangedOrder = orderRepository.findById(seededOrder.order().getId()).orElseThrow();
		Payment unchangedPayment = paymentRepository.findByOrderId(seededOrder.order().getId()).orElseThrow();

		assertThat(unchangedOrder.getStatus()).isEqualTo(OrderStatus.NEW);
		assertThat(unchangedPayment.getStatus()).isEqualTo(PaymentStatus.PENDING);
		assertStripeWebhookEventPersisted("evt_persistence_unsupported", UNSUPPORTED_EVENT_TYPE);
		verifyNoInteractions(cartService);
	}

	@Test
	void handleStripeWebhook_shouldIgnoreSucceededEventWhenOrderAlreadyPaid() throws Exception {
		SeededOrder seededOrder = seedOrderWithPayment(BigDecimal.valueOf(40), "pi_duplicate");
		markOrderAndPaymentAsCompleted(seededOrder.order().getId());

		Event event = succeededEvent(
				"evt_persistence_already_paid",
				seededOrder.order().getId().toString(),
				"pi_duplicate",
				4000L,
				"pln");

		try (var webhookStatic = mockStatic(Webhook.class)) {
			webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_placeholder"))
					.thenReturn(event);

			mockMvc.perform(post(WEBHOOK_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.header("Stripe-Signature", "sig")
					.content("payload"))
					.andExpect(status().isOk());
		}

		Order unchangedOrder = orderRepository.findById(seededOrder.order().getId()).orElseThrow();
		Payment unchangedPayment = paymentRepository.findByOrderId(seededOrder.order().getId()).orElseThrow();

		assertThat(unchangedOrder.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(unchangedPayment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
		assertStripeWebhookEventPersisted("evt_persistence_already_paid", SUCCEEDED_EVENT_TYPE);
		verifyNoInteractions(cartService);
	}

	@Test
	void handleStripeWebhook_shouldBeIdempotentForDuplicateEventId() throws Exception {
		SeededOrder seededOrder = seedOrderWithPayment(BigDecimal.valueOf(75), "pi_duplicate_event");
		Event event = succeededEvent(
				"evt_persistence_duplicate",
				seededOrder.order().getId().toString(),
				"pi_duplicate_event",
				7500L,
				"pln");

		try (var webhookStatic = mockStatic(Webhook.class)) {
			webhookStatic.when(() -> Webhook.constructEvent(WEBHOOK_PAYLOAD, STRIPE_SIGNATURE, "whsec_placeholder"))
					.thenReturn(event);

			performWebhookRequest().andExpect(status().isOk());
			performWebhookRequest().andExpect(status().isOk());
		}

		Order orderAfterRequests = orderRepository.findById(seededOrder.order().getId()).orElseThrow();
		Payment paymentAfterRequests = paymentRepository.findByOrderId(seededOrder.order().getId()).orElseThrow();

		assertThat(orderAfterRequests.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(paymentAfterRequests.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
		assertStripeWebhookEventPersisted("evt_persistence_duplicate", SUCCEEDED_EVENT_TYPE);
		verify(cartService, times(1)).clearCartForUser(seededOrder.user().getId());
	}

	@Test
	void handleStripeWebhook_shouldKeepPersistenceStateUnchangedWhenPaymentAmountDoesNotMatchOrderTotal() throws Exception {
		SeededOrder seededOrder = seedOrderWithPayment(BigDecimal.valueOf(35), "pi_amount_mismatch");

		Event event = succeededEvent(
				"evt_persistence_amount_mismatch",
				seededOrder.order().getId().toString(),
				"pi_amount_mismatch",
				3499L,
				"pln");

		try (var webhookStatic = mockStatic(Webhook.class)) {
			webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_placeholder"))
					.thenReturn(event);

			mockMvc.perform(post(WEBHOOK_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.header("Stripe-Signature", "sig")
					.content("payload"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("STRIPE_WEBHOOK_SIGNATURE_INVALID"));
		}

		Order unchangedOrder = orderRepository.findById(seededOrder.order().getId()).orElseThrow();
		Payment unchangedPayment = paymentRepository.findByOrderId(seededOrder.order().getId()).orElseThrow();

		assertThat(unchangedOrder.getStatus()).isEqualTo(OrderStatus.NEW);
		assertThat(unchangedPayment.getStatus()).isEqualTo(PaymentStatus.PENDING);
		assertStripeWebhookEventNotPersisted("evt_persistence_amount_mismatch");
		verifyNoInteractions(cartService);
	}

	@Test
	void handleStripeWebhook_shouldKeepPersistenceStateUnchangedWhenPaymentCurrencyDoesNotMatchOrderCurrency() throws Exception {
		SeededOrder seededOrder = seedOrderWithPayment(BigDecimal.valueOf(55), "pi_currency_mismatch");

		Event event = succeededEvent(
				"evt_persistence_currency_mismatch",
				seededOrder.order().getId().toString(),
				"pi_currency_mismatch",
				5500L,
				"eur");

		try (var webhookStatic = mockStatic(Webhook.class)) {
			webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_placeholder"))
					.thenReturn(event);

			mockMvc.perform(post(WEBHOOK_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.header("Stripe-Signature", "sig")
					.content("payload"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("STRIPE_WEBHOOK_SIGNATURE_INVALID"));
		}

		Order unchangedOrder = orderRepository.findById(seededOrder.order().getId()).orElseThrow();
		Payment unchangedPayment = paymentRepository.findByOrderId(seededOrder.order().getId()).orElseThrow();

		assertThat(unchangedOrder.getStatus()).isEqualTo(OrderStatus.NEW);
		assertThat(unchangedPayment.getStatus()).isEqualTo(PaymentStatus.PENDING);
		assertStripeWebhookEventNotPersisted("evt_persistence_currency_mismatch");
		verifyNoInteractions(cartService);
	}

	@Test
	void handleStripeWebhook_shouldKeepPersistenceStateUnchangedWhenPaymentIntentMetadataDoesNotContainOrderId() throws Exception {
		SeededOrder seededOrder = seedOrderWithPayment(BigDecimal.valueOf(45), "pi_missing_order_metadata");

		Event event = succeededEventWithoutOrderIdMetadata(
				"evt_persistence_missing_order_metadata",
				"pi_missing_order_metadata",
				4500L,
				"pln");

		try (var webhookStatic = mockStatic(Webhook.class)) {
			webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_placeholder"))
					.thenReturn(event);

			mockMvc.perform(post(WEBHOOK_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.header("Stripe-Signature", "sig")
					.content("payload"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("STRIPE_WEBHOOK_SIGNATURE_INVALID"));
		}

		Order unchangedOrder = orderRepository.findById(seededOrder.order().getId()).orElseThrow();
		Payment unchangedPayment = paymentRepository.findByOrderId(seededOrder.order().getId()).orElseThrow();

		assertThat(unchangedOrder.getStatus()).isEqualTo(OrderStatus.NEW);
		assertThat(unchangedPayment.getStatus()).isEqualTo(PaymentStatus.PENDING);
		assertStripeWebhookEventNotPersisted("evt_persistence_missing_order_metadata");
		verifyNoInteractions(cartService);
	}

	@Test
	void handleStripeWebhook_shouldKeepPersistenceStateUnchangedWhenProviderPaymentIdDoesNotMatchWebhookPaymentIntentId() throws Exception {
		SeededOrder seededOrder = seedOrderWithPayment(BigDecimal.valueOf(65), "pi_stored_id");

		Event event = succeededEvent(
				"evt_persistence_provider_id_mismatch",
				seededOrder.order().getId().toString(),
				"pi_other_id",
				6500L,
				"pln");

		try (var webhookStatic = mockStatic(Webhook.class)) {
			webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_placeholder"))
					.thenReturn(event);

			mockMvc.perform(post(WEBHOOK_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.header("Stripe-Signature", "sig")
					.content("payload"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("STRIPE_WEBHOOK_SIGNATURE_INVALID"));
		}

		Order unchangedOrder = orderRepository.findById(seededOrder.order().getId()).orElseThrow();
		Payment unchangedPayment = paymentRepository.findByOrderId(seededOrder.order().getId()).orElseThrow();

		assertThat(unchangedOrder.getStatus()).isEqualTo(OrderStatus.NEW);
		assertThat(unchangedPayment.getStatus()).isEqualTo(PaymentStatus.PENDING);
		assertStripeWebhookEventNotPersisted("evt_persistence_provider_id_mismatch");
		verifyNoInteractions(cartService);
	}

	private SeededOrder seedOrderWithPayment(BigDecimal orderAmount, String paymentIntentId) {
		User user = userRepository.save(new User("stripe-webhook-" + paymentIntentId + "@example.com", "encoded-pass", "Test", "User"));

		Order order = new Order(user.getId(), user.getEmail());
		setOrderTotal(order, orderAmount);
		Order savedOrder = orderRepository.save(order);

		Payment payment = new Payment(savedOrder, "STRIPE", orderAmount);
		payment.attachProviderPayment(paymentIntentId, "cs_" + paymentIntentId);
		paymentRepository.save(payment);

		return new SeededOrder(user, savedOrder);
	}

	private Event succeededEvent(String eventId, String orderId, String paymentIntentId, long amountReceived, String currency) {
		Event event = mock(Event.class);
		EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
		PaymentIntent paymentIntent = mock(PaymentIntent.class);

		when(event.getId()).thenReturn(eventId);
		when(event.getType()).thenReturn(SUCCEEDED_EVENT_TYPE);
		when(event.getDataObjectDeserializer()).thenReturn(deserializer);
		when(deserializer.getObject()).thenReturn(java.util.Optional.of(paymentIntent));
		when(paymentIntent.getMetadata()).thenReturn(Map.of("orderId", orderId));
		when(paymentIntent.getAmountReceived()).thenReturn(amountReceived);
		when(paymentIntent.getCurrency()).thenReturn(currency);
		when(paymentIntent.getId()).thenReturn(paymentIntentId);

		return event;
	}

	private Event succeededEventWithoutOrderIdMetadata(
			String eventId,
			String paymentIntentId,
			long amountReceived,
			String currency) {
		Event event = mock(Event.class);
		EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
		PaymentIntent paymentIntent = mock(PaymentIntent.class);

		when(event.getId()).thenReturn(eventId);
		when(event.getType()).thenReturn(SUCCEEDED_EVENT_TYPE);
		when(event.getDataObjectDeserializer()).thenReturn(deserializer);
		when(deserializer.getObject()).thenReturn(java.util.Optional.of(paymentIntent));
		when(paymentIntent.getMetadata()).thenReturn(Map.of());
		when(paymentIntent.getAmountReceived()).thenReturn(amountReceived);
		when(paymentIntent.getCurrency()).thenReturn(currency);
		when(paymentIntent.getId()).thenReturn(paymentIntentId);

		return event;
	}

	private void markOrderAndPaymentAsCompleted(java.util.UUID orderId) {
		Order order = orderRepository.findById(orderId).orElseThrow();
		order.markAsPaid();
		orderRepository.save(order);

		Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
		payment.markAsCompleted();
		paymentRepository.save(payment);
	}

	private void setOrderTotal(Order order, BigDecimal amount) {
		try {
			Field totalAmountField = Order.class.getDeclaredField("totalAmount");
			totalAmountField.setAccessible(true);
			totalAmountField.set(order, amount);

			Field createdAtField = AuditableEntity.class.getDeclaredField("createdAt");
			createdAtField.setAccessible(true);
			createdAtField.set(order, LocalDateTime.now());
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Unable to set order amount for integration test", e);
		}
	}

	private org.springframework.test.web.servlet.ResultActions performWebhookRequest() throws Exception {
		return mockMvc.perform(post(WEBHOOK_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.header("Stripe-Signature", STRIPE_SIGNATURE)
				.content(WEBHOOK_PAYLOAD));
	}

	private void assertStripeWebhookEventPersisted(String expectedEventId, String expectedEventType) {
		assertThat(stripeWebhookEventRepository.findAll())
				.filteredOn(savedEvent -> savedEvent.getStripeEventId().equals(expectedEventId))
				.singleElement()
				.satisfies(savedEvent -> assertThat(savedEvent.getEventType()).isEqualTo(expectedEventType));
	}

	private void assertStripeWebhookEventNotPersisted(String eventId) {
		assertThat(stripeWebhookEventRepository.findAll())
				.noneMatch(savedEvent -> savedEvent.getStripeEventId().equals(eventId));
	}

	private record SeededOrder(User user, Order order) {
	}
}