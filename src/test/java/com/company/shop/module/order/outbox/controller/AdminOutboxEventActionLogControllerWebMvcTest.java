package com.company.shop.module.order.outbox.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.company.shop.module.order.outbox.OutboxEventAdminActionLogQueryService;
import com.company.shop.module.order.outbox.OutboxEventAdminActionType;
import com.company.shop.module.order.outbox.dto.OutboxEventAdminActionLogResponseDTO;
import com.company.shop.module.order.outbox.exception.OutboxEventActionLogDateRangeInvalidException;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.company.shop.support.WebMvcSliceTestConfig;

@WebMvcTest(controllers = AdminOutboxEventActionLogController.class)
@ActiveProfiles("test")
@Import(WebMvcSliceTestConfig.class)
class AdminOutboxEventActionLogControllerWebMvcTest {

    private static final String ADMIN_OUTBOX_EVENT_ACTIONS_URL = "/api/v1/admin/outbox-event-actions";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OutboxEventAdminActionLogQueryService outboxEventAdminActionLogQueryService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.validate(anyString())).thenReturn(false);
    }

    @Test
    void searchActionLogs_shouldReturnForbiddenForAnonymous() throws Exception {
        mockMvc.perform(get(ADMIN_OUTBOX_EVENT_ACTIONS_URL))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventAdminActionLogQueryService);
    }

    @Test
    void searchActionLogs_shouldReturnForbiddenForUserWithoutAdminRole() throws Exception {
        mockMvc.perform(get(ADMIN_OUTBOX_EVENT_ACTIONS_URL)
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventAdminActionLogQueryService);
    }

    @Test
    void searchActionLogs_shouldReturnActionLogsForAdmin() throws Exception {
        UUID outboxEventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID actionLogId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        OutboxEventAdminActionLogResponseDTO response = new OutboxEventAdminActionLogResponseDTO(
                actionLogId,
                outboxEventId,
                OutboxEventAdminActionType.REQUEUE,
                "admin@example.com",
                Instant.parse("2026-01-01T10:00:00Z"),
                "Requeued outbox event");
        when(outboxEventAdminActionLogQueryService.searchActionLogs(
                eq(null), eq(null), eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1));

        mockMvc.perform(get(ADMIN_OUTBOX_EVENT_ACTIONS_URL)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value("33333333-3333-3333-3333-333333333333"))
                .andExpect(jsonPath("$.content[0].outboxEventId").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.content[0].actionType").value("REQUEUE"))
                .andExpect(jsonPath("$.content[0].actorEmail").value("admin@example.com"))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.content[0].details").value("Requeued outbox event"))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.numberOfElements").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.empty").value(false));
    }

    @Test
    void searchActionLogs_shouldPassFiltersAndPageableToService() throws Exception {
        UUID outboxEventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(outboxEventAdminActionLogQueryService.searchActionLogs(
                any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5), 0));

        mockMvc.perform(get(ADMIN_OUTBOX_EVENT_ACTIONS_URL)
                        .with(user("admin").roles("ADMIN"))
                        .param("outboxEventId", outboxEventId.toString())
                        .param("actionType", "REQUEUE")
                        .param("actorEmail", "admin")
                        .param("createdFrom", "2026-01-01T00:00:00Z")
                        .param("createdTo", "2026-01-31T23:59:59Z")
                        .param("page", "2")
                        .param("size", "5")
                        .param("sort", "actorEmail,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.number").value(2))
                .andExpect(jsonPath("$.size").value(5));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(outboxEventAdminActionLogQueryService).searchActionLogs(
                eq(outboxEventId),
                eq(OutboxEventAdminActionType.REQUEUE),
                eq("admin"),
                eq(Instant.parse("2026-01-01T00:00:00Z")),
                eq(Instant.parse("2026-01-31T23:59:59Z")),
                pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(5);
        assertThat(pageable.getSort().getOrderFor("actorEmail").getDirection().name()).isEqualTo("ASC");
    }

    @Test
    void searchActionLogs_shouldReturnBadRequestWhenCreatedFromIsAfterCreatedTo() throws Exception {
        when(outboxEventAdminActionLogQueryService.searchActionLogs(
                eq(null),
                eq(null),
                eq(null),
                eq(Instant.parse("2026-02-01T00:00:00Z")),
                eq(Instant.parse("2026-01-01T00:00:00Z")),
                any(Pageable.class)))
                .thenThrow(new OutboxEventActionLogDateRangeInvalidException());

        mockMvc.perform(get(ADMIN_OUTBOX_EVENT_ACTIONS_URL)
                        .with(user("admin").roles("ADMIN"))
                        .param("createdFrom", "2026-02-01T00:00:00Z")
                        .param("createdTo", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("OUTBOX_EVENT_ACTION_LOG_DATE_RANGE_INVALID"))
                .andExpect(jsonPath("$.message").value("createdFrom must be before or equal to createdTo."))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(outboxEventAdminActionLogQueryService).searchActionLogs(
                eq(null),
                eq(null),
                eq(null),
                eq(Instant.parse("2026-02-01T00:00:00Z")),
                eq(Instant.parse("2026-01-01T00:00:00Z")),
                any(Pageable.class));
    }
}
