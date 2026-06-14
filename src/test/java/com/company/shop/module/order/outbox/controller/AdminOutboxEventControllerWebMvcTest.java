package com.company.shop.module.order.outbox.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.module.order.outbox.OutboxEventQueryService;
import com.company.shop.module.order.outbox.dto.OutboxEventSummaryDTO;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.company.shop.support.WebMvcSliceTestConfig;

@WebMvcTest(controllers = AdminOutboxEventController.class)
@ActiveProfiles("test")
@Import(WebMvcSliceTestConfig.class)
class AdminOutboxEventControllerWebMvcTest {

    private static final String ADMIN_OUTBOX_EVENTS_SUMMARY_URL = "/api/v1/admin/outbox-events/summary";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OutboxEventQueryService outboxEventQueryService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.validate(anyString())).thenReturn(false);
    }

    @Test
    void getSummary_shouldReturnForbiddenForAnonymous() throws Exception {
        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_SUMMARY_URL))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventQueryService);
    }

    @Test
    void getSummary_shouldReturnForbiddenForUserWithoutAdminRole() throws Exception {
        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_SUMMARY_URL)
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventQueryService);
    }

    @Test
    void getSummary_shouldReturnSummaryForAdmin() throws Exception {
        when(outboxEventQueryService.getSummary()).thenReturn(new OutboxEventSummaryDTO(
                2L,
                3L,
                1L,
                6L,
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T11:00:00Z")));

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_SUMMARY_URL)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.pendingCount").value(2))
                .andExpect(jsonPath("$.processedCount").value(3))
                .andExpect(jsonPath("$.failedCount").value(1))
                .andExpect(jsonPath("$.totalCount").value(6))
                .andExpect(jsonPath("$.oldestPendingCreatedAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.newestFailedCreatedAt").value("2026-01-01T11:00:00Z"));

        verify(outboxEventQueryService).getSummary();
        verifyNoMoreInteractions(outboxEventQueryService);
    }
}
