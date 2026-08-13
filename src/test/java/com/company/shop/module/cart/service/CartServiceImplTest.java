package com.company.shop.module.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
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

import com.company.shop.common.model.BaseEntity;
import com.company.shop.module.cart.dto.AddToCartRequestDTO;
import com.company.shop.module.cart.dto.CartResponseDTO;
import com.company.shop.module.cart.dto.UpdateCartItemRequestDTO;
import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.entity.CartItem;
import com.company.shop.module.cart.exception.CartNotFoundException;
import com.company.shop.module.cart.exception.InsufficientStockException;
import com.company.shop.module.cart.mapper.CartMapper;
import com.company.shop.module.cart.repository.CartRepository;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.exception.ProductNotFoundException;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.service.UserService;

@ExtendWith(MockitoExtension.class)
class CartServiceImplTest {

	@Mock
	private CartRepository cartRepository;

	@Mock
	private ProductRepository productRepository;

	@Mock
	private UserService userService;

	@Mock
	private CartMapper cartMapper;

	private CartServiceImpl service;

	@BeforeEach
	void setUp() {
		service = new CartServiceImpl(cartRepository, productRepository, userService, cartMapper);
	}

	@Nested
	class GetMyCartTests {

		@Test
		void getMyCart_shouldReturnMappedCartWhenCartExists() {
			User user = user();
			Cart cart = new Cart(user);
			CartResponseDTO dto = cartResponse();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartRepository.findByUserIdWithItems(user.getId())).thenReturn(Optional.of(cart));
			when(cartMapper.toDTO(cart)).thenReturn(dto);

			CartResponseDTO result = service.getMyCart();

			assertThat(result).isEqualTo(dto);
			verify(userService).getCurrentUserEntity();
			verify(cartRepository).findByUserIdWithItems(user.getId());
			verify(cartMapper).toDTO(cart);
			verify(cartRepository, never()).save(any(Cart.class));
		}

		@Test
		void getMyCart_shouldCreateCartWhenMissing() {
			User user = user();
			Cart savedCart = new Cart(user);
			CartResponseDTO dto = cartResponse();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartRepository.findByUserIdWithItems(user.getId())).thenReturn(Optional.empty());
			when(cartRepository.save(any(Cart.class))).thenReturn(savedCart);
			when(cartMapper.toDTO(savedCart)).thenReturn(dto);

			CartResponseDTO result = service.getMyCart();

			assertThat(result).isEqualTo(dto);

			ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
			verify(userService).getCurrentUserEntity();
			verify(cartRepository).findByUserIdWithItems(user.getId());
			verify(cartRepository).save(cartCaptor.capture());
			verify(cartMapper).toDTO(savedCart);

			Cart createdCart = cartCaptor.getValue();
			assertThat(createdCart.getUser()).isEqualTo(user);
			assertThat(createdCart.getItems()).isEmpty();
		}
	}

	@Nested
	class AddToCartTests {

		@Test
		void addToCart_shouldAddNewItemWhenProductNotYetInCartAndStockSufficient() {
			User user = user();
			Cart cart = new Cart(user);
			Product product = product(1, 10);
			AddToCartRequestDTO request = new AddToCartRequestDTO(product.getId(), 2);
			CartResponseDTO dto = cartResponse();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartRepository.findByUserIdWithItemsForUpdate(user.getId())).thenReturn(Optional.of(cart));
			when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
			when(cartRepository.save(cart)).thenReturn(cart);
			when(cartMapper.toDTO(cart)).thenReturn(dto);

			CartResponseDTO result = service.addToCart(request);

			assertThat(result).isEqualTo(dto);
			assertThat(cart.getItems()).hasSize(1);
			assertThat(cart.getItems().get(0).getProduct().getId()).isEqualTo(product.getId());
			assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(2);
			verify(userService).getCurrentUserEntity();
			verify(cartRepository).findByUserIdWithItemsForUpdate(user.getId());
			verify(productRepository).findById(product.getId());
			verify(cartRepository).save(cart);
			verify(cartMapper).toDTO(cart);
		}

		@Test
		void addToCart_shouldIncreaseQuantityWhenProductAlreadyInCartAndStockSufficient() {
			User user = user();
			Cart cart = new Cart(user);
			Product product = product(2, 10);
			cart.addItem(product, 2);

			AddToCartRequestDTO request = new AddToCartRequestDTO(product.getId(), 3);
			CartResponseDTO dto = cartResponse();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartRepository.findByUserIdWithItemsForUpdate(user.getId())).thenReturn(Optional.of(cart));
			when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
			when(cartRepository.save(cart)).thenReturn(cart);
			when(cartMapper.toDTO(cart)).thenReturn(dto);

			CartResponseDTO result = service.addToCart(request);

			assertThat(result).isEqualTo(dto);
			assertThat(cart.getItems()).hasSize(1);
			assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(5);
			verify(userService).getCurrentUserEntity();
			verify(cartRepository).findByUserIdWithItemsForUpdate(user.getId());
			verify(productRepository).findById(product.getId());
			verify(cartRepository).save(cart);
			verify(cartMapper).toDTO(cart);
		}

		@Test
		void addToCart_shouldAllowWhenRequestedPlusCurrentEqualsStock() {
			User user = user();
			Cart cart = new Cart(user);
			Product product = product(3, 5);
			cart.addItem(product, 2);

			AddToCartRequestDTO request = new AddToCartRequestDTO(product.getId(), 3);
			CartResponseDTO dto = cartResponse();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartRepository.findByUserIdWithItemsForUpdate(user.getId())).thenReturn(Optional.of(cart));
			when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
			when(cartRepository.save(cart)).thenReturn(cart);
			when(cartMapper.toDTO(cart)).thenReturn(dto);

			CartResponseDTO result = service.addToCart(request);

			assertThat(result).isEqualTo(dto);
			assertThat(cart.getItems()).hasSize(1);
			assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(5);
			verify(userService).getCurrentUserEntity();
			verify(cartRepository).findByUserIdWithItemsForUpdate(user.getId());
			verify(productRepository).findById(product.getId());
			verify(cartRepository).save(cart);
			verify(cartMapper).toDTO(cart);
		}

		@Test
		void shouldCreateCartWhenMissing() {
			User user = user();
			Product product = product(4, 8);
			AddToCartRequestDTO request = new AddToCartRequestDTO(product.getId(), 2);
			CartResponseDTO dto = cartResponse();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartRepository.findByUserIdWithItemsForUpdate(user.getId())).thenReturn(Optional.empty());
			when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
			when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> invocation.getArgument(0));
			when(cartMapper.toDTO(any(Cart.class))).thenReturn(dto);

			CartResponseDTO result = service.addToCart(request);

			assertThat(result).isEqualTo(dto);
			verify(userService).getCurrentUserEntity();
			verify(cartRepository).findByUserIdWithItemsForUpdate(user.getId());
			verify(productRepository).findById(product.getId());

			ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
			verify(cartRepository, atLeastOnce()).save(cartCaptor.capture());

			List<Cart> savedCarts = cartCaptor.getAllValues();
			assertThat(savedCarts).isNotEmpty();

			Cart finalSavedCart = savedCarts.get(savedCarts.size() - 1);

			verify(cartMapper).toDTO(finalSavedCart);

			assertThat(finalSavedCart.getUser()).isEqualTo(user);
			assertThat(finalSavedCart.getItems()).hasSize(1);
			assertThat(finalSavedCart.getItems().get(0).getProduct().getId()).isEqualTo(product.getId());
			assertThat(finalSavedCart.getItems().get(0).getQuantity()).isEqualTo(2);
		}

		@Test
		void addToCart_shouldThrowProductNotFoundWhenProductMissing() {
			User user = user();
			Cart cart = new Cart(user);
			UUID productId = UUID.randomUUID();
			AddToCartRequestDTO request = new AddToCartRequestDTO(productId, 1);

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartRepository.findByUserIdWithItemsForUpdate(user.getId())).thenReturn(Optional.of(cart));
			when(productRepository.findById(productId)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> service.addToCart(request)).isInstanceOf(ProductNotFoundException.class);

			verify(userService).getCurrentUserEntity();
			verify(cartRepository).findByUserIdWithItemsForUpdate(user.getId());
			verify(productRepository).findById(productId);
			verify(cartRepository, never()).save(any(Cart.class));
			verify(cartMapper, never()).toDTO(any(Cart.class));
			verifyNoMoreInteractions(productRepository, cartRepository, cartMapper, userService);
		}

		@Test
		void addToCart_shouldThrowInsufficientStockWhenRequestedPlusCurrentExceedsStock() {
			User user = user();
			Cart cart = new Cart(user);
			Product product = product(5, 4);
			cart.addItem(product, 3);

			AddToCartRequestDTO request = new AddToCartRequestDTO(product.getId(), 2);

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartRepository.findByUserIdWithItemsForUpdate(user.getId())).thenReturn(Optional.of(cart));
			when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

			assertThatThrownBy(() -> service.addToCart(request)).isInstanceOf(InsufficientStockException.class);

			verify(userService).getCurrentUserEntity();
			verify(cartRepository).findByUserIdWithItemsForUpdate(user.getId());
			verify(productRepository).findById(product.getId());
			verify(cartRepository, never()).save(any(Cart.class));
			verify(cartMapper, never()).toDTO(any(Cart.class));
			verifyNoMoreInteractions(productRepository, cartRepository, cartMapper, userService);
		}
	}

	@Nested
	class UpdateItemQuantityTests {

		@Test
		void updateItemQuantity_shouldUpdateQuantityWhenProductExistsAndStockSufficient() {
			User user = user();
			Cart cart = new Cart(user);
			Product product = product(6, 10);
			cart.addItem(product, 2);

			UpdateCartItemRequestDTO request = new UpdateCartItemRequestDTO(5);
			CartResponseDTO dto = cartResponse();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartRepository.findByUserIdWithItemsForUpdate(user.getId())).thenReturn(Optional.of(cart));
			when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
			when(cartRepository.save(cart)).thenReturn(cart);
			when(cartMapper.toDTO(cart)).thenReturn(dto);

			CartResponseDTO result = service.updateItemQuantity(product.getId(), request);

			assertThat(result).isEqualTo(dto);
			assertThat(cart.getItems()).hasSize(1);
			assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(5);
			verify(userService).getCurrentUserEntity();
			verify(cartRepository).findByUserIdWithItemsForUpdate(user.getId());
			verify(productRepository).findById(product.getId());
			verify(cartRepository).save(cart);
			verify(cartMapper).toDTO(cart);
		}

		@Test
		void updateItemQuantity_shouldAllowWhenRequestedEqualsStock() {
			User user = user();
			Cart cart = new Cart(user);
			Product product = product(7, 5);
			cart.addItem(product, 1);
			CartResponseDTO dto = cartResponse();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartRepository.findByUserIdWithItemsForUpdate(user.getId())).thenReturn(Optional.of(cart));
			when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
			when(cartRepository.save(cart)).thenReturn(cart);
			when(cartMapper.toDTO(cart)).thenReturn(dto);

			CartResponseDTO result = service.updateItemQuantity(product.getId(), new UpdateCartItemRequestDTO(5));

			assertThat(result).isEqualTo(dto);
			assertThat(cart.getItems()).hasSize(1);
			assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(5);
			verify(userService).getCurrentUserEntity();
			verify(cartRepository).findByUserIdWithItemsForUpdate(user.getId());
			verify(productRepository).findById(product.getId());
			verify(cartRepository).save(cart);
			verify(cartMapper).toDTO(cart);
		}

		@Test
		void shouldCreateCartWhenMissingAndPersist() {
			User user = user();
			Product product = product(8, 10);
			CartResponseDTO dto = cartResponse();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartRepository.findByUserIdWithItemsForUpdate(user.getId())).thenReturn(Optional.empty());
			when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
			when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> invocation.getArgument(0));
			when(cartMapper.toDTO(any(Cart.class))).thenReturn(dto);

			CartResponseDTO result = service.updateItemQuantity(product.getId(), new UpdateCartItemRequestDTO(2));

			assertThat(result).isEqualTo(dto);
			verify(userService).getCurrentUserEntity();
			verify(cartRepository).findByUserIdWithItemsForUpdate(user.getId());
			verify(productRepository).findById(product.getId());

			ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
			verify(cartRepository, atLeastOnce()).save(cartCaptor.capture());

			List<Cart> savedCarts = cartCaptor.getAllValues();
			assertThat(savedCarts).isNotEmpty();

			Cart finalSavedCart = savedCarts.get(savedCarts.size() - 1);

			verify(cartMapper).toDTO(finalSavedCart);

			assertThat(finalSavedCart.getUser()).isEqualTo(user);
			assertThat(finalSavedCart.getItems()).isEmpty();
		}

		@Test
		void updateItemQuantity_shouldThrowProductNotFoundWhenProductMissing() {
			User user = user();
			Cart cart = new Cart(user);
			UUID productId = UUID.randomUUID();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartRepository.findByUserIdWithItemsForUpdate(user.getId())).thenReturn(Optional.of(cart));
			when(productRepository.findById(productId)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> service.updateItemQuantity(productId, new UpdateCartItemRequestDTO(2)))
					.isInstanceOf(ProductNotFoundException.class);

			verify(userService).getCurrentUserEntity();
			verify(cartRepository).findByUserIdWithItemsForUpdate(user.getId());
			verify(productRepository).findById(productId);
			verify(cartRepository, never()).save(any(Cart.class));
			verify(cartMapper, never()).toDTO(any(Cart.class));
			verifyNoMoreInteractions(productRepository, cartRepository, cartMapper, userService);
		}

		@Test
		void updateItemQuantity_shouldThrowInsufficientStockWhenRequestedExceedsStock() {
			User user = user();
			Cart cart = new Cart(user);
			Product product = product(9, 3);

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartRepository.findByUserIdWithItemsForUpdate(user.getId())).thenReturn(Optional.of(cart));
			when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

			assertThatThrownBy(() -> service.updateItemQuantity(product.getId(), new UpdateCartItemRequestDTO(5)))
					.isInstanceOf(InsufficientStockException.class);

			verify(userService).getCurrentUserEntity();
			verify(cartRepository).findByUserIdWithItemsForUpdate(user.getId());
			verify(productRepository).findById(product.getId());
			verify(cartRepository, never()).save(any(Cart.class));
			verify(cartMapper, never()).toDTO(any(Cart.class));
			verifyNoMoreInteractions(productRepository, cartRepository, cartMapper, userService);
		}

		@Test
		void updateItemQuantity_shouldLeaveCartUnchangedAndPersistWhenItemDoesNotExist() {
			User user = user();
			Cart cart = new Cart(user);
			Product product = product(10, 10);
			CartResponseDTO dto = cartResponse();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartRepository.findByUserIdWithItemsForUpdate(user.getId())).thenReturn(Optional.of(cart));
			when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
			when(cartRepository.save(cart)).thenReturn(cart);
			when(cartMapper.toDTO(cart)).thenReturn(dto);

			CartResponseDTO result = service.updateItemQuantity(product.getId(), new UpdateCartItemRequestDTO(2));

			assertThat(result).isEqualTo(dto);
			assertThat(cart.getItems()).isEmpty();
			verify(userService).getCurrentUserEntity();
			verify(cartRepository).findByUserIdWithItemsForUpdate(user.getId());
			verify(productRepository).findById(product.getId());
			verify(cartRepository).save(cart);
			verify(cartMapper).toDTO(cart);
		}
	}

	@Nested
	class RemoveItemTests {

		@Test
		void removeItem_shouldRemoveExistingItemAndReturnMappedCart() {
			User user = user();
			Cart cart = new Cart(user);
			Product product = product(11, 10);
			cart.addItem(product, 2);
			CartResponseDTO dto = cartResponse();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartRepository.findByUserIdWithItemsForUpdate(user.getId())).thenReturn(Optional.of(cart));
			when(cartRepository.save(cart)).thenReturn(cart);
			when(cartMapper.toDTO(cart)).thenReturn(dto);

			CartResponseDTO result = service.removeItem(product.getId());

			assertThat(result).isEqualTo(dto);
			assertThat(cart.getItems()).isEmpty();
			verify(userService).getCurrentUserEntity();
			verify(cartRepository).findByUserIdWithItemsForUpdate(user.getId());
			verify(cartRepository).save(cart);
			verify(cartMapper).toDTO(cart);
		}

		@Test
		void removeItem_shouldBeNoOpWhenItemNotPresentButStillSave() {
			User user = user();
			Cart cart = new Cart(user);
			CartResponseDTO dto = cartResponse();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartRepository.findByUserIdWithItemsForUpdate(user.getId())).thenReturn(Optional.of(cart));
			when(cartRepository.save(cart)).thenReturn(cart);
			when(cartMapper.toDTO(cart)).thenReturn(dto);

			CartResponseDTO result = service.removeItem(UUID.randomUUID());

			assertThat(result).isEqualTo(dto);
			assertThat(cart.getItems()).isEmpty();
			verify(userService).getCurrentUserEntity();
			verify(cartRepository).findByUserIdWithItemsForUpdate(user.getId());
			verify(cartRepository).save(cart);
			verify(cartMapper).toDTO(cart);
		}

		@Test
		void shouldCreateCartWhenMissing() {
			User user = user();
			CartResponseDTO dto = cartResponse();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartRepository.findByUserIdWithItemsForUpdate(user.getId())).thenReturn(Optional.empty());
			when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> invocation.getArgument(0));
			when(cartMapper.toDTO(any(Cart.class))).thenReturn(dto);

			CartResponseDTO result = service.removeItem(UUID.randomUUID());

			assertThat(result).isEqualTo(dto);
			verify(userService).getCurrentUserEntity();
			verify(cartRepository).findByUserIdWithItemsForUpdate(user.getId());

			ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
			verify(cartRepository, atLeastOnce()).save(cartCaptor.capture());

			List<Cart> savedCarts = cartCaptor.getAllValues();
			assertThat(savedCarts).isNotEmpty();

			Cart finalSavedCart = savedCarts.get(savedCarts.size() - 1);

			verify(cartMapper).toDTO(finalSavedCart);

			assertThat(finalSavedCart.getUser()).isEqualTo(user);
			assertThat(finalSavedCart.getItems()).isEmpty();
		}
	}

	@Nested
	class ClearCartTests {

		@Test
		void clearCart_shouldClearCurrentUserCartWhenCartExists() {
			User user = user();
			Cart cart = new Cart(user);
			Product product = product(12, 10);
			cart.addItem(product, 1);

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartRepository.findByUserIdWithItemsForUpdate(user.getId())).thenReturn(Optional.of(cart));

			service.clearCart();

			assertThat(cart.getItems()).isEmpty();
			verify(userService).getCurrentUserEntity();
			verify(cartRepository).findByUserIdWithItemsForUpdate(user.getId());
			verify(cartRepository).save(cart);
		}

		@Test
		void clearCart_shouldDoNothingWhenCurrentUserCartMissing() {
			User user = user();

			when(userService.getCurrentUserEntity()).thenReturn(user);
			when(cartRepository.findByUserIdWithItemsForUpdate(user.getId())).thenReturn(Optional.empty());

			service.clearCart();

			verify(userService).getCurrentUserEntity();
			verify(cartRepository).findByUserIdWithItemsForUpdate(user.getId());
			verify(cartRepository, never()).save(any(Cart.class));
		}

		@Test
		void clearCartForUser_shouldClearAndSaveWhenCartExists() {
			User user = user();
			Cart cart = new Cart(user);
			Product product = product(13, 10);
			cart.addItem(product, 2);

			when(cartRepository.findByUserIdWithItemsForUpdate(user.getId())).thenReturn(Optional.of(cart));

			service.clearCartForUser(user.getId());

			assertThat(cart.getItems()).isEmpty();
			verify(cartRepository).findByUserIdWithItemsForUpdate(user.getId());
			verify(cartRepository).save(cart);
		}

		@Test
		void clearCartForUser_shouldDoNothingWhenCartMissing() {
			UUID userId = UUID.randomUUID();

			when(cartRepository.findByUserIdWithItemsForUpdate(userId)).thenReturn(Optional.empty());

			service.clearCartForUser(userId);

			verify(cartRepository).findByUserIdWithItemsForUpdate(userId);
			verify(cartRepository, never()).save(any(Cart.class));
		}
	}

	@Nested
	class ReconcileCartTests {

		@Test
		void reconcileCartForUser_shouldApplyEveryCheckedOutLineAndSave() {
			User user = user();
			Product firstProduct = product(21, 10);
			Product secondProduct = product(22, 10);
			Product unrelatedProduct = product(23, 10);
			Cart cart = new Cart(user);
			cart.addItem(firstProduct, 1);
			cart.addItem(secondProduct, 3);
			cart.addItem(unrelatedProduct, 1);

			when(cartRepository.findByUserIdWithItemsForUpdate(user.getId())).thenReturn(Optional.of(cart));

			service.reconcileCartForUser(user.getId(), List.of(
					new com.company.shop.module.cart.api.internal.CartCheckoutItem(firstProduct.getId(), 1),
					new com.company.shop.module.cart.api.internal.CartCheckoutItem(secondProduct.getId(), 1)));

			assertThat(cart.getItems())
					.extracting(item -> item.getProduct().getId(), CartItem::getQuantity)
					.containsExactlyInAnyOrder(
							org.assertj.core.groups.Tuple.tuple(secondProduct.getId(), 2),
							org.assertj.core.groups.Tuple.tuple(unrelatedProduct.getId(), 1));
			verify(cartRepository).save(cart);
		}

		@Test
		void reconcileCartForUser_shouldDoNothingWhenCartIsMissing() {
			UUID userId = UUID.randomUUID();
			when(cartRepository.findByUserIdWithItemsForUpdate(userId)).thenReturn(Optional.empty());

			service.reconcileCartForUser(userId, List.of());

			verify(cartRepository, never()).save(any(Cart.class));
		}
	}

	@Nested
	class GetCartEntityForUserTests {

		@Test
		void getCartEntityForUser_shouldReturnCartWhenExists() {
			User user = user();
			Cart cart = new Cart(user);

			when(cartRepository.findByUserIdWithItems(user.getId())).thenReturn(Optional.of(cart));

			Cart result = service.getCartEntityForUser(user.getId());

			assertThat(result).isSameAs(cart);
			verify(cartRepository).findByUserIdWithItems(user.getId());
			verify(cartMapper, never()).toDTO(any(Cart.class));
		}

		@Test
		void getCartEntityForUser_shouldThrowCartNotFoundWhenMissing() {
			UUID userId = UUID.randomUUID();

			when(cartRepository.findByUserIdWithItems(userId)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> service.getCartEntityForUser(userId)).isInstanceOf(CartNotFoundException.class);

			verify(cartRepository).findByUserIdWithItems(userId);
			verify(cartMapper, never()).toDTO(any(Cart.class));
		}
	}

	private User user() {
		User user = new User("john@example.com", "encoded", "John", "Doe");
		setEntityId(user, UUID.randomUUID());
		return user;
	}

	private Product product(int unique, int stock) {
		Category category = new Category("Category-" + unique, "category-" + unique, "desc");
		Product product = new Product("Product-" + unique, "product-" + unique, "SKU-" + unique, "desc", BigDecimal.TEN,
				stock, category);
		setEntityId(product, UUID.randomUUID());
		return product;
	}

	private CartResponseDTO cartResponse() {
		return new CartResponseDTO(UUID.randomUUID(), List.of(), BigDecimal.ZERO, 0);
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
