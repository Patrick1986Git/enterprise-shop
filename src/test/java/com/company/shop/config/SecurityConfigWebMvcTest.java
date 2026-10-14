package com.company.shop.config;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.module.cart.controller.CartController;
import com.company.shop.module.cart.service.CartService;
import com.company.shop.module.category.controller.AdminCategoryController;
import com.company.shop.module.category.controller.CategoryController;
import com.company.shop.module.category.service.CategoryService;
import com.company.shop.module.order.controller.AdminOrderController;
import com.company.shop.module.order.controller.CurrentUserOrderController;
import com.company.shop.module.order.controller.OrderController;
import com.company.shop.module.order.controller.StripeWebhookController;
import com.company.shop.module.order.service.OrderService;
import com.company.shop.module.order.expiration.ReservationExpirationRecoveryService;
import com.company.shop.module.order.expiration.ReservationExpirationWorkQueryService;
import com.company.shop.module.order.expiration.ReservationExpirationAdminActionLogQueryService;
import com.company.shop.module.order.expiration.LegacyReservationService;
import com.company.shop.module.order.service.PaymentService;
import com.company.shop.module.order.service.StripeWebhookProcessor;
import com.company.shop.module.product.controller.AdminProductController;
import com.company.shop.module.product.controller.ProductController;
import com.company.shop.module.product.controller.ProductReviewController;
import com.company.shop.module.product.dto.ProductReviewResponseDTO;
import com.company.shop.module.product.service.ProductReviewService;
import com.company.shop.module.product.service.ProductService;
import com.company.shop.module.system.controller.HomeController;
import com.company.shop.module.system.controller.SystemController;
import com.company.shop.module.system.dto.ApplicationStatusDTO;
import com.company.shop.module.system.service.ApplicationStatusService;
import com.company.shop.module.user.controller.AdminUserController;
import com.company.shop.module.user.controller.UserController;
import com.company.shop.module.user.dto.AuthResponseDTO;
import com.company.shop.common.i18n.MessageService;
import com.company.shop.module.user.dto.UserResponseDTO;
import com.company.shop.module.user.service.UserService;
import com.company.shop.security.AuthController;
import com.company.shop.security.AuthService;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.company.shop.support.TestMeterRegistryConfig;

@WebMvcTest(controllers = {
        AuthController.class,
        HomeController.class,
        SystemController.class,
        CategoryController.class,
        AdminCategoryController.class,
        ProductController.class,
        ProductReviewController.class,
        AdminProductController.class,
        CartController.class,
        UserController.class,
        AdminUserController.class,
        OrderController.class,
        CurrentUserOrderController.class,
        AdminOrderController.class,
        StripeWebhookController.class
})
@Import({ SecurityConfig.class, JwtAuthenticationFilter.class, TestMeterRegistryConfig.class, MessageService.class })
class SecurityConfigWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private ApplicationStatusService applicationStatusService;
    @MockitoBean
    private CategoryService categoryService;
    @MockitoBean
    private ProductService productService;
    @MockitoBean
    private ProductReviewService productReviewService;
    @MockitoBean
    private CartService cartService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private OrderService orderService;
    @MockitoBean
    private ReservationExpirationRecoveryService reservationExpirationRecoveryService;
    @MockitoBean
    private ReservationExpirationWorkQueryService reservationExpirationWorkQueryService;
    @MockitoBean
    private ReservationExpirationAdminActionLogQueryService reservationExpirationAdminActionLogQueryService;

    @MockitoBean
    private LegacyReservationService legacyReservationService;
    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private StripeWebhookProcessor stripeWebhookProcessor;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;
    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.validate(anyString())).thenReturn(false);

        when(authService.login(any())).thenReturn(new AuthResponseDTO("token"));
        when(applicationStatusService.getApplicationStatus()).thenReturn(
                new ApplicationStatusDTO("shop", "1.0", "test", Instant.now(), List.of("test"), "17", "Linux", "1", "localhost"));
        when(userService.getCurrentUserProfile()).thenReturn(
                new UserResponseDTO(UUID.randomUUID(), "user@example.com", "John", "Doe", true,
                        Set.of("ROLE_USER")));

        when(productService.findAll(any())).thenReturn(Page.empty());
        when(categoryService.findAll(any())).thenReturn(Page.empty());
        when(orderService.findAll(any())).thenReturn(Page.empty());
        when(orderService.findMyOrders(any())).thenReturn(Page.empty());

        when(productReviewService.addReview(any())).thenReturn(
                new ProductReviewResponseDTO(UUID.randomUUID(), "tester", 5, "ok", LocalDateTime.now()));
    }

    @ParameterizedTest
    @MethodSource("publicGetEndpoints")
    void publicGetEndpoints_shouldBeAccessibleForAnonymous(String endpoint) throws Exception {
        mockMvc.perform(get(endpoint))
                .andExpect(status().isOk());
    }

    @Test
    void publicPostAuth_shouldAllowAnonymousWithoutCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"user@example.com\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
    }

    @Test
    void publicPostRegister_shouldAllowAnonymousWithoutCsrf() throws Exception {
        String registerBody = """
                {
                  "email": "user@example.com",
                  "password": "secret123",
                  "passwordRepeat": "secret123",
                  "firstName": "John",
                  "lastName": "Doe"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
    }

    @Test
    void webhookPost_shouldBypassCsrfAndBePublic() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/stripe")
                        .contentType("application/json")
                        .header("Stripe-Signature", "sig")
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @MethodSource("authenticatedEndpoints")
    void authenticatedEndpoints_shouldDenyAnonymous(String endpoint) throws Exception {
        mockMvc.perform(get(endpoint))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @MethodSource("authenticatedEndpoints")
    void authenticatedEndpoints_shouldAllowAuthenticatedUser(String endpoint) throws Exception {
        mockMvc.perform(get(endpoint)
                        .with(user("user").roles("USER")))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @MethodSource("adminGetEndpoints")
    void adminGetEndpoints_shouldAllowOnlyAdmin(String endpoint) throws Exception {
        mockMvc.perform(get(endpoint))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(endpoint)
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(endpoint)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void corsPreflight_shouldAllowXRequestIdHeader() throws Exception {
        mockMvc.perform(options("/api/v1/products")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "X-Request-Id, Idempotency-Key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("X-Request-Id")))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("Idempotency-Key")));
    }

    @Test
    void corsPatchPreflight_shouldAllowBrowserContract() throws Exception {
        mockMvc.perform(options("/api/v1/me/cart/items/{productId}", UUID.randomUUID())
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers",
                                "Authorization, Content-Type, X-Request-Id, Idempotency-Key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("PATCH")))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("Authorization")))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("Content-Type")))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("X-Request-Id")))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("Idempotency-Key")));
    }

    @Test
    void corsPreflight_shouldNotAllowUnknownOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/products")
                        .header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void corsActualRequest_shouldExposeXRequestIdHeader() throws Exception {
        mockMvc.perform(get("/api/v1")
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Authorization")))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("X-Request-Id")));
    }

    @Test
    void publicEndpoint_shouldContainCoreSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/v1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("Expires", "0"));
    }

    @Test
    void secureRequest_shouldContainHstsHeader() throws Exception {
        mockMvc.perform(get("/api/v1").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=")));
    }

    @Test
    void methodSecuredReviewEndpoint_shouldRequireAuthenticatedUser() throws Exception {
        String body = "{\"productId\":\"" + UUID.randomUUID() + "\",\"rating\":5,\"comment\":\"great\"}";

        mockMvc.perform(post("/api/v1/reviews")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/reviews")
                        .with(user("user").roles("USER"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void methodSecuredDeleteReview_shouldRequireAuthenticatedUser() throws Exception {
        UUID reviewId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/reviews/{reviewId}", reviewId))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/reviews/{reviewId}", reviewId)
                        .with(user("user").roles("USER")))
                .andExpect(status().isNoContent());
    }

    @Test
    void checkout_shouldDenyAnonymousWithoutCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/me/orders/checkout")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void checkout_shouldNotBeBlockedBySecurity_whenAuthenticatedUserHasNoCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/me/orders/checkout")
                        .with(user("user").roles("USER"))
                        .header("Idempotency-Key", "checkout-key")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isCreated());
    }

    @Test
    void cartPatch_shouldDenyAnonymousWithoutCsrf() throws Exception {
        UUID productId = UUID.randomUUID();

        mockMvc.perform(patch("/api/v1/me/cart/items/{productId}", productId)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cartPatch_shouldNotBeBlockedBySecurity_whenAuthenticatedUserHasNoCsrf() throws Exception {
        UUID productId = UUID.randomUUID();

        mockMvc.perform(patch("/api/v1/me/cart/items/{productId}", productId)
                        .with(user("user").roles("USER"))
                        .contentType("application/json")
                        .content("{}"))
                // 400 confirms request passed security layer (authentication) and failed later on request validation/handling.
                .andExpect(status().isBadRequest());
    }

    @Test
    void cartDelete_shouldDenyAnonymousWithoutCsrf() throws Exception {
        mockMvc.perform(delete("/api/v1/me/cart"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cartDelete_shouldNotBeBlockedBySecurity_whenAuthenticatedUserHasNoCsrf() throws Exception {
        mockMvc.perform(delete("/api/v1/me/cart")
                        .with(user("user").roles("USER")))
                .andExpect(status().isNoContent());
    }

    @Test
    void adminProductCreate_shouldDenyAnonymousWithoutCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/admin/products")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminProductCreate_shouldNotBeBlockedBySecurity_whenAdminHasNoCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/admin/products")
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content("{}"))
                // 400 confirms request passed security layer (admin authorization) and failed later on request validation/handling.
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminProductCreate_shouldDenyUserRole_withoutCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/admin/products")
                        .with(user("user").roles("USER"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCategoryUpdate_shouldEnforceRoleWithoutCsrf() throws Exception {
        UUID categoryId = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/admin/categories/{id}", categoryId)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/admin/categories/{id}", categoryId)
                        .with(user("user").roles("USER"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/admin/categories/{id}", categoryId)
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content("{}"))
                // 400 confirms request passed security layer (admin authorization) and failed later on request validation/handling.
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    void storedSessionSecurityContext_shouldNotAuthenticateApiRequests(String role) throws Exception {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken("session-user", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        for (String path : List.of("/api/v1/me", "/api/v1/admin/users")) {
            mockMvc.perform(get(path).session(session))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(delete("/api/v1/me/cart").session(session))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/css/csrf-probe", "/api/v10/auth/register", "/api/v1-other/auth/register"})
    void outsideApiScope_shouldStillRequireCsrf(String path) throws Exception {
        mockMvc.perform(post(path).with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(path).with(user("user").roles("USER")).with(csrf()))
                .andExpect(status().isNotFound());
    }

    private static Stream<String> publicGetEndpoints() {
        return Stream.of(
                "/api/v1",
                "/api/v1/products",
                "/api/v1/products/search",
                "/api/v1/products/slug/test-product",
                "/api/v1/products/" + UUID.randomUUID() + "/reviews",
                "/api/v1/categories",
                "/api/v1/categories/slug/test-category");
    }

    private static Stream<String> authenticatedEndpoints() {
        return Stream.of(
                "/api/v1/system/status",
                "/api/v1/me",
                "/api/v1/me/cart",
                "/api/v1/me/orders",
                "/api/v1/orders/" + UUID.randomUUID());
    }

    private static Stream<String> adminGetEndpoints() {
        return Stream.of(
                "/api/v1/admin/orders",
                "/api/v1/admin/users",
                "/api/v1/admin/products/" + UUID.randomUUID(),
                "/api/v1/admin/categories/" + UUID.randomUUID());
    }
}
