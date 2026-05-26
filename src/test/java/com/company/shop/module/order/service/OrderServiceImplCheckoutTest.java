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
import java.util.List;
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
import com.company.shop.module.cart.api.internal.CartCheckoutFacade;
import com.company.shop.module.cart.api.internal.CartCheckoutItem;
import com.company.shop.module.cart.api.internal.CartCheckoutSnapshot;
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
import com.company.shop.module.product.api.internal.CheckoutProduct;
import com.company.shop.module.product.api.internal.ProductCatalogFacade;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.exception.ProductInsufficientStockException;
import com.company.shop.module.product.exception.ProductNotFoundException;

import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.service.UserService;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplCheckoutTest {

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private ProductCatalogFacade productCatalogFacade;

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private DiscountCodeRepository discountCodeRepository;

	@Mock
	private UserService userService;

	@Mock
	private CartCheckoutFacade cartCheckoutFacade;

	@Mock
	private OrderMapper orderMapper;

	@Mock
	private PaymentService paymentService;

	private SimpleMeterRegistry meterRegistry;
	private OrderServiceImpl service;

	@BeforeEach
	void setUp() {
		meterRegistry = new SimpleMeterRegistry();
		service = new OrderServiceImpl(orderRepository, productCatalogFacade, paymentRepository, discountCodeRepository,
				userService, cartCheckoutFacade, orderMapper, paymentService, meterRegistry);
	}

	@Nested
	class PlaceOrderFromCartHappyPathTests {

		@Test
		void placeOrderFromCart_shouldCreateOrderPaymentAndReturnDetailedResponse() {
			User user = user();
			Product firstProduct = product(1, 10, BigDecimal.valueOf(10));
			Product secondProduct = product(2, 8, BigDecimal.valueOf(5));
			CartCheckoutSnapshot cart = cart(user, firstProduct, 2, secondProduct, 3);

			OrderCheckoutRequestDTO request = new OrderCheckoutRequestDTO(null, "deliver quickly");
			PaymentIntentResponseDTO paymentIntent = new PaymentIntentResponseDTO("pi_secret", "pk_test");
			LocalDateTime createdAt = LocalDateTime.now();
			UUID savedOrderId = UUID.randomUUID();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartCheckoutFacade.getCartForCheckout(user.getId())).thenReturn(cart);
									when(productCatalogFacade.reserveProductForCheckout(firstProduct.getId(), 2))
					.thenReturn(new CheckoutProduct(firstProduct.getId(), firstProduct.getName(), firstProduct.getPrice()));
			when(productCatalogFacade.reserveProductForCheckout(secondProduct.getId(), 3))
					.thenReturn(new CheckoutProduct(secondProduct.getId(), secondProduct.getName(), secondProduct.getPrice()));
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

						ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
			verify(orderRepository).save(orderCaptor.capture());
			Order savedOrder = orderCaptor.getValue();
			assertThat(savedOrder.getUser()).isEqualTo(user);
			assertThat(savedOrder.getItems()).hasSize(2);
			assertThat(savedOrder.getItems().get(0).getProductId()).isEqualTo(firstProduct.getId());
			assertThat(savedOrder.getItems().get(0).getProductName()).isEqualTo(firstProduct.getName());
			assertThat(savedOrder.getItems().get(0).getQuantity()).isEqualTo(2);
			assertThat(savedOrder.getItems().get(0).getPrice()).isEqualByComparingTo("10.00");
			assertThat(savedOrder.getItems().get(1).getProductId()).isEqualTo(secondProduct.getId());
			assertThat(savedOrder.getItems().get(1).getProductName()).isEqualTo(secondProduct.getName());
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
			CartCheckoutSnapshot cart = cart(user, product, 1);
			DiscountCode discountCode = mock(DiscountCode.class);
			when(discountCode.canBeUsed()).thenReturn(true);
			when(discountCode.getDiscountPercent()).thenReturn(10);

			OrderCheckoutRequestDTO request = new OrderCheckoutRequestDTO(" SAVE10 ", null);
			PaymentIntentResponseDTO paymentIntent = new PaymentIntentResponseDTO("pi_discount", "pk_discount");
			UUID savedOrderId = UUID.randomUUID();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartCheckoutFacade.getCartForCheckout(user.getId())).thenReturn(cart);
						when(productCatalogFacade.reserveProductForCheckout(product.getId(), 1))
					.thenReturn(new CheckoutProduct(product.getId(), product.getName(), product.getPrice()));
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
			CartCheckoutSnapshot cart = cart(user, product, 2);

			OrderCheckoutRequestDTO request = new OrderCheckoutRequestDTO("   ", "no notes");
			PaymentIntentResponseDTO paymentIntent = new PaymentIntentResponseDTO("pi_blank", "pk_blank");
			UUID savedOrderId = UUID.randomUUID();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartCheckoutFacade.getCartForCheckout(user.getId())).thenReturn(cart);
						when(productCatalogFacade.reserveProductForCheckout(product.getId(), 2))
					.thenReturn(new CheckoutProduct(product.getId(), product.getName(), product.getPrice()));
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
			CartCheckoutSnapshot cart = new CartCheckoutSnapshot(UUID.randomUUID(), java.util.List.of());

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartCheckoutFacade.getCartForCheckout(user.getId())).thenReturn(cart);

			assertThatThrownBy(() -> service.placeOrderFromCart(new OrderCheckoutRequestDTO(null, null)))
					.isInstanceOf(EmptyCartCheckoutException.class);
			assertThat(meterRegistry.get("shop.checkout.total").tag("result", "attempt").counter().count()).isEqualTo(1);
			assertThat(meterRegistry.get("shop.checkout.total").tag("result", "failure").counter().count()).isEqualTo(1);

			verify(userService).getCurrentUserEntity();
			verify(cartCheckoutFacade).getCartForCheckout(user.getId());
			verifyNoInteractions(productCatalogFacade, discountCodeRepository, orderRepository, paymentRepository,
					paymentService, orderMapper);
		}

		@Test
		void placeOrderFromCart_shouldThrowWhenLockedProductNotFound() {
			User user = user();
			Product missingProduct = product(5, 10, BigDecimal.TEN);
			CartCheckoutSnapshot cart = cart(user, missingProduct, 1);

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartCheckoutFacade.getCartForCheckout(user.getId())).thenReturn(cart);
			when(productCatalogFacade.reserveProductForCheckout(missingProduct.getId(), 1))
					.thenThrow(new ProductNotFoundException(missingProduct.getId()));

			assertThatThrownBy(() -> service.placeOrderFromCart(new OrderCheckoutRequestDTO(null, null)))
					.isInstanceOf(ProductNotFoundException.class);

			verify(userService).getCurrentUserEntity();
			verify(cartCheckoutFacade).getCartForCheckout(user.getId());
			verify(productCatalogFacade).reserveProductForCheckout(missingProduct.getId(), 1);
			verifyNoInteractions(discountCodeRepository, orderRepository, paymentRepository, paymentService,
					orderMapper);
		}

		@Test
		void placeOrderFromCart_shouldThrowWhenInsufficientStock() {
			User user = user();
			Product product = product(6, 1, BigDecimal.valueOf(12));
			CartCheckoutSnapshot cart = cart(user, product, 2);

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartCheckoutFacade.getCartForCheckout(user.getId())).thenReturn(cart);
			when(productCatalogFacade.reserveProductForCheckout(product.getId(), 2))
					.thenThrow(new ProductInsufficientStockException(product.getName(), 2, 1));

			assertThatThrownBy(() -> service.placeOrderFromCart(new OrderCheckoutRequestDTO(null, null)))
					.isInstanceOf(OrderInsufficientStockException.class);

			verify(userService).getCurrentUserEntity();
			verify(cartCheckoutFacade).getCartForCheckout(user.getId());
			verify(productCatalogFacade).reserveProductForCheckout(product.getId(), 2);
						verifyNoInteractions(discountCodeRepository, orderRepository, paymentRepository, paymentService,
					orderMapper);
		}

		@Test
		void placeOrderFromCart_shouldThrowWhenDiscountCodeNotFound() {
			User user = user();
			Product product = product(7, 8, BigDecimal.valueOf(50));
			CartCheckoutSnapshot cart = cart(user, product, 1);

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartCheckoutFacade.getCartForCheckout(user.getId())).thenReturn(cart);
						when(productCatalogFacade.reserveProductForCheckout(product.getId(), 1))
					.thenReturn(new CheckoutProduct(product.getId(), product.getName(), product.getPrice()));
			when(discountCodeRepository.findByCodeIgnoreCase("SAVE20")).thenReturn(Optional.empty());

			assertThatThrownBy(() -> service.placeOrderFromCart(new OrderCheckoutRequestDTO(" SAVE20 ", null)))
					.isInstanceOf(DiscountCodeInvalidException.class).hasMessageContaining("SAVE20");

			verify(userService).getCurrentUserEntity();
			verify(cartCheckoutFacade).getCartForCheckout(user.getId());
			verify(productCatalogFacade).reserveProductForCheckout(product.getId(), 1);
			verify(discountCodeRepository).findByCodeIgnoreCase("SAVE20");
			verifyNoInteractions(orderRepository, paymentRepository, paymentService, orderMapper);
		}

		@Test
		void placeOrderFromCart_shouldThrowWhenDiscountCodeCannotBeUsed() {
			User user = user();
			Product product = product(8, 9, BigDecimal.valueOf(30));
			CartCheckoutSnapshot cart = cart(user, product, 2);
			DiscountCode discountCode = mock(DiscountCode.class);
			when(discountCode.canBeUsed()).thenReturn(false);

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartCheckoutFacade.getCartForCheckout(user.getId())).thenReturn(cart);
						when(productCatalogFacade.reserveProductForCheckout(product.getId(), 2))
					.thenReturn(new CheckoutProduct(product.getId(), product.getName(), product.getPrice()));
			when(discountCodeRepository.findByCodeIgnoreCase("EXPIRED10"))
					.thenReturn(Optional.of(discountCode));

			assertThatThrownBy(() -> service.placeOrderFromCart(new OrderCheckoutRequestDTO("EXPIRED10", null)))
					.isInstanceOf(DiscountCodeInvalidException.class).hasMessageContaining("EXPIRED10");

			verify(userService).getCurrentUserEntity();
			verify(cartCheckoutFacade).getCartForCheckout(user.getId());
			verify(productCatalogFacade).reserveProductForCheckout(product.getId(), 2);
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
			CartCheckoutSnapshot cart = cart(user, firstProduct, 1, secondProduct, 2);

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartCheckoutFacade.getCartForCheckout(user.getId())).thenReturn(cart);
									when(productCatalogFacade.reserveProductForCheckout(firstProduct.getId(), 1))
					.thenReturn(new CheckoutProduct(firstProduct.getId(), firstProduct.getName(), firstProduct.getPrice()));
			when(productCatalogFacade.reserveProductForCheckout(secondProduct.getId(), 2))
					.thenReturn(new CheckoutProduct(secondProduct.getId(), secondProduct.getName(), secondProduct.getPrice()));
			when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
			when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
			when(paymentService.createPaymentIntent(any(Order.class)))
					.thenReturn(new PaymentIntentResponseDTO("pi_lock", "pk_lock"));
			when(orderMapper.toDto(any(Order.class))).thenReturn(new OrderResponseDTO(UUID.randomUUID(),
					OrderStatus.NEW, BigDecimal.valueOf(17), LocalDateTime.now(), null));

			service.placeOrderFromCart(new OrderCheckoutRequestDTO(null, null));

			verify(productCatalogFacade).reserveProductForCheckout(firstProduct.getId(), 1);
			verify(productCatalogFacade).reserveProductForCheckout(secondProduct.getId(), 2);
		}

		@Test
		void placeOrderFromCart_shouldCreateOrderItemsAndDecreaseStockForEachCartItem() {
			User user = user();
			Product firstProduct = product(11, 10, BigDecimal.valueOf(3));
			Product secondProduct = product(12, 8, BigDecimal.valueOf(6));
			CartCheckoutSnapshot cart = cart(user, firstProduct, 2, secondProduct, 3);

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartCheckoutFacade.getCartForCheckout(user.getId())).thenReturn(cart);
									when(productCatalogFacade.reserveProductForCheckout(firstProduct.getId(), 2))
					.thenReturn(new CheckoutProduct(firstProduct.getId(), firstProduct.getName(), firstProduct.getPrice()));
			when(productCatalogFacade.reserveProductForCheckout(secondProduct.getId(), 3))
					.thenReturn(new CheckoutProduct(secondProduct.getId(), secondProduct.getName(), secondProduct.getPrice()));
			when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
			when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
			when(paymentService.createPaymentIntent(any(Order.class)))
					.thenReturn(new PaymentIntentResponseDTO("pi_stock", "pk_stock"));
			when(orderMapper.toDto(any(Order.class))).thenReturn(new OrderResponseDTO(UUID.randomUUID(),
					OrderStatus.NEW, BigDecimal.valueOf(24), LocalDateTime.now(), null));

			service.placeOrderFromCart(new OrderCheckoutRequestDTO(null, null));

						ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
			verify(orderRepository).save(orderCaptor.capture());
			Order savedOrder = orderCaptor.getValue();

			assertThat(savedOrder.getItems()).hasSize(2);
			assertThat(savedOrder.getItems()).anySatisfy(item -> {
				assertThat(item.getProductId()).isEqualTo(firstProduct.getId());
				assertThat(item.getProductName()).isEqualTo(firstProduct.getName());
				assertThat(item.getQuantity()).isEqualTo(2);
				assertThat(item.getPrice()).isEqualByComparingTo("3.00");
			}).anySatisfy(item -> {
				assertThat(item.getProductId()).isEqualTo(secondProduct.getId());
				assertThat(item.getProductName()).isEqualTo(secondProduct.getName());
				assertThat(item.getQuantity()).isEqualTo(3);
				assertThat(item.getPrice()).isEqualByComparingTo("6.00");
			});
		}

		@Test
		void placeOrderFromCart_shouldPreserveUnitPriceFromLockedProductDuringOrderCreation() {
			User user = user();
			Product cartProduct = product(15, 5, BigDecimal.valueOf(99));
			CartCheckoutSnapshot cart = cart(user, cartProduct, 2);

			Product lockedProduct = product(16, 5, BigDecimal.valueOf(12.50));
			setEntityId(lockedProduct, cartProduct.getId());

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartCheckoutFacade.getCartForCheckout(user.getId())).thenReturn(cart);
						when(productCatalogFacade.reserveProductForCheckout(cartProduct.getId(), 2))
					.thenReturn(new CheckoutProduct(lockedProduct.getId(), lockedProduct.getName(), lockedProduct.getPrice()));
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

	private CartCheckoutSnapshot cart(User user, Product firstProduct, int firstQuantity) {
		return new CartCheckoutSnapshot(UUID.randomUUID(),
				List.of(new CartCheckoutItem(firstProduct.getId(), firstQuantity)));
	}

	private CartCheckoutSnapshot cart(User user, Product firstProduct, int firstQuantity, Product secondProduct, int secondQuantity) {
		return new CartCheckoutSnapshot(UUID.randomUUID(), List.of(
				new CartCheckoutItem(firstProduct.getId(), firstQuantity),
				new CartCheckoutItem(secondProduct.getId(), secondQuantity)));
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
