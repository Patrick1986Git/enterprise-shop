package com.company.shop.module.order.outbox.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
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
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.module.order.outbox.OutboxEventAdminCommandService;
import com.company.shop.module.order.outbox.OutboxEventQueryService;
import com.company.shop.module.order.outbox.OutboxEventStatus;
import com.company.shop.module.order.outbox.dto.OutboxEventDetailResponseDTO;
import com.company.shop.module.order.outbox.dto.OutboxEventResponseDTO;
import com.company.shop.module.order.outbox.dto.OutboxEventSummaryDTO;
import com.company.shop.module.order.outbox.exception.OutboxEventDateRangeInvalidException;
import com.company.shop.module.order.outbox.exception.OutboxEventNotFoundException;
import com.company.shop.module.order.outbox.exception.OutboxEventRequeueNotAllowedException;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.company.shop.support.WebMvcSliceTestConfig;

@WebMvcTest(controllers = AdminOutboxEventController.class)
@ActiveProfiles("test")
@Import(WebMvcSliceTestConfig.class)
class AdminOutboxEventControllerWebMvcTest {

    private static final String ADMIN_OUTBOX_EVENTS_URL = "/api/v1/admin/outbox-events";
    private static final String ADMIN_OUTBOX_EVENTS_SUMMARY_URL = "/api/v1/admin/outbox-events/summary";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OutboxEventQueryService outboxEventQueryService;

    @MockitoBean
    private OutboxEventAdminCommandService outboxEventAdminCommandService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.validate(anyString())).thenReturn(false);
    }

    @Test
    void getEvents_shouldReturnForbiddenForAnonymous() throws Exception {
        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventQueryService, outboxEventAdminCommandService);
    }

    @Test
    void getEvents_shouldReturnForbiddenForUserWithoutAdminRole() throws Exception {
        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL)
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventQueryService, outboxEventAdminCommandService);
    }

    @Test
    void getEvents_shouldReturnPagedEventsForAdminWithoutPayload() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        OutboxEventResponseDTO response = new OutboxEventResponseDTO(
                eventId,
                "Order",
                aggregateId,
                "OrderPlaced",
                OutboxEventStatus.FAILED,
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:01:00Z"),
                2,
                "boom");
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxEventQueryService.getEvents(null, null, null, null, null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(response), pageable, 1));

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.content[0].id").value(eventId.toString()))
                .andExpect(jsonPath("$.content[0].aggregateType").value("Order"))
                .andExpect(jsonPath("$.content[0].aggregateId").value(aggregateId.toString()))
                .andExpect(jsonPath("$.content[0].eventType").value("OrderPlaced"))
                .andExpect(jsonPath("$.content[0].status").value("FAILED"))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.content[0].processedAt").value("2026-01-01T10:01:00Z"))
                .andExpect(jsonPath("$.content[0].attempts").value(2))
                .andExpect(jsonPath("$.content[0].lastError").value("boom"))
                .andExpect(jsonPath("$.content[0].payload").doesNotExist())
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.numberOfElements").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.empty").value(false));

        verify(outboxEventQueryService).getEvents(null, null, null, null, null, null, pageable);
        verifyNoMoreInteractions(outboxEventQueryService);
    }

    @Test
    void getEvents_shouldReturnBadRequestWhenCreatedFromIsAfterCreatedTo() throws Exception {
        Instant createdFrom = Instant.parse("2026-02-01T00:00:00Z");
        Instant createdTo = Instant.parse("2026-01-01T00:00:00Z");
        when(outboxEventQueryService.getEvents(
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(createdFrom),
                eq(createdTo),
                any(Pageable.class)))
                .thenThrow(new OutboxEventDateRangeInvalidException());

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL)
                        .param("createdFrom", createdFrom.toString())
                        .param("createdTo", createdTo.toString())
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("OUTBOX_EVENT_DATE_RANGE_INVALID"))
                .andExpect(jsonPath("$.message").value("createdFrom must be before or equal to createdTo."))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(outboxEventQueryService).getEvents(
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(createdFrom),
                eq(createdTo),
                any(Pageable.class));
    }

    @Test
    void getEvents_shouldPassFiltersPageableAndSortToService() throws Exception {
        UUID aggregateId = UUID.randomUUID();
        Instant createdFrom = Instant.parse("2026-06-01T00:00:00Z");
        Instant createdTo = Instant.parse("2026-06-30T23:59:59Z");
        when(outboxEventQueryService.getEvents(
                org.mockito.Mockito.eq(OutboxEventStatus.PENDING),
                org.mockito.Mockito.eq("Order"),
                org.mockito.Mockito.eq(aggregateId),
                org.mockito.Mockito.eq("Placed"),
                org.mockito.Mockito.eq(createdFrom),
                org.mockito.Mockito.eq(createdTo),
                org.mockito.Mockito.any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5, Sort.by(Sort.Direction.ASC, "eventType")), 0));

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL)
                        .param("status", "PENDING")
                        .param("aggregateType", "Order")
                        .param("aggregateId", aggregateId.toString())
                        .param("eventType", "Placed")
                        .param("createdFrom", createdFrom.toString())
                        .param("createdTo", createdTo.toString())
                        .param("page", "2")
                        .param("size", "5")
                        .param("sort", "eventType,asc")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(outboxEventQueryService).getEvents(
                org.mockito.Mockito.eq(OutboxEventStatus.PENDING),
                org.mockito.Mockito.eq("Order"),
                org.mockito.Mockito.eq(aggregateId),
                org.mockito.Mockito.eq("Placed"),
                org.mockito.Mockito.eq(createdFrom),
                org.mockito.Mockito.eq(createdTo),
                pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(5);
        assertThat(pageableCaptor.getValue().getSort()).containsExactly(Sort.Order.asc("eventType"));
    }

    @Test
    void getEvent_shouldReturnDetailForAdmin() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        OutboxEventDetailResponseDTO response = new OutboxEventDetailResponseDTO(
                eventId,
                "Order",
                aggregateId,
                "OrderPlaced",
                "{\"orderId\":\"" + aggregateId + "\"}",
                OutboxEventStatus.FAILED,
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:01:00Z"),
                2,
                "boom");
        when(outboxEventQueryService.getEvent(eventId)).thenReturn(response);

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL + "/{id}", eventId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(eventId.toString()))
                .andExpect(jsonPath("$.aggregateType").value("Order"))
                .andExpect(jsonPath("$.aggregateId").value(aggregateId.toString()))
                .andExpect(jsonPath("$.eventType").value("OrderPlaced"))
                .andExpect(jsonPath("$.payload").value("{\"orderId\":\"" + aggregateId + "\"}"))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.processedAt").value("2026-01-01T10:01:00Z"))
                .andExpect(jsonPath("$.attempts").value(2))
                .andExpect(jsonPath("$.lastError").value("boom"));

        verify(outboxEventQueryService).getEvent(eventId);
        verifyNoMoreInteractions(outboxEventQueryService);
    }

    @Test
    void getEvent_shouldReturnForbiddenForUserWithoutAdminRole() throws Exception {
        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL + "/{id}", UUID.randomUUID())
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventQueryService, outboxEventAdminCommandService);
    }

    @Test
    void getEvent_shouldReturnForbiddenForAnonymous() throws Exception {
        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL + "/{id}", UUID.randomUUID()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventQueryService, outboxEventAdminCommandService);
    }

    @Test
    void getEvent_shouldReturnNotFoundWhenEventIsMissing() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(outboxEventQueryService.getEvent(eventId)).thenThrow(new OutboxEventNotFoundException(eventId));

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL + "/{id}", eventId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.errorCode").value("OUTBOX_EVENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Outbox event not found: " + eventId))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(outboxEventQueryService).getEvent(eventId);
    }


    @Test
    void requeueEvent_shouldReturnRequeuedEventForAdminWithoutPayload() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        OutboxEventResponseDTO response = new OutboxEventResponseDTO(
                eventId,
                "Order",
                aggregateId,
                "OrderPlaced",
                OutboxEventStatus.PENDING,
                Instant.parse("2026-01-01T10:00:00Z"),
                null,
                3,
                null);
        when(outboxEventAdminCommandService.requeueFailedEvent(eventId)).thenReturn(response);

        mockMvc.perform(post(ADMIN_OUTBOX_EVENTS_URL + "/{id}/requeue", eventId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(eventId.toString()))
                .andExpect(jsonPath("$.aggregateType").value("Order"))
                .andExpect(jsonPath("$.aggregateId").value(aggregateId.toString()))
                .andExpect(jsonPath("$.eventType").value("OrderPlaced"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.processedAt").value(nullValue()))
                .andExpect(jsonPath("$.attempts").value(3))
                .andExpect(jsonPath("$.lastError").value(nullValue()))
                .andExpect(jsonPath("$.payload").doesNotExist());

        verify(outboxEventAdminCommandService).requeueFailedEvent(eventId);
        verifyNoMoreInteractions(outboxEventAdminCommandService);
        verifyNoInteractions(outboxEventQueryService);
    }

    @Test
    void requeueEvent_shouldReturnForbiddenForUserWithoutAdminRole() throws Exception {
        mockMvc.perform(post(ADMIN_OUTBOX_EVENTS_URL + "/{id}/requeue", UUID.randomUUID())
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventQueryService, outboxEventAdminCommandService);
    }

    @Test
    void requeueEvent_shouldReturnForbiddenForAnonymous() throws Exception {
        mockMvc.perform(post(ADMIN_OUTBOX_EVENTS_URL + "/{id}/requeue", UUID.randomUUID()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventQueryService, outboxEventAdminCommandService);
    }

    @Test
    void requeueEvent_shouldReturnNotFoundWhenEventIsMissing() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(outboxEventAdminCommandService.requeueFailedEvent(eventId)).thenThrow(new OutboxEventNotFoundException(eventId));

        mockMvc.perform(post(ADMIN_OUTBOX_EVENTS_URL + "/{id}/requeue", eventId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.errorCode").value("OUTBOX_EVENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Outbox event not found: " + eventId))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(outboxEventAdminCommandService).requeueFailedEvent(eventId);
    }

    @Test
    void requeueEvent_shouldReturnConflictWhenStatusCannotBeRequeued() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(outboxEventAdminCommandService.requeueFailedEvent(eventId))
                .thenThrow(new OutboxEventRequeueNotAllowedException());

        mockMvc.perform(post(ADMIN_OUTBOX_EVENTS_URL + "/{id}/requeue", eventId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("OUTBOX_EVENT_REQUEUE_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("Outbox event can be requeued only when it is FAILED."))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(outboxEventAdminCommandService).requeueFailedEvent(eventId);
    }

    @Test
    void getSummary_shouldReturnForbiddenForAnonymous() throws Exception {
        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_SUMMARY_URL))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventQueryService, outboxEventAdminCommandService);
    }

    @Test
    void getSummary_shouldReturnForbiddenForUserWithoutAdminRole() throws Exception {
        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_SUMMARY_URL)
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventQueryService, outboxEventAdminCommandService);
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
