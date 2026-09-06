package com.company.shop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.userdetails.User.withUsername;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.company.shop.module.cart.service.CartService;
import com.company.shop.module.category.service.CategoryService;
import com.company.shop.module.notification.delivery.NotificationDeliveryProcessor;
import com.company.shop.module.notification.delivery.NotificationDeliveryTransactionalWorker;
import com.company.shop.module.notification.outbox.OrderPlacedNotificationHandler;
import com.company.shop.module.notification.service.NotificationAdminActionLogQueryService;
import com.company.shop.module.notification.service.NotificationAdminCommandService;
import com.company.shop.module.notification.service.NotificationQueryService;
import com.company.shop.module.notification.service.NotificationService;
import com.company.shop.module.order.expiration.LegacyReservationService;
import com.company.shop.module.order.expiration.ReservationExpirationClaimService;
import com.company.shop.module.order.expiration.ReservationExpirationMetrics;
import com.company.shop.module.order.expiration.ReservationExpirationPoller;
import com.company.shop.module.order.expiration.ReservationExpirationProcessor;
import com.company.shop.module.order.expiration.ReservationExpirationAdminActionLogQueryService;
import com.company.shop.module.order.expiration.ReservationExpirationRecoveryService;
import com.company.shop.module.order.expiration.ReservationExpirationWorkQueryService;
import com.company.shop.module.order.outbox.OutboxEventAdminActionLogQueryService;
import com.company.shop.module.order.outbox.OutboxEventAdminCommandService;
import com.company.shop.module.order.outbox.OutboxEventFailureRecorder;
import com.company.shop.module.order.outbox.OutboxEventProcessor;
import com.company.shop.module.order.outbox.OutboxEventQueryService;
import com.company.shop.module.order.outbox.OrderOutboxEventRecorder;
import com.company.shop.module.order.outbox.OutboxEventTransactionalWorker;
import com.company.shop.module.order.service.OrderService;
import com.company.shop.module.order.service.PaymentService;
import com.company.shop.module.order.service.PaymentInitializationTransactionService;
import com.company.shop.module.order.service.PaymentTerminalTransitionService;
import com.company.shop.module.order.service.StripeWebhookEventRegistrar;
import com.company.shop.module.order.service.checkout.OrderCheckoutProcessor;
import com.company.shop.module.order.service.query.OrderQueryProcessor;
import com.company.shop.module.product.api.internal.ProductCatalogFacade;
import com.company.shop.module.product.service.ProductReviewService;
import com.company.shop.module.product.service.ProductService;
import com.company.shop.module.system.service.ApplicationStatusService;
import com.company.shop.module.user.repository.RoleRepository;
import com.company.shop.module.user.service.UserService;
import com.company.shop.security.AuthService;
import com.company.shop.config.ProductionDatabaseOwnershipValidator;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.UserRolesStartupValidator;
import com.company.shop.security.jwt.JwtTokenProvider;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                "spring.datasource.url=jdbc:postgresql://localhost:5432/test",
                "spring.datasource.username=test",
                "spring.datasource.password=test",
                "spring.flyway.url=jdbc:postgresql://localhost:5432/test",
                "spring.flyway.user=migration",
                "spring.flyway.password=test",
                "security.jwt.key-id=test-current",
                "security.jwt.secret=dGVzdC1vbmx5LTMyLWJ5dGUtand0LXNlY3JldC1rZXkh",
                "stripe.api-key=sk_test_placeholder",
                "stripe.webhook-secret=whsec_placeholder",
                "stripe.public-key=pk_test_placeholder"
        })
@ActiveProfiles("prod")
@MockitoBean(types = {
        ProductionDatabaseOwnershipValidator.class, AuthService.class, ApplicationStatusService.class,
        CategoryService.class, ProductService.class,
        ProductCatalogFacade.class, ProductReviewService.class, CartService.class, UserService.class,
        NotificationService.class, NotificationQueryService.class, NotificationAdminCommandService.class,
        NotificationAdminActionLogQueryService.class, OrderPlacedNotificationHandler.class,
        NotificationDeliveryProcessor.class, NotificationDeliveryTransactionalWorker.class, OrderService.class,
        ReservationExpirationClaimService.class, ReservationExpirationMetrics.class, ReservationExpirationPoller.class,
        ReservationExpirationProcessor.class, ReservationExpirationRecoveryService.class,
        ReservationExpirationWorkQueryService.class,
        ReservationExpirationAdminActionLogQueryService.class, LegacyReservationService.class,
        OrderCheckoutProcessor.class, OrderQueryProcessor.class, OrderOutboxEventRecorder.class,
        OutboxEventProcessor.class, OutboxEventTransactionalWorker.class, OutboxEventFailureRecorder.class,
        OutboxEventQueryService.class, OutboxEventAdminCommandService.class,
        OutboxEventAdminActionLogQueryService.class, PaymentService.class,
        PaymentInitializationTransactionService.class, PaymentTerminalTransitionService.class,
        StripeWebhookEventRegistrar.class,
        UserRolesStartupValidator.class
})
@MockitoBean(name = "jpaMappingContext", types = JpaMetamodelMappingContext.class)
class ProductionOpenApiExposureHttpTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private RoleRepository roleRepository;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.validate(anyString())).thenReturn(false);
        when(roleRepository.existsByName(anyString())).thenReturn(true);
    }

    @Test
    void productionDocumentation_shouldBeUnavailableToAnonymousAndAdminCallers() throws Exception {
        when(jwtTokenProvider.validate("admin-token")).thenReturn(true);
        when(jwtTokenProvider.getUsername("admin-token")).thenReturn("admin@example.com");
        when(userDetailsService.loadUserByUsername("admin@example.com"))
                .thenReturn(withUsername("admin@example.com").password("password").roles("ADMIN").build());

        assertThat(get("/swagger-ui.html").statusCode()).isEqualTo(403);
        assertThat(get("/swagger-ui.html", "admin-token").statusCode()).isEqualTo(404);

        for (String endpoint : new String[] {
                "/swagger-ui/index.html",
                "/swagger-ui/swagger-initializer.js",
                "/api-docs",
                "/api-docs/",
                "/api-docs/all-api",
                "/api-docs/public-api",
                "/api-docs/customer-api",
                "/api-docs/admin-api",
                "/api-docs/webhooks-api",
                "/api-docs/system-api",
                "/v3/api-docs",
                "/v3/api-docs/admin-api"
        }) {
            assertThat(get(endpoint).statusCode()).as("anonymous GET %s", endpoint).isEqualTo(404);
            assertThat(get(endpoint, "admin-token").statusCode()).as("admin GET %s", endpoint).isEqualTo(404);
        }
    }

    @Test
    void productionSecurityBoundaries_shouldRemainUnchanged() throws Exception {
        assertThat(get("/api/v1").statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/me").statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/admin/notifications").statusCode()).isEqualTo(403);
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/info").statusCode()).isEqualTo(403);
        assertThat(get("/actuator/metrics").statusCode()).isEqualTo(403);
        assertThat(get("/actuator/prometheus").statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return get(path, null);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().send(
                request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
