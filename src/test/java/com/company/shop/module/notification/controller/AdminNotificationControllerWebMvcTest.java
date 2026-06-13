package com.company.shop.module.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.module.notification.dto.NotificationAdminActionLogResponseDTO;
import com.company.shop.module.notification.dto.NotificationResponseDTO;
import com.company.shop.module.notification.dto.NotificationSummaryDTO;
import com.company.shop.module.notification.entity.NotificationAdminActionType;
import com.company.shop.module.notification.entity.NotificationStatus;
import com.company.shop.module.notification.exception.NotificationNotFoundException;
import com.company.shop.module.notification.exception.NotificationRequeueNotAllowedException;
import com.company.shop.module.notification.service.NotificationAdminActionLogQueryService;
import com.company.shop.module.notification.service.NotificationAdminCommandService;
import com.company.shop.module.notification.service.NotificationQueryService;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.company.shop.support.WebMvcSliceTestConfig;

@WebMvcTest(controllers = AdminNotificationController.class)
@ActiveProfiles("test")
@Import(WebMvcSliceTestConfig.class)
class AdminNotificationControllerWebMvcTest {

    private static final String ADMIN_NOTIFICATIONS_URL = "/api/v1/admin/notifications";
    private static final Instant LAST_ATTEMPT_AT = Instant.parse("2026-01-01T10:05:00Z");
    private static final Instant LAST_REQUEUED_AT = Instant.parse("2026-01-01T10:10:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationQueryService notificationQueryService;

    @MockitoBean
    private NotificationAdminCommandService notificationAdminCommandService;

    @MockitoBean
    private NotificationAdminActionLogQueryService notificationAdminActionLogQueryService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.validate(anyString())).thenReturn(false);
    }

    @Test
    void getNotifications_shouldReturnForbiddenForAnonymous() throws Exception {
        mockMvc.perform(get(ADMIN_NOTIFICATIONS_URL))
                .andExpect(status().isForbidden());

        verifyNoInteractions(notificationQueryService);
    }

    @Test
    void getNotifications_shouldReturnForbiddenForUserWithoutAdminRole() throws Exception {
        mockMvc.perform(get(ADMIN_NOTIFICATIONS_URL)
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(notificationQueryService);
    }

    @Test
    void getNotifications_shouldReturnPagedNotificationsForAdmin() throws Exception {
        NotificationResponseDTO notification = responseWithLastAttemptAt(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"));
        when(notificationQueryService.getNotifications(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(notification), PageRequest.of(0, 20), 1));

        mockMvc.perform(get(ADMIN_NOTIFICATIONS_URL)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.content[0].type").value("ORDER_PLACED_EMAIL"))
                .andExpect(jsonPath("$.content[0].recipient").value("customer@example.com"))
                .andExpect(jsonPath("$.content[0].subject").value("Order placed"))
                .andExpect(jsonPath("$.content[0].body").value("Your order has been placed."))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].sourceEventId").value("22222222-2222-2222-2222-222222222222"))
                .andExpect(jsonPath("$.content[0].attempts").value(0))
                .andExpect(jsonPath("$.content[0].requeueCount").value(1))
                .andExpect(jsonPath("$.content[0].lastRequeuedAt").value("2026-01-01T10:10:00Z"))
                .andExpect(jsonPath("$.content[0].lastRequeuedBy").value("admin@example.com"))
                .andExpect(jsonPath("$.content[0].lastAttemptAt").value("2026-01-01T10:05:00Z"))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20));

        verify(notificationQueryService).getNotifications(eq(null), eq(null), eq(null), eq(null), any(Pageable.class));
    }

    @Test
    void getNotifications_shouldPassFiltersAndPageableToService() throws Exception {
        when(notificationQueryService.getNotifications(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5), 0));

        mockMvc.perform(get(ADMIN_NOTIFICATIONS_URL)
                        .with(user("admin").roles("ADMIN"))
                        .param("status", "FAILED")
                        .param("type", "ORDER_PLACED_EMAIL")
                        .param("recipient", "customer")
                        .param("page", "2")
                        .param("size", "5")
                        .param("sort", "createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.number").value(2))
                .andExpect(jsonPath("$.size").value(5));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationQueryService).getNotifications(
                eq(NotificationStatus.FAILED),
                eq("ORDER_PLACED_EMAIL"),
                eq("customer"),
                eq(null),
                pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(5);
        assertThat(pageable.getSort().getOrderFor("createdAt")).isNotNull();
        assertThat(pageable.getSort().getOrderFor("createdAt").getDirection().name()).isEqualTo("DESC");
    }

    @Test
    void getNotifications_shouldPassRequeuedOnlyToService() throws Exception {
        when(notificationQueryService.getNotifications(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(ADMIN_NOTIFICATIONS_URL)
                        .with(user("admin").roles("ADMIN"))
                        .param("requeuedOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray());

        verify(notificationQueryService).getNotifications(
                eq(null),
                eq(null),
                eq(null),
                eq(Boolean.TRUE),
                any(Pageable.class));
    }

    @Test
    void getSummary_shouldReturnForbiddenForAnonymous() throws Exception {
        mockMvc.perform(get(ADMIN_NOTIFICATIONS_URL + "/summary"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(notificationQueryService);
    }

    @Test
    void getSummary_shouldReturnForbiddenForUserWithoutAdminRole() throws Exception {
        mockMvc.perform(get(ADMIN_NOTIFICATIONS_URL + "/summary")
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(notificationQueryService);
    }

    @Test
    void getSummary_shouldReturnSummaryForAdmin() throws Exception {
        when(notificationQueryService.getSummary())
                .thenReturn(new NotificationSummaryDTO(3, 5, 7, 2, 1, 4, 6));

        mockMvc.perform(get(ADMIN_NOTIFICATIONS_URL + "/summary")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.pendingCount").value(3))
                .andExpect(jsonPath("$.sentCount").value(5))
                .andExpect(jsonPath("$.failedCount").value(7))
                .andExpect(jsonPath("$.duePendingCount").value(2))
                .andExpect(jsonPath("$.scheduledPendingCount").value(1))
                .andExpect(jsonPath("$.requeuedNotificationCount").value(4))
                .andExpect(jsonPath("$.totalRequeueCount").value(6));

        verify(notificationQueryService).getSummary();
        verifyNoMoreInteractions(notificationQueryService);
    }

    @Test
    void requeueNotification_shouldReturnForbiddenForAnonymous() throws Exception {
        UUID notificationId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        mockMvc.perform(post(ADMIN_NOTIFICATIONS_URL + "/{id}/requeue", notificationId)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(notificationAdminCommandService);
    }

    @Test
    void requeueNotification_shouldReturnForbiddenForUserWithoutAdminRole() throws Exception {
        UUID notificationId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        mockMvc.perform(post(ADMIN_NOTIFICATIONS_URL + "/{id}/requeue", notificationId)
                        .with(user("user").roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(notificationAdminCommandService);
    }

    @Test
    void requeueNotification_shouldRequeueNotificationForAdmin() throws Exception {
        UUID notificationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        NotificationResponseDTO response = response(
                notificationId,
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                NotificationStatus.PENDING,
                null,
                1,
                LAST_REQUEUED_AT);
        when(notificationAdminCommandService.requeueFailedNotification(notificationId)).thenReturn(response);

        mockMvc.perform(post(ADMIN_NOTIFICATIONS_URL + "/{id}/requeue", notificationId)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.requeueCount").value(1))
                .andExpect(jsonPath("$.lastRequeuedAt").value("2026-01-01T10:10:00Z"))
                .andExpect(jsonPath("$.lastRequeuedBy").value("admin@example.com"))
                .andExpect(jsonPath("$.lastError").doesNotExist())
                .andExpect(jsonPath("$.sentAt").doesNotExist())
                .andExpect(jsonPath("$.nextAttemptAt").doesNotExist())
                .andExpect(jsonPath("$.lastAttemptAt").doesNotExist());

        verify(notificationAdminCommandService).requeueFailedNotification(notificationId);
        verifyNoMoreInteractions(notificationAdminCommandService);
    }

    @Test
    void requeueNotification_shouldReturnNotFoundWhenMissing() throws Exception {
        UUID notificationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(notificationAdminCommandService.requeueFailedNotification(notificationId))
                .thenThrow(new NotificationNotFoundException(notificationId));

        mockMvc.perform(post(ADMIN_NOTIFICATIONS_URL + "/{id}/requeue", notificationId)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.errorCode").value("NOTIFICATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());

        verify(notificationAdminCommandService).requeueFailedNotification(notificationId);
    }

    @Test
    void requeueNotification_shouldReturnConflictWhenStatusInvalid() throws Exception {
        UUID notificationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(notificationAdminCommandService.requeueFailedNotification(notificationId))
                .thenThrow(new NotificationRequeueNotAllowedException());

        mockMvc.perform(post(ADMIN_NOTIFICATIONS_URL + "/{id}/requeue", notificationId)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("NOTIFICATION_REQUEUE_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());

        verify(notificationAdminCommandService).requeueFailedNotification(notificationId);
    }

    @Test
    void getNotificationActionLogs_shouldReturnForbiddenForAnonymous() throws Exception {
        UUID notificationId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        mockMvc.perform(get(ADMIN_NOTIFICATIONS_URL + "/{id}/actions", notificationId))
                .andExpect(status().isForbidden());

        verifyNoInteractions(notificationAdminActionLogQueryService);
    }

    @Test
    void getNotificationActionLogs_shouldReturnForbiddenForUserWithoutAdminRole() throws Exception {
        UUID notificationId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        mockMvc.perform(get(ADMIN_NOTIFICATIONS_URL + "/{id}/actions", notificationId)
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(notificationAdminActionLogQueryService);
    }

    @Test
    void getNotificationActionLogs_shouldReturnActionLogsForAdminAndPassPageable() throws Exception {
        UUID notificationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID actionLogId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        NotificationAdminActionLogResponseDTO response = new NotificationAdminActionLogResponseDTO(
                actionLogId,
                notificationId,
                NotificationAdminActionType.REQUEUE,
                "admin@example.com",
                Instant.parse("2026-01-01T10:00:00Z"),
                "Requeued notification");
        when(notificationAdminActionLogQueryService.getNotificationActionLogs(eq(notificationId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(1, 5), 1));

        mockMvc.perform(get(ADMIN_NOTIFICATIONS_URL + "/{id}/actions", notificationId)
                        .with(user("admin").roles("ADMIN"))
                        .param("page", "1")
                        .param("size", "5")
                        .param("sort", "createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value("33333333-3333-3333-3333-333333333333"))
                .andExpect(jsonPath("$.content[0].notificationId").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.content[0].actionType").value("REQUEUE"))
                .andExpect(jsonPath("$.content[0].actorEmail").value("admin@example.com"))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.content[0].details").value("Requeued notification"));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationAdminActionLogQueryService).getNotificationActionLogs(eq(notificationId), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(5);
        assertThat(pageable.getSort().getOrderFor("createdAt").getDirection().name()).isEqualTo("DESC");
    }

    @Test
    void getNotificationActionLogs_shouldReturnNotFoundWhenMissing() throws Exception {
        UUID notificationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(notificationAdminActionLogQueryService.getNotificationActionLogs(eq(notificationId), any(Pageable.class)))
                .thenThrow(new NotificationNotFoundException(notificationId));

        mockMvc.perform(get(ADMIN_NOTIFICATIONS_URL + "/{id}/actions", notificationId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.errorCode").value("NOTIFICATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());

        verify(notificationAdminActionLogQueryService).getNotificationActionLogs(eq(notificationId), any(Pageable.class));
    }

    @Test
    void getNotification_shouldReturnNotificationForAdmin() throws Exception {
        UUID notificationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        NotificationResponseDTO response = responseWithLastAttemptAt(
                notificationId,
                UUID.fromString("22222222-2222-2222-2222-222222222222"));
        when(notificationQueryService.getNotification(notificationId)).thenReturn(response);

        mockMvc.perform(get(ADMIN_NOTIFICATIONS_URL + "/{id}", notificationId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.lastRequeuedBy").value("admin@example.com"))
                .andExpect(jsonPath("$.lastAttemptAt").value("2026-01-01T10:05:00Z"));

        verify(notificationQueryService).getNotification(notificationId);
        verifyNoMoreInteractions(notificationQueryService);
    }

    @Test
    void getNotification_shouldReturnNotFoundWhenMissing() throws Exception {
        UUID notificationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(notificationQueryService.getNotification(notificationId)).thenThrow(new NotificationNotFoundException(notificationId));

        mockMvc.perform(get(ADMIN_NOTIFICATIONS_URL + "/{id}", notificationId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.errorCode").value("NOTIFICATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());

        verify(notificationQueryService).getNotification(notificationId);
    }

    private NotificationResponseDTO response(UUID notificationId, UUID sourceEventId) {
        return response(notificationId, sourceEventId, NotificationStatus.PENDING);
    }

    private NotificationResponseDTO responseWithLastAttemptAt(UUID notificationId, UUID sourceEventId) {
        return response(notificationId, sourceEventId, NotificationStatus.PENDING, LAST_ATTEMPT_AT, 1, LAST_REQUEUED_AT);
    }

    private NotificationResponseDTO response(UUID notificationId, UUID sourceEventId, NotificationStatus status) {
        return response(notificationId, sourceEventId, status, null, 0, null);
    }

    private NotificationResponseDTO response(
            UUID notificationId,
            UUID sourceEventId,
            NotificationStatus status,
            Instant lastAttemptAt,
            int requeueCount,
            Instant lastRequeuedAt) {
        return new NotificationResponseDTO(
                notificationId,
                "ORDER_PLACED_EMAIL",
                "customer@example.com",
                "Order placed",
                "Your order has been placed.",
                status,
                sourceEventId,
                Instant.parse("2026-01-01T10:00:00Z"),
                null,
                0,
                requeueCount,
                lastRequeuedAt,
                lastRequeuedAt == null ? null : "admin@example.com",
                null,
                lastAttemptAt,
                null);
    }
}
