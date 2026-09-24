package com.company.shop.module.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.repository.CartRepository;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.category.repository.CategoryRepository;
import com.company.shop.module.notification.delivery.NotificationDeliveryProcessor;
import com.company.shop.module.notification.delivery.NotificationDeliveryResult;
import com.company.shop.module.notification.delivery.NotificationSender;
import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.entity.NotificationStatus;
import com.company.shop.module.notification.repository.NotificationRepository;
import com.company.shop.module.order.dto.OrderCheckoutRequestDTO;
import com.company.shop.module.order.dto.OrderResponseDTO;
import com.company.shop.module.order.dto.PaymentIntentResponseDTO;
import com.company.shop.module.order.outbox.OrderOutboxEventTypes;
import com.company.shop.module.order.outbox.OutboxEvent;
import com.company.shop.module.order.outbox.OutboxEventProcessingResult;
import com.company.shop.module.order.outbox.OutboxEventProcessor;
import com.company.shop.module.order.outbox.OutboxEventRepository;
import com.company.shop.module.order.outbox.OutboxEventStatus;
import com.company.shop.module.order.service.OrderService;
import com.company.shop.module.order.service.PaymentService;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.user.api.internal.CurrentUserFacade;
import com.company.shop.module.user.api.internal.CurrentUserSnapshot;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.persistence.support.PostgresContainerSupport;

@SpringBootTest(properties = {
        "app.outbox.processing.enabled=false",
        "app.notification.delivery.enabled=false",
        "app.notification.smtp.enabled=false"
})
@ActiveProfiles("test")
class OrderNotificationPipelineIT extends PostgresContainerSupport {

    private static final String CHECKOUT_EMAIL = "pipeline@example.com";

    @Autowired private OrderService orderService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CartRepository cartRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private OutboxEventProcessor outboxEventProcessor;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationDeliveryProcessor notificationDeliveryProcessor;
    @Autowired private RecordingNotificationSender notificationSender;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private CurrentUserFacade currentUserFacade;
    @MockitoBean private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE notifications, outbox_events, payments, reservation_expiration_work,
                    order_items, orders, cart_items, carts, products, categories, users
                RESTART IDENTITY CASCADE
                """);
        notificationSender.reset();
        when(paymentService.createPaymentIntent(any()))
                .thenReturn(new PaymentIntentResponseDTO("pi_pipeline_test", "pk_pipeline_test"));
    }

    @Test
    void checkout_shouldComposeDurableOrderNotificationPipelineAndRemainIdempotent() {
        Category category = categoryRepository.saveAndFlush(new Category(
                "Pipeline category", "pipeline-category", "Durable notification pipeline test"));
        Product product = productRepository.saveAndFlush(new Product(
                "Pipeline product", "pipeline-product", "PIPELINE-1", "Pipeline test product",
                new BigDecimal("19.99"), 3, category));
        User user = userRepository.saveAndFlush(new User(CHECKOUT_EMAIL, "encoded", "Pipeline", "User"));
        Cart cart = new Cart(user);
        cart.addItem(product, 2);
        cartRepository.saveAndFlush(cart);
        when(currentUserFacade.getCurrentUser())
                .thenReturn(new CurrentUserSnapshot(user.getId(), CHECKOUT_EMAIL, Set.of()));

        OrderResponseDTO checkout = orderService.placeOrderFromCart(
                "pipeline-checkout", new OrderCheckoutRequestDTO(null, null));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE id = ?", Long.class, checkout.id())).isOne();
        List<OutboxEvent> createdEvents = outboxEventRepository.findAll();
        assertThat(createdEvents).singleElement().satisfies(event -> {
            assertThat(event.getAggregateId()).isEqualTo(checkout.id());
            assertThat(event.getEventType()).isEqualTo(OrderOutboxEventTypes.ORDER_PLACED);
            assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
            assertThat(event.getProcessedAt()).isNull();
        });
        OutboxEvent event = createdEvents.getFirst();
        assertThat(notificationRepository.count()).isZero();

        jdbcTemplate.update("UPDATE users SET email = ? WHERE id = ?", "changed@example.com", user.getId());
        OutboxEventProcessingResult outboxResult = outboxEventProcessor.processPendingBatch(10);

        assertThat(outboxResult.processedCount()).isOne();
        assertThat(outboxResult.failedCount()).isZero();
        OutboxEvent processedEvent = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(processedEvent.getStatus()).isEqualTo(OutboxEventStatus.PROCESSED);
        assertThat(processedEvent.getProcessedAt()).isNotNull();
        List<Notification> createdNotifications = notificationRepository.findAll();
        assertThat(createdNotifications).singleElement().satisfies(notification -> {
            assertThat(notification.getSourceEventId()).isEqualTo(event.getId());
            assertThat(notification.getRecipient()).isEqualTo(CHECKOUT_EMAIL);
            assertThat(notification.getSubject()).contains(checkout.id().toString());
            assertThat(notification.getBody())
                    .contains(checkout.id().toString())
                    .contains(checkout.totalAmount().toString());
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
            assertThat(notification.getAttempts()).isZero();
            assertThat(notification.getSentAt()).isNull();
        });
        Notification pendingNotification = createdNotifications.getFirst();

        NotificationDeliveryResult deliveryResult = notificationDeliveryProcessor.processPendingBatch(10);

        assertThat(deliveryResult.sentCount()).isOne();
        assertThat(deliveryResult.failedCount()).isZero();
        assertThat(notificationSender.sent()).singleElement().satisfies(sent -> {
            assertThat(sent.id()).isEqualTo(pendingNotification.getId());
            assertThat(sent.recipient()).isEqualTo(CHECKOUT_EMAIL);
            assertThat(sent.sourceEventId()).isEqualTo(event.getId());
        });
        Notification sentNotification = notificationRepository.findById(pendingNotification.getId()).orElseThrow();
        assertThat(sentNotification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(sentNotification.getSentAt()).isNotNull();
        assertThat(sentNotification.getAttempts()).isOne();
        assertThat(sentNotification.getLastAttemptAt()).isNotNull();
        assertThat(sentNotification.getLastError()).isNull();
        assertThat(sentNotification.getNextAttemptAt()).isNull();
        assertThat(sentNotification.getClaimToken()).isNull();
        assertThat(sentNotification.getClaimExpiresAt()).isNull();

        assertThat(outboxEventProcessor.processPendingBatch(10))
                .isEqualTo(new OutboxEventProcessingResult(0, 0));
        assertThat(notificationDeliveryProcessor.processPendingBatch(10))
                .isEqualTo(new NotificationDeliveryResult(0, 0));
        assertThat(notificationRepository.count()).isOne();
        assertThat(notificationRepository.findBySourceEventId(event.getId()))
                .get()
                .extracting(Notification::getId, Notification::getStatus)
                .containsExactly(sentNotification.getId(), NotificationStatus.SENT);
        assertThat(notificationSender.sent()).hasSize(1);
    }

    @TestConfiguration
    static class SenderConfiguration {
        @Bean
        @Primary
        RecordingNotificationSender recordingNotificationSender() {
            return new RecordingNotificationSender();
        }
    }

    static final class RecordingNotificationSender implements NotificationSender {
        private final CopyOnWriteArrayList<SentNotification> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(Notification notification) {
            sent.add(new SentNotification(
                    notification.getId(), notification.getRecipient(), notification.getSourceEventId()));
        }

        List<SentNotification> sent() {
            return List.copyOf(sent);
        }

        void reset() {
            sent.clear();
        }
    }

    record SentNotification(java.util.UUID id, String recipient, java.util.UUID sourceEventId) { }
}
