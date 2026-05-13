package com.company.shop.module.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.company.shop.common.model.BaseEntity;
import com.company.shop.module.cart.service.CartService;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.order.dto.PaymentIntentResponseDTO;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderItem;
import com.company.shop.module.order.entity.Payment;
import com.company.shop.module.order.exception.PaymentAlreadyCompletedException;
import com.company.shop.module.order.exception.PaymentProcessingException;
import com.company.shop.module.order.exception.PaymentRecordNotFoundException;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.user.entity.User;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplCreateIntentTest {

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private CartService cartService;

	@Mock
	private StripeWebhookEventRegistrar stripeWebhookEventRegistrar;

	private SimpleMeterRegistry meterRegistry;
	private PaymentServiceImpl service;

	@BeforeEach
	void setUp() {
		meterRegistry = new SimpleMeterRegistry();
		service = new PaymentServiceImpl(orderRepository, paymentRepository, cartService, stripeWebhookEventRegistrar,
				meterRegistry);
		setField(service, "publicKey", "pk_test_123");
	}

	@Test
	void createPaymentIntent_shouldReturnExistingClientSecretWhenProviderPaymentAlreadyAttached() {
		Order order = orderWithTotal(BigDecimal.valueOf(39.98));
		Payment payment = new Payment(order, "STRIPE", order.getTotalAmount());
		payment.attachProviderPayment("pi_existing", "cs_existing");

		when(paymentRepository.findByOrderIdForUpdate(order.getId())).thenReturn(Optional.of(payment));

		PaymentIntentResponseDTO result = service.createPaymentIntent(order);

		assertThat(result.clientSecret()).isEqualTo("cs_existing");
		assertThat(result.publishableKey()).isEqualTo("pk_test_123");
		assertThat(meterRegistry.get("shop.payment_intent.total").tag("result", "reused").counter().count()).isEqualTo(1);

		verify(paymentRepository).findByOrderIdForUpdate(order.getId());
		verify(paymentRepository, never()).save(any(Payment.class));
	}

	@Test
	void createPaymentIntent_shouldThrowWhenPaymentRecordMissing() {
		Order order = orderWithTotal(BigDecimal.valueOf(20));
		when(paymentRepository.findByOrderIdForUpdate(order.getId())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.createPaymentIntent(order)).isInstanceOf(PaymentRecordNotFoundException.class)
				.hasMessageContaining(order.getId().toString());
		assertThat(meterRegistry.get("shop.payment_intent.total").tag("result", "failed").counter().count()).isEqualTo(1);

		verify(paymentRepository).findByOrderIdForUpdate(order.getId());
		verify(paymentRepository, never()).save(any(Payment.class));
	}

	@Test
	void createPaymentIntent_shouldThrowWhenPaymentAlreadyCompleted() {
		Order order = orderWithTotal(BigDecimal.valueOf(20));
		Payment payment = new Payment(order, "STRIPE", order.getTotalAmount());
		payment.markAsCompleted();

		when(paymentRepository.findByOrderIdForUpdate(order.getId())).thenReturn(Optional.of(payment));

		assertThatThrownBy(() -> service.createPaymentIntent(order))
				.isInstanceOf(PaymentAlreadyCompletedException.class).hasMessageContaining(order.getId().toString());
		assertThat(meterRegistry.get("shop.payment_intent.total").tag("result", "failed").counter().count()).isEqualTo(1);

		verify(paymentRepository).findByOrderIdForUpdate(order.getId());
		verify(paymentRepository, never()).save(any(Payment.class));
	}

	@Test
	void createPaymentIntent_shouldWrapStripeErrorIntoPaymentProcessingException() {
		Order order = orderWithTotal(BigDecimal.valueOf(20));
		Payment payment = new Payment(order, "STRIPE", order.getTotalAmount());

		when(paymentRepository.findByOrderIdForUpdate(order.getId())).thenReturn(Optional.of(payment));

		try (MockedStatic<PaymentIntent> paymentIntentStatic = mockStatic(PaymentIntent.class)) {
			paymentIntentStatic
					.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
					.thenThrow(new RuntimeException("stripe down"));

			assertThatThrownBy(() -> service.createPaymentIntent(order)).isInstanceOf(PaymentProcessingException.class)
					.hasMessageContaining(order.getId().toString());
			assertThat(meterRegistry.get("shop.payment_intent.total").tag("result", "failed").counter().count()).isEqualTo(1);

			verify(paymentRepository).findByOrderIdForUpdate(order.getId());
			verify(paymentRepository, never()).save(any(Payment.class));
		}
	}

	@Test
	void createPaymentIntent_shouldCreateStripeIntentPersistProviderFieldsAndReturnDto() {
		Order order = orderWithTotal(BigDecimal.valueOf(24.50));
		Payment payment = new Payment(order, "STRIPE", order.getTotalAmount());

		when(paymentRepository.findByOrderIdForUpdate(order.getId())).thenReturn(Optional.of(payment));
		when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

		PaymentIntent stripeIntent = mock(PaymentIntent.class);
		when(stripeIntent.getId()).thenReturn("pi_123");
		when(stripeIntent.getClientSecret()).thenReturn("cs_123");

		try (MockedStatic<PaymentIntent> paymentIntentStatic = mockStatic(PaymentIntent.class)) {
			paymentIntentStatic
					.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
					.thenReturn(stripeIntent);

			PaymentIntentResponseDTO result = service.createPaymentIntent(order);

			assertThat(result.clientSecret()).isEqualTo("cs_123");
			assertThat(result.publishableKey()).isEqualTo("pk_test_123");

			ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
			verify(paymentRepository).save(paymentCaptor.capture());
			Payment savedPayment = paymentCaptor.getValue();
			assertThat(savedPayment.getProviderPaymentId()).isEqualTo("pi_123");
			assertThat(savedPayment.getClientSecret()).isEqualTo("cs_123");
			assertThat(savedPayment.getAmount()).isEqualByComparingTo("24.50");
			assertThat(meterRegistry.get("shop.payment_intent.total").tag("result", "created").counter().count()).isEqualTo(1);

			verify(paymentRepository).findByOrderIdForUpdate(order.getId());
			paymentIntentStatic.verify(
					() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)));
		}
	}

	private Order orderWithTotal(BigDecimal unitPrice) {
		User user = new User("john@example.com", "encoded", "John", "Doe");
		setEntityId(user, UUID.randomUUID());

		Category category = new Category("Accessories", "accessories", "desc");
		Product product = new Product("Cable", "cable", "SKU-1", "desc", unitPrice, 10, category);
		setEntityId(product, UUID.randomUUID());

		Order order = new Order(user);
		setEntityId(order, UUID.randomUUID());
		order.addItem(new OrderItem(product, 1, unitPrice));
		return order;
	}

	private void setEntityId(Object entity, UUID id) {
		try {
			Field field = BaseEntity.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(entity, id);
		} catch (ReflectiveOperationException ex) {
			throw new RuntimeException(ex);
		}
	}

	private void setField(Object target, String fieldName, Object value) {
		try {
			Field field = target.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException ex) {
			throw new RuntimeException(ex);
		}
	}
}
