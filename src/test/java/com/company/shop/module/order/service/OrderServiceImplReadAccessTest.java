package com.company.shop.module.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.company.shop.common.model.BaseEntity;
import com.company.shop.module.cart.api.internal.CartCheckoutFacade;
import com.company.shop.module.order.dto.OrderDetailedResponseDTO;
import com.company.shop.module.order.dto.OrderResponseDTO;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.exception.OrderAccessDeniedException;
import com.company.shop.module.order.exception.OrderNotFoundException;
import com.company.shop.module.order.mapper.OrderMapper;
import com.company.shop.module.order.repository.DiscountCodeRepository;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.company.shop.module.order.service.checkout.OrderCheckoutProcessor;
import com.company.shop.module.order.service.query.OrderQueryProcessor;
import com.company.shop.module.product.api.internal.ProductCatalogFacade;
import com.company.shop.module.user.entity.Role;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.api.internal.CurrentUserFacade;
import com.company.shop.module.user.api.internal.CurrentUserSnapshot;
import com.company.shop.security.SecurityConstants;


@ExtendWith(MockitoExtension.class)
class OrderServiceImplReadAccessTest {

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private ProductCatalogFacade productCatalogFacade;

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private DiscountCodeRepository discountCodeRepository;

	@Mock
	private CurrentUserFacade currentUserFacade;

	@Mock
	private CartCheckoutFacade cartCheckoutFacade;

	@Mock
	private OrderMapper orderMapper;

	@Mock
	private PaymentService paymentService;

	private OrderServiceImpl service;

	@BeforeEach
	void setUp() {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		OrderCheckoutProcessor checkoutProcessor = new OrderCheckoutProcessor(orderRepository, productCatalogFacade,
				paymentRepository, discountCodeRepository, currentUserFacade, cartCheckoutFacade, orderMapper,
				paymentService, meterRegistry);
		OrderQueryProcessor queryProcessor = new OrderQueryProcessor(orderRepository, currentUserFacade, orderMapper);
		service = new OrderServiceImpl(checkoutProcessor, queryProcessor);
	}

	@Nested
	class FindByIdAccessControlTests {

		@Test
		void findById_shouldThrowWhenOrderMissing() {
			UUID orderId = UUID.randomUUID();
			when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> service.findById(orderId)).isInstanceOf(OrderNotFoundException.class)
					.hasMessageContaining(orderId.toString());

			verify(orderRepository).findById(orderId);
			verifyNoInteractions(currentUserFacade, orderMapper);
		}

		@Test
		void findById_shouldReturnDetailedDtoWhenCurrentUserOwnsOrder() {
			User owner = user();
			Order order = new Order(owner.getId(), owner.getEmail());
			UUID orderId = UUID.randomUUID();
			setEntityId(order, orderId);

			OrderDetailedResponseDTO detailedDto = new OrderDetailedResponseDTO(orderId, OrderStatus.NEW,
					BigDecimal.valueOf(42), LocalDateTime.now(), owner.getEmail(), List.of());

			when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
			when(currentUserFacade.getCurrentUser()).thenReturn(snapshot(owner));
			when(orderMapper.toDetailedDto(order)).thenReturn(detailedDto);

			OrderDetailedResponseDTO result = service.findById(orderId);

			assertThat(result).isEqualTo(detailedDto);
			verify(orderRepository).findById(orderId);
			verify(currentUserFacade).getCurrentUser();
			verify(orderMapper).toDetailedDto(order);
		}

		@Test
		void findById_shouldReturnDetailedDtoWhenCurrentUserIsAdmin() {
			User owner = user();
			User admin = user();
			admin.addRole(new Role(SecurityConstants.ROLE_ADMIN));

			UUID orderId = UUID.randomUUID();
			Order order = new Order(owner.getId(), owner.getEmail());
			setEntityId(order, orderId);

			OrderDetailedResponseDTO detailedDto = new OrderDetailedResponseDTO(orderId, OrderStatus.NEW,
					BigDecimal.valueOf(10), LocalDateTime.now(), owner.getEmail(), List.of());

			when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
			when(currentUserFacade.getCurrentUser()).thenReturn(snapshot(admin));
			when(orderMapper.toDetailedDto(order)).thenReturn(detailedDto);

			OrderDetailedResponseDTO result = service.findById(orderId);

			assertThat(result).isEqualTo(detailedDto);
			verify(orderRepository).findById(orderId);
			verify(currentUserFacade).getCurrentUser();
			verify(orderMapper).toDetailedDto(order);
		}

		@Test
		void findById_shouldThrowAccessDeniedWhenNotOwnerAndNotAdmin() {
			User owner = user();
			User differentUser = user();
			setEntityId(owner, UUID.fromString("00000000-0000-0000-0000-000000000001"));
			setEntityId(differentUser, UUID.fromString("00000000-0000-0000-0000-000000000002"));

			UUID orderId = UUID.randomUUID();
			Order order = new Order(owner.getId(), owner.getEmail());
			setEntityId(order, orderId);

			when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
			when(currentUserFacade.getCurrentUser()).thenReturn(snapshot(differentUser));

			assertThatThrownBy(() -> service.findById(orderId)).isInstanceOf(OrderAccessDeniedException.class);

			verify(orderRepository).findById(orderId);
			verify(currentUserFacade).getCurrentUser();
			verifyNoInteractions(orderMapper);
		}
	}

	@Nested
	class ReadDelegationTests {

		@Test
		void findAll_shouldMapRepositoryPageToDtoPage() {
			User user = user();
			Order order = new Order(user.getId(), user.getEmail());
			OrderResponseDTO dto = new OrderResponseDTO(UUID.randomUUID(), OrderStatus.NEW, BigDecimal.ONE,
					LocalDateTime.now(), null);
			PageRequest pageable = PageRequest.of(0, 10);
			Page<Order> page = new PageImpl<>(List.of(order));

			when(orderRepository.findAll(pageable)).thenReturn(page);
			when(orderMapper.toDto(order)).thenReturn(dto);

			Page<OrderResponseDTO> result = service.findAll(pageable);

			assertThat(result.getContent()).containsExactly(dto);
			verify(orderRepository).findAll(pageable);
			verify(orderMapper).toDto(order);
			verifyNoInteractions(currentUserFacade);
		}

		@Test
		void findMyOrders_shouldUseCurrentUserAndMapToDtoPage() {
			User currentUser = user();
			Order order = new Order(currentUser.getId(), currentUser.getEmail());
			OrderResponseDTO dto = new OrderResponseDTO(UUID.randomUUID(), OrderStatus.NEW, BigDecimal.TEN,
					LocalDateTime.now(), null);
			PageRequest pageable = PageRequest.of(0, 5);
			Page<Order> page = new PageImpl<>(List.of(order));

			when(currentUserFacade.getCurrentUser()).thenReturn(snapshot(currentUser));
			when(orderRepository.findByUserId(currentUser.getId(), pageable)).thenReturn(page);
			when(orderMapper.toDto(order)).thenReturn(dto);

			Page<OrderResponseDTO> result = service.findMyOrders(pageable);

			assertThat(result.getContent()).containsExactly(dto);
			verify(currentUserFacade).getCurrentUser();
			verify(orderRepository).findByUserId(currentUser.getId(), pageable);
			verify(orderMapper).toDto(order);
		}
	}

	private CurrentUserSnapshot snapshot(User user) {
		return new CurrentUserSnapshot(user.getId(), user.getEmail(),
				user.getRoles().stream().map(Role::getName).collect(java.util.stream.Collectors.toSet()));
	}

	private User user() {
		User user = new User("john@example.com", "encoded", "John", "Doe");
		setEntityId(user, UUID.randomUUID());
		return user;
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
