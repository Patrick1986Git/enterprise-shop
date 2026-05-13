package com.company.shop.module.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.company.shop.common.model.BaseEntity;
import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.service.CartService;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.order.dto.OrderCheckoutRequestDTO;
import com.company.shop.module.order.dto.OrderResponseDTO;
import com.company.shop.module.order.dto.PaymentIntentResponseDTO;
import com.company.shop.module.order.entity.DiscountCode;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.Payment;
import com.company.shop.module.order.exception.DiscountCodeInvalidException;
import com.company.shop.module.order.exception.EmptyCartCheckoutException;
import com.company.shop.module.order.exception.OrderInsufficientStockException;
import com.company.shop.module.order.mapper.OrderMapper;
import com.company.shop.module.order.repository.DiscountCodeRepository;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.exception.ProductNotFoundException;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.service.UserService;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplCheckoutTest {

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private ProductRepository productRepository;

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private DiscountCodeRepository discountCodeRepository;

	@Mock
	private UserService userService;

	@Mock
	private CartService cartService;

	@Mock
	private OrderMapper orderMapper;

	@Mock
	private PaymentService paymentService;

	private SimpleMeterRegistry meterRegistry;
	private OrderServiceImpl service;

	@BeforeEach
	void setUp() {
		meterRegistry = new SimpleMeterRegistry();
		service = new OrderServiceImpl(orderRepository, productRepository, paymentRepository, discountCodeRepository,
				userService, cartService, orderMapper, paymentService, meterRegistry);
	}

	@Nested
	class PlaceOrderFromCartHappyPathTests {

		@Test
		void placeOrderFromCart_shouldCreateOrderPaymentAndReturnDetailedResponse() {
			User user = user();
			Product firstProduct = product(1, 10, BigDecimal.valueOf(10));
			Product secondProduct = product(2, 8, BigDecimal.valueOf(5));
			Cart cart = cart(user, firstProduct, 2, secondProduct, 3);

			OrderCheckoutRequestDTO request = new OrderCheckoutRequestDTO(null, "deliver quickly");
			PaymentIntentResponseDTO paymentIntent = new PaymentIntentResponseDTO("pi_secret", "pk_test");
			LocalDateTime createdAt = LocalDateTime.now();
			UUID savedOrderId = UUID.randomUUID();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartService.getCartEntityForUser(user.getId())).thenReturn(cart);
			when(productRepository.findByIdWithLock(firstProduct.getId())).thenReturn(Optional.of(firstProduct));
			when(productRepository.findByIdWithLock(secondProduct.getId())).thenReturn(Optional.of(secondProduct));
			when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
				Order order = invocation.getArgument(0);
				setEntityId(order, savedOrderId);
				return order;
			});
			when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
			when(paymentService.createPaymentIntent(any(Order.class))).thenReturn(paymentIntent);
			when(orderMapper.toDto(any(Order.class))).thenReturn(
					new OrderResponseDTO(savedOrderId, OrderStatus.NEW, BigDecimal.valueOf(35), createdAt, null));

			OrderResponseDTO result = service.placeOrderFromCart(request);

			assertThat(result.id()).isEqualTo(savedOrderId);
			assertThat(result.status()).isEqualTo(OrderStatus.NEW);
			assertThat(result.totalAmount()).isEqualByComparingTo("35.00");
			assertThat(result.createdAt()).isEqualTo(createdAt);
			assertThat(result.paymentInfo()).isEqualTo(paymentIntent);

			assertThat(firstProduct.getStock()).isEqualTo(8);
			assertThat(secondProduct.getStock()).isEqualTo(5);

			ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
			verify(orderRepository).save(orderCaptor.capture());
			Order savedOrder = orderCaptor.getValue();
			assertThat(savedOrder.getUser()).isEqualTo(user);
			assertThat(savedOrder.getItems()).hasSize(2);
			assertThat(savedOrder.getItems().get(0).getProduct()).isEqualTo(firstProduct);
			assertThat(savedOrder.getItems().get(0).getQuantity()).isEqualTo(2);
			assertThat(savedOrder.getItems().get(0).getPrice()).isEqualByComparingTo("10.00");
			assertThat(savedOrder.getItems().get(1).getProduct()).isEqualTo(secondProduct);
			assertThat(savedOrder.getItems().get(1).getQuantity()).isEqualTo(3);
			assertThat(savedOrder.getItems().get(1).getPrice()).isEqualByComparingTo("5.00");
			assertThat(savedOrder.getTotalAmount()).isEqualByComparingTo("35.00");

			ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
			verify(paymentRepository).save(paymentCaptor.capture());
			Payment savedPayment = paymentCaptor.getValue();
			assertThat(savedPayment.getOrder()).isEqualTo(savedOrder);
			assertThat(savedPayment.getProvider()).isEqualTo("STRIPE");
			assertThat(savedPayment.getAmount()).isEqualByComparingTo("35.00");

			verify(paymentService).createPaymentIntent(savedOrder);
			verify(orderMapper).toDto(savedOrder);
			verify(discountCodeRepository, never()).findByCodeIgnoreCase(any(String.class));
			assertThat(meterRegistry.get("shop.checkout.total").tag("result", "attempt").counter().count()).isEqualTo(1);
			assertThat(meterRegistry.get("shop.checkout.total").tag("result", "success").counter().count()).isEqualTo(1);
		}

		@Test
		void placeOrderFromCart_shouldApplyDiscountWhenValidCodeProvided() {
			User user = user();
			Product product = product(3, 10, BigDecimal.valueOf(100));
			Cart cart = cart(user, product, 1);
			DiscountCode discountCode = mock(DiscountCode.class);
			when(discountCode.canBeUsed()).thenReturn(true);
			when(discountCode.getDiscountPercent()).thenReturn(10);

			OrderCheckoutRequestDTO request = new OrderCheckoutRequestDTO(" SAVE10 ", null);
			PaymentIntentResponseDTO paymentIntent = new PaymentIntentResponseDTO("pi_discount", "pk_discount");
			UUID savedOrderId = UUID.randomUUID();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartService.getCartEntityForUser(user.getId())).thenReturn(cart);
			when(productRepository.findByIdWithLock(product.getId())).thenReturn(Optional.of(product));
			when(discountCodeRepository.findByCodeIgnoreCase("SAVE10"))
					.thenReturn(Optional.of(discountCode));
			when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
				Order order = invocation.getArgument(0);
				setEntityId(order, savedOrderId);
				return order;
			});
			when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
			when(paymentService.createPaymentIntent(any(Order.class))).thenReturn(paymentIntent);
			when(orderMapper.toDto(any(Order.class))).thenReturn(new OrderResponseDTO(savedOrderId, OrderStatus.NEW,
					BigDecimal.valueOf(90), LocalDateTime.now(), null));

			OrderResponseDTO result = service.placeOrderFromCart(request);

			assertThat(result.totalAmount()).isEqualByComparingTo("90.00");
			assertThat(result.paymentInfo()).isEqualTo(paymentIntent);
			assertThat(product.getStock()).isEqualTo(9);

			ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
			verify(orderRepository).save(orderCaptor.capture());
			Order savedOrder = orderCaptor.getValue();
			assertThat(savedOrder.getTotalAmount()).isEqualByComparingTo("90.00");

			ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
			verify(paymentRepository).save(paymentCaptor.capture());
			assertThat(paymentCaptor.getValue().getAmount()).isEqualByComparingTo("90.00");

			verify(discountCodeRepository).findByCodeIgnoreCase("SAVE10");
			verify(paymentService).createPaymentIntent(savedOrder);
			verify(orderMapper).toDto(savedOrder);
		}

		@Test
		void placeOrderFromCart_shouldIgnoreBlankDiscountCode() {
			User user = user();
			Product product = product(4, 4, BigDecimal.valueOf(20));
			Cart cart = cart(user, product, 2);

			OrderCheckoutRequestDTO request = new OrderCheckoutRequestDTO("   ", "no notes");
			PaymentIntentResponseDTO paymentIntent = new PaymentIntentResponseDTO("pi_blank", "pk_blank");
			UUID savedOrderId = UUID.randomUUID();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartService.getCartEntityForUser(user.getId())).thenReturn(cart);
			when(productRepository.findByIdWithLock(product.getId())).thenReturn(Optional.of(product));
			when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
				Order order = invocation.getArgument(0);
				setEntityId(order, savedOrderId);
				return order;
			});
			when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
			when(paymentService.createPaymentIntent(any(Order.class))).thenReturn(paymentIntent);
			when(orderMapper.toDto(any(Order.class))).thenReturn(new OrderResponseDTO(savedOrderId, OrderStatus.NEW,
					BigDecimal.valueOf(40), LocalDateTime.now(), null));

			OrderResponseDTO result = service.placeOrderFromCart(request);

			assertThat(result.totalAmount()).isEqualByComparingTo("40.00");
			assertThat(product.getStock()).isEqualTo(2);

			ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
			verify(orderRepository).save(orderCaptor.capture());
			Order savedOrder = orderCaptor.getValue();
			assertThat(savedOrder.getTotalAmount()).isEqualByComparingTo("40.00");

			verify(discountCodeRepository, never()).findByCodeIgnoreCase(any(String.class));
			verify(paymentService).createPaymentIntent(savedOrder);
			verify(orderMapper).toDto(savedOrder);
		}
	}

	@Nested
	class PlaceOrderFromCartGuardClauseTests {

		@Test
		void placeOrderFromCart_shouldThrowWhenCartIsEmpty() {
			User user = user();
			Cart cart = new Cart(user);

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartService.getCartEntityForUser(user.getId())).thenReturn(cart);

			assertThatThrownBy(() -> service.placeOrderFromCart(new OrderCheckoutRequestDTO(null, null)))
					.isInstanceOf(EmptyCartCheckoutException.class);
			assertThat(meterRegistry.get("shop.checkout.total").tag("result", "attempt").counter().count()).isEqualTo(1);
			assertThat(meterRegistry.get("shop.checkout.total").tag("result", "failure").counter().count()).isEqualTo(1);

			verify(userService).getCurrentUserEntity();
			verify(cartService).getCartEntityForUser(user.getId());
			verifyNoInteractions(productRepository, discountCodeRepository, orderRepository, paymentRepository,
					paymentService, orderMapper);
		}

		@Test
		void placeOrderFromCart_shouldThrowWhenLockedProductNotFound() {
			User user = user();
			Product missingProduct = product(5, 10, BigDecimal.TEN);
			Cart cart = cart(user, missingProduct, 1);

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartService.getCartEntityForUser(user.getId())).thenReturn(cart);
			when(productRepository.findByIdWithLock(missingProduct.getId())).thenReturn(Optional.empty());

			assertThatThrownBy(() -> service.placeOrderFromCart(new OrderCheckoutRequestDTO(null, null)))
					.isInstanceOf(ProductNotFoundException.class);

			verify(userService).getCurrentUserEntity();
			verify(cartService).getCartEntityForUser(user.getId());
			verify(productRepository).findByIdWithLock(missingProduct.getId());
			verifyNoInteractions(discountCodeRepository, orderRepository, paymentRepository, paymentService,
					orderMapper);
		}

		@Test
		void placeOrderFromCart_shouldThrowWhenInsufficientStock() {
			User user = user();
			Product product = product(6, 1, BigDecimal.valueOf(12));
			Cart cart = cart(user, product, 2);

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartService.getCartEntityForUser(user.getId())).thenReturn(cart);
			when(productRepository.findByIdWithLock(product.getId())).thenReturn(Optional.of(product));

			assertThatThrownBy(() -> service.placeOrderFromCart(new OrderCheckoutRequestDTO(null, null)))
					.isInstanceOf(OrderInsufficientStockException.class);

			verify(userService).getCurrentUserEntity();
			verify(cartService).getCartEntityForUser(user.getId());
			verify(productRepository).findByIdWithLock(product.getId());
			assertThat(product.getStock()).isEqualTo(1);
			verifyNoInteractions(discountCodeRepository, orderRepository, paymentRepository, paymentService,
					orderMapper);
		}

		@Test
		void placeOrderFromCart_shouldThrowWhenDiscountCodeNotFound() {
			User user = user();
			Product product = product(7, 8, BigDecimal.valueOf(50));
			Cart cart = cart(user, product, 1);

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartService.getCartEntityForUser(user.getId())).thenReturn(cart);
			when(productRepository.findByIdWithLock(product.getId())).thenReturn(Optional.of(product));
			when(discountCodeRepository.findByCodeIgnoreCase("SAVE20")).thenReturn(Optional.empty());

			assertThatThrownBy(() -> service.placeOrderFromCart(new OrderCheckoutRequestDTO(" SAVE20 ", null)))
					.isInstanceOf(DiscountCodeInvalidException.class).hasMessageContaining("SAVE20");

			verify(userService).getCurrentUserEntity();
			verify(cartService).getCartEntityForUser(user.getId());
			verify(productRepository).findByIdWithLock(product.getId());
			verify(discountCodeRepository).findByCodeIgnoreCase("SAVE20");
			verifyNoInteractions(orderRepository, paymentRepository, paymentService, orderMapper);
		}

		@Test
		void placeOrderFromCart_shouldThrowWhenDiscountCodeCannotBeUsed() {
			User user = user();
			Product product = product(8, 9, BigDecimal.valueOf(30));
			Cart cart = cart(user, product, 2);
			DiscountCode discountCode = mock(DiscountCode.class);
			when(discountCode.canBeUsed()).thenReturn(false);

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartService.getCartEntityForUser(user.getId())).thenReturn(cart);
			when(productRepository.findByIdWithLock(product.getId())).thenReturn(Optional.of(product));
			when(discountCodeRepository.findByCodeIgnoreCase("EXPIRED10"))
					.thenReturn(Optional.of(discountCode));

			assertThatThrownBy(() -> service.placeOrderFromCart(new OrderCheckoutRequestDTO("EXPIRED10", null)))
					.isInstanceOf(DiscountCodeInvalidException.class).hasMessageContaining("EXPIRED10");

			verify(userService).getCurrentUserEntity();
			verify(cartService).getCartEntityForUser(user.getId());
			verify(productRepository).findByIdWithLock(product.getId());
			verify(discountCodeRepository).findByCodeIgnoreCase("EXPIRED10");
			verifyNoInteractions(orderRepository, paymentRepository, paymentService, orderMapper);
		}
	}

	@Nested
	class PlaceOrderFromCartStockAndLockingTests {

		@Test
		void placeOrderFromCart_shouldUsePessimisticLookupForEachCartItem() {
			User user = user();
			Product firstProduct = product(9, 10, BigDecimal.valueOf(9));
			Product secondProduct = product(10, 7, BigDecimal.valueOf(4));
			Cart cart = cart(user, firstProduct, 1, secondProduct, 2);

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartService.getCartEntityForUser(user.getId())).thenReturn(cart);
			when(productRepository.findByIdWithLock(firstProduct.getId())).thenReturn(Optional.of(firstProduct));
			when(productRepository.findByIdWithLock(secondProduct.getId())).thenReturn(Optional.of(secondProduct));
			when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
			when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
			when(paymentService.createPaymentIntent(any(Order.class)))
					.thenReturn(new PaymentIntentResponseDTO("pi_lock", "pk_lock"));
			when(orderMapper.toDto(any(Order.class))).thenReturn(new OrderResponseDTO(UUID.randomUUID(),
					OrderStatus.NEW, BigDecimal.valueOf(17), LocalDateTime.now(), null));

			service.placeOrderFromCart(new OrderCheckoutRequestDTO(null, null));

			verify(productRepository).findByIdWithLock(firstProduct.getId());
			verify(productRepository).findByIdWithLock(secondProduct.getId());
		}

		@Test
		void placeOrderFromCart_shouldCreateOrderItemsAndDecreaseStockForEachCartItem() {
			User user = user();
			Product firstProduct = product(11, 10, BigDecimal.valueOf(3));
			Product secondProduct = product(12, 8, BigDecimal.valueOf(6));
			Cart cart = cart(user, firstProduct, 2, secondProduct, 3);

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartService.getCartEntityForUser(user.getId())).thenReturn(cart);
			when(productRepository.findByIdWithLock(firstProduct.getId())).thenReturn(Optional.of(firstProduct));
			when(productRepository.findByIdWithLock(secondProduct.getId())).thenReturn(Optional.of(secondProduct));
			when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
			when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
			when(paymentService.createPaymentIntent(any(Order.class)))
					.thenReturn(new PaymentIntentResponseDTO("pi_stock", "pk_stock"));
			when(orderMapper.toDto(any(Order.class))).thenReturn(new OrderResponseDTO(UUID.randomUUID(),
					OrderStatus.NEW, BigDecimal.valueOf(24), LocalDateTime.now(), null));

			service.placeOrderFromCart(new OrderCheckoutRequestDTO(null, null));

			assertThat(firstProduct.getStock()).isEqualTo(8);
			assertThat(secondProduct.getStock()).isEqualTo(5);

			ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
			verify(orderRepository).save(orderCaptor.capture());
			Order savedOrder = orderCaptor.getValue();

			assertThat(savedOrder.getItems()).hasSize(2);
			assertThat(savedOrder.getItems()).anySatisfy(item -> {
				assertThat(item.getProduct()).isEqualTo(firstProduct);
				assertThat(item.getQuantity()).isEqualTo(2);
				assertThat(item.getPrice()).isEqualByComparingTo("3.00");
			}).anySatisfy(item -> {
				assertThat(item.getProduct()).isEqualTo(secondProduct);
				assertThat(item.getQuantity()).isEqualTo(3);
				assertThat(item.getPrice()).isEqualByComparingTo("6.00");
			});
		}

		@Test
		void placeOrderFromCart_shouldPreserveUnitPriceFromLockedProductDuringOrderCreation() {
			User user = user();
			Product cartProduct = product(15, 5, BigDecimal.valueOf(99));
			Cart cart = cart(user, cartProduct, 2);

			Product lockedProduct = product(16, 5, BigDecimal.valueOf(12.50));
			setEntityId(lockedProduct, cartProduct.getId());

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartService.getCartEntityForUser(user.getId())).thenReturn(cart);
			when(productRepository.findByIdWithLock(cartProduct.getId())).thenReturn(Optional.of(lockedProduct));
			when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
			when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
			when(paymentService.createPaymentIntent(any(Order.class)))
					.thenReturn(new PaymentIntentResponseDTO("pi_price", "pk_price"));
			when(orderMapper.toDto(any(Order.class))).thenReturn(new OrderResponseDTO(UUID.randomUUID(),
					OrderStatus.NEW, BigDecimal.valueOf(25), LocalDateTime.now(), null));

			service.placeOrderFromCart(new OrderCheckoutRequestDTO(null, null));

			ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
			verify(orderRepository).save(orderCaptor.capture());
			Order savedOrder = orderCaptor.getValue();

			assertThat(savedOrder.getItems()).hasSize(1);
			assertThat(savedOrder.getItems().get(0).getPrice()).isEqualByComparingTo("12.50");
			assertThat(savedOrder.getTotalAmount()).isEqualByComparingTo("25.00");
		}
	}

	private User user() {
		User user = new User("john@example.com", "encoded", "John", "Doe");
		setEntityId(user, UUID.randomUUID());
		return user;
	}

	private Product product(int unique, int stock, BigDecimal price) {
		Category category = new Category("Category-" + unique, "category-" + unique, "desc");
		Product product = new Product("Product-" + unique, "product-" + unique, "SKU-" + unique, "desc", price, stock,
				category);
		setEntityId(product, UUID.randomUUID());
		return product;
	}

	private Cart cart(User user, Product firstProduct, int firstQuantity) {
		Cart cart = new Cart(user);
		cart.addItem(firstProduct, firstQuantity);
		return cart;
	}

	private Cart cart(User user, Product firstProduct, int firstQuantity, Product secondProduct, int secondQuantity) {
		Cart cart = new Cart(user);
		cart.addItem(firstProduct, firstQuantity);
		cart.addItem(secondProduct, secondQuantity);
		return cart;
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

}
