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
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.module.order.outbox.OutboxEventAdminActionLogQueryService;
import com.company.shop.module.order.outbox.OutboxEventAdminActionType;
import com.company.shop.module.order.outbox.OutboxEventAdminCommandService;
import com.company.shop.module.order.outbox.OutboxEventAdminSearchCriteria;
import com.company.shop.module.order.outbox.OutboxEventProblemType;
import com.company.shop.module.order.outbox.OutboxEventQueryService;
import com.company.shop.module.order.outbox.OutboxEventStatus;
import com.company.shop.module.order.outbox.dto.OutboxEventAdminActionLogResponseDTO;
import com.company.shop.module.order.outbox.dto.OutboxEventDetailResponseDTO;
import com.company.shop.module.order.outbox.dto.OutboxEventResponseDTO;
import com.company.shop.module.order.outbox.dto.OutboxEventSummaryDTO;
import com.company.shop.module.order.outbox.exception.OutboxEventAttemptsRangeInvalidException;
import com.company.shop.module.order.outbox.exception.OutboxEventDateRangeInvalidException;
import com.company.shop.module.order.outbox.exception.OutboxEventLastAttemptDateRangeInvalidException;
import com.company.shop.module.order.outbox.exception.OutboxEventNextAttemptDateRangeInvalidException;
import com.company.shop.module.order.outbox.exception.OutboxEventNotFoundException;
import com.company.shop.module.order.outbox.exception.OutboxEventProcessedDateRangeInvalidException;
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
    void getEvents_shouldReturnForbiddenForAnonymous() throws Exception {
        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventQueryService, outboxEventAdminCommandService, outboxEventAdminActionLogQueryService);
    }

    @Test
    void getEvents_shouldReturnForbiddenForUserWithoutAdminRole() throws Exception {
        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL)
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventQueryService, outboxEventAdminCommandService, outboxEventAdminActionLogQueryService);
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
                Instant.parse("2026-01-01T10:01:30Z"),
                Instant.parse("2026-01-01T10:06:30Z"),
                2,
                "boom",
                1,
                Instant.parse("2026-01-01T10:02:00Z"),
                "admin@example.com");
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxEventQueryService.getEvents(eq(emptyCriteria()), eq(pageable)))
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
                .andExpect(jsonPath("$.content[0].lastAttemptAt").value("2026-01-01T10:01:30Z"))
                .andExpect(jsonPath("$.content[0].nextAttemptAt").value("2026-01-01T10:06:30Z"))
                .andExpect(jsonPath("$.content[0].attempts").value(2))
                .andExpect(jsonPath("$.content[0].lastError").value("boom"))
                .andExpect(jsonPath("$.content[0].requeueCount").value(1))
                .andExpect(jsonPath("$.content[0].lastRequeuedAt").value("2026-01-01T10:02:00Z"))
                .andExpect(jsonPath("$.content[0].lastRequeuedBy").value("admin@example.com"))
                .andExpect(jsonPath("$.content[0].payload").doesNotExist())
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.numberOfElements").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.empty").value(false));

        verify(outboxEventQueryService).getEvents(eq(emptyCriteria()), eq(pageable));
        verifyNoMoreInteractions(outboxEventQueryService);
    }


    @Test
    void getEvents_shouldPassRequeuedOnlyFalseToService() throws Exception {
        when(outboxEventQueryService.getEvents(eq(criteriaWithRequeuedOnly(Boolean.FALSE)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL)
                 .param("requeuedOnly", "false")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(outboxEventQueryService).getEvents(eq(criteriaWithRequeuedOnly(Boolean.FALSE)), any(Pageable.class));
    }

    @Test
    void getEvents_shouldReturnBadRequestWhenCreatedFromIsAfterCreatedTo() throws Exception {
        Instant createdFrom = Instant.parse("2026-02-01T00:00:00Z");
        Instant createdTo = Instant.parse("2026-01-01T00:00:00Z");
        when(outboxEventQueryService.getEvents(eq(criteriaWithCreatedRange(createdFrom, createdTo)), any(Pageable.class)))
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

        verify(outboxEventQueryService).getEvents(eq(criteriaWithCreatedRange(createdFrom, createdTo)), any(Pageable.class));
    }

    @Test
    void getEvents_shouldPassProcessedFiltersToService() throws Exception {
        Instant processedFrom = Instant.parse("2026-06-21T00:00:00Z");
        Instant processedTo = Instant.parse("2026-06-21T23:59:59Z");
        when(outboxEventQueryService.getEvents(eq(criteriaWithProcessedRange(processedFrom, processedTo)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL)
                 .param("processedFrom", processedFrom.toString())
                 .param("processedTo", processedTo.toString())
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(outboxEventQueryService).getEvents(eq(criteriaWithProcessedRange(processedFrom, processedTo)), any(Pageable.class));
    }

    @Test
    void getEvents_shouldReturnBadRequestWhenProcessedFromIsAfterProcessedTo() throws Exception {
        Instant processedFrom = Instant.parse("2026-06-22T00:00:00Z");
        Instant processedTo = Instant.parse("2026-06-21T00:00:00Z");
        when(outboxEventQueryService.getEvents(eq(criteriaWithProcessedRange(processedFrom, processedTo)), any(Pageable.class)))
                .thenThrow(new OutboxEventProcessedDateRangeInvalidException());

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL)
                 .param("processedFrom", processedFrom.toString())
                 .param("processedTo", processedTo.toString())
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("OUTBOX_EVENT_PROCESSED_DATE_RANGE_INVALID"))
                .andExpect(jsonPath("$.message").value("processedFrom must be before or equal to processedTo."))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(outboxEventQueryService).getEvents(eq(criteriaWithProcessedRange(processedFrom, processedTo)), any(Pageable.class));
    }

    @Test
    void getEvents_shouldPassLastAttemptFiltersToService() throws Exception {
        Instant lastAttemptFrom = Instant.parse("2026-06-01T00:00:00Z");
        Instant lastAttemptTo = Instant.parse("2026-06-30T23:59:59Z");
        when(outboxEventQueryService.getEvents(eq(criteriaWithLastAttemptRange(lastAttemptFrom, lastAttemptTo)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL)
                 .param("lastAttemptFrom", lastAttemptFrom.toString())
                 .param("lastAttemptTo", lastAttemptTo.toString())
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(outboxEventQueryService).getEvents(eq(criteriaWithLastAttemptRange(lastAttemptFrom, lastAttemptTo)), any(Pageable.class));
    }

    @Test
    void getEvents_shouldReturnBadRequestWhenLastAttemptFromIsAfterLastAttemptTo() throws Exception {
        Instant lastAttemptFrom = Instant.parse("2026-07-01T00:00:00Z");
        Instant lastAttemptTo = Instant.parse("2026-06-01T00:00:00Z");
        when(outboxEventQueryService.getEvents(eq(criteriaWithLastAttemptRange(lastAttemptFrom, lastAttemptTo)), any(Pageable.class)))
                .thenThrow(new OutboxEventLastAttemptDateRangeInvalidException());

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL)
                 .param("lastAttemptFrom", lastAttemptFrom.toString())
                 .param("lastAttemptTo", lastAttemptTo.toString())
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("OUTBOX_EVENT_LAST_ATTEMPT_DATE_RANGE_INVALID"))
                .andExpect(jsonPath("$.message").value("lastAttemptFrom must be before or equal to lastAttemptTo."))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(outboxEventQueryService).getEvents(eq(criteriaWithLastAttemptRange(lastAttemptFrom, lastAttemptTo)), any(Pageable.class));
    }


    @Test
    void getEvents_shouldPassNextAttemptFiltersToService() throws Exception {
        Instant nextAttemptFrom = Instant.parse("2026-06-01T00:00:00Z");
        Instant nextAttemptTo = Instant.parse("2026-06-30T23:59:59Z");
        when(outboxEventQueryService.getEvents(eq(criteriaWithNextAttemptRange(nextAttemptFrom, nextAttemptTo)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL)
                 .param("nextAttemptFrom", nextAttemptFrom.toString())
                 .param("nextAttemptTo", nextAttemptTo.toString())
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(outboxEventQueryService).getEvents(eq(criteriaWithNextAttemptRange(nextAttemptFrom, nextAttemptTo)), any(Pageable.class));
    }

    @Test
    void getEvents_shouldReturnBadRequestWhenNextAttemptFromIsAfterNextAttemptTo() throws Exception {
        Instant nextAttemptFrom = Instant.parse("2026-07-01T00:00:00Z");
        Instant nextAttemptTo = Instant.parse("2026-06-01T00:00:00Z");
        when(outboxEventQueryService.getEvents(eq(criteriaWithNextAttemptRange(nextAttemptFrom, nextAttemptTo)), any(Pageable.class)))
                .thenThrow(new OutboxEventNextAttemptDateRangeInvalidException());

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL)
                 .param("nextAttemptFrom", nextAttemptFrom.toString())
                 .param("nextAttemptTo", nextAttemptTo.toString())
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("OUTBOX_EVENT_NEXT_ATTEMPT_DATE_RANGE_INVALID"))
                .andExpect(jsonPath("$.message").value("nextAttemptFrom must be before or equal to nextAttemptTo."))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(outboxEventQueryService).getEvents(eq(criteriaWithNextAttemptRange(nextAttemptFrom, nextAttemptTo)), any(Pageable.class));
    }


    @Test
    void getEvents_shouldPassProblemTypeToService() throws Exception {
        when(outboxEventQueryService.getEvents(eq(criteriaWithProblemType(OutboxEventProblemType.STALE_PENDING)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL)
                 .param("problemType", "STALE_PENDING")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(outboxEventQueryService).getEvents(eq(criteriaWithProblemType(OutboxEventProblemType.STALE_PENDING)), any(Pageable.class));
    }

    @Test
    void getEvents_shouldPassAttemptsFiltersToService() throws Exception {
        when(outboxEventQueryService.getEvents(eq(criteriaWithAttempts(3, 7)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL)
                 .param("attemptsMin", "3")
                 .param("attemptsMax", "7")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(outboxEventQueryService).getEvents(eq(criteriaWithAttempts(3, 7)), any(Pageable.class));
    }

    @Test
    void getEvents_shouldPassLastErrorContainsToService() throws Exception {
        when(outboxEventQueryService.getEvents(eq(criteriaWithLastErrorContains("timeout")), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL)
                 .param("lastErrorContains", "timeout")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(outboxEventQueryService).getEvents(eq(criteriaWithLastErrorContains("timeout")), any(Pageable.class));
    }

    @Test
    void getEvents_shouldReturnBadRequestWhenAttemptsRangeIsInvalid() throws Exception {
        when(outboxEventQueryService.getEvents(eq(criteriaWithAttempts(5, 3)), any(Pageable.class)))
                .thenThrow(new OutboxEventAttemptsRangeInvalidException());

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL)
                 .param("attemptsMin", "5")
                 .param("attemptsMax", "3")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("OUTBOX_EVENT_ATTEMPTS_RANGE_INVALID"))
                .andExpect(jsonPath("$.message").value("attemptsMin and attemptsMax must be non-negative and attemptsMin must be less than or equal to attemptsMax."))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(outboxEventQueryService).getEvents(eq(criteriaWithAttempts(5, 3)), any(Pageable.class));
    }

    @Test
    void getEvents_shouldPassFiltersPageableAndSortToService() throws Exception {
        UUID aggregateId = UUID.randomUUID();
        Instant createdFrom = Instant.parse("2026-06-01T00:00:00Z");
        Instant createdTo = Instant.parse("2026-06-30T23:59:59Z");
        Instant processedFrom = Instant.parse("2026-06-21T00:00:00Z");
        Instant processedTo = Instant.parse("2026-06-21T23:59:59Z");
        when(outboxEventQueryService.getEvents(eq(criteriaWithAllFilters(aggregateId, createdFrom, createdTo, processedFrom, processedTo)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5, Sort.by(Sort.Direction.ASC, "eventType")), 0));

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL)
                 .param("status", "PENDING")
                 .param("aggregateType", "Order")
                 .param("aggregateId", aggregateId.toString())
                 .param("eventType", "Placed")
                 .param("lastErrorContains", "timeout")
                 .param("createdFrom", createdFrom.toString())
                 .param("createdTo", createdTo.toString())
                 .param("processedFrom", processedFrom.toString())
                 .param("processedTo", processedTo.toString())
                 .param("attemptsMin", "2")
                 .param("attemptsMax", "4")
                 .param("requeuedOnly", "true")
                 .param("problemType", "HIGH_ATTEMPT_FAILED")
                 .param("page", "2")
                 .param("size", "5")
                 .param("sort", "eventType,asc")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(outboxEventQueryService).getEvents(eq(criteriaWithAllFilters(aggregateId, createdFrom, createdTo, processedFrom, processedTo)), pageableCaptor.capture());
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
                Instant.parse("2026-01-01T10:01:30Z"),
                Instant.parse("2026-01-01T10:06:30Z"),
                2,
                "boom",
                null,
                1,
                Instant.parse("2026-01-01T10:02:00Z"),
                "admin@example.com");
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
                .andExpect(jsonPath("$.lastAttemptAt").value("2026-01-01T10:01:30Z"))
                .andExpect(jsonPath("$.nextAttemptAt").value("2026-01-01T10:06:30Z"))
                .andExpect(jsonPath("$.attempts").value(2))
                .andExpect(jsonPath("$.lastError").value("boom"))
                .andExpect(jsonPath("$.deadLetterReason").value(nullValue()))
                .andExpect(jsonPath("$.requeueCount").value(1))
                .andExpect(jsonPath("$.lastRequeuedAt").value("2026-01-01T10:02:00Z"))
                .andExpect(jsonPath("$.lastRequeuedBy").value("admin@example.com"));

        verify(outboxEventQueryService).getEvent(eventId);
        verifyNoMoreInteractions(outboxEventQueryService);
    }

    @Test
    void getEvent_shouldReturnForbiddenForUserWithoutAdminRole() throws Exception {
        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL + "/{id}", UUID.randomUUID())
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventQueryService, outboxEventAdminCommandService, outboxEventAdminActionLogQueryService);
    }

    @Test
    void getEvent_shouldReturnForbiddenForAnonymous() throws Exception {
        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL + "/{id}", UUID.randomUUID()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventQueryService, outboxEventAdminCommandService, outboxEventAdminActionLogQueryService);
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
    void getEventActionLogs_shouldReturnForbiddenForAnonymous() throws Exception {
        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL + "/{id}/actions", UUID.randomUUID()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventQueryService, outboxEventAdminCommandService, outboxEventAdminActionLogQueryService);
    }

    @Test
    void getEventActionLogs_shouldReturnForbiddenForUserWithoutAdminRole() throws Exception {
        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL + "/{id}/actions", UUID.randomUUID())
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventQueryService, outboxEventAdminCommandService, outboxEventAdminActionLogQueryService);
    }

    @Test
    void getEventActionLogs_shouldReturnActionLogsForAdminAndPassPageable() throws Exception {
        UUID outboxEventId = UUID.randomUUID();
        UUID logId = UUID.randomUUID();
        OutboxEventAdminActionLogResponseDTO response = new OutboxEventAdminActionLogResponseDTO(
                logId,
                outboxEventId,
                OutboxEventAdminActionType.REQUEUE,
                "admin@example.com",
                Instant.parse("2026-01-01T10:00:00Z"),
                "Requeued after failure");
        when(outboxEventAdminActionLogQueryService.getOutboxEventActionLogs(eq(outboxEventId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(response),
                        PageRequest.of(1, 5, Sort.by(Sort.Direction.ASC, "actorEmail")),
                        11));

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL + "/{id}/actions", outboxEventId)
                 .param("page", "1")
                 .param("size", "5")
                 .param("sort", "actorEmail,asc")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.content[0].id").value(logId.toString()))
                .andExpect(jsonPath("$.content[0].outboxEventId").value(outboxEventId.toString()))
                .andExpect(jsonPath("$.content[0].actionType").value("REQUEUE"))
                .andExpect(jsonPath("$.content[0].actorEmail").value("admin@example.com"))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.content[0].details").value("Requeued after failure"))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.numberOfElements").value(1))
                .andExpect(jsonPath("$.totalElements").value(11))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(false))
                .andExpect(jsonPath("$.empty").value(false));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(outboxEventAdminActionLogQueryService).getOutboxEventActionLogs(eq(outboxEventId), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(5);
        assertThat(pageableCaptor.getValue().getSort()).containsExactly(Sort.Order.asc("actorEmail"));
        verifyNoMoreInteractions(outboxEventAdminActionLogQueryService);
        verifyNoInteractions(outboxEventQueryService, outboxEventAdminCommandService);
    }

    @Test
    void getEventActionLogs_shouldReturnNotFoundWhenEventIsMissing() throws Exception {
        UUID outboxEventId = UUID.randomUUID();
        when(outboxEventAdminActionLogQueryService.getOutboxEventActionLogs(eq(outboxEventId), any(Pageable.class)))
                .thenThrow(new OutboxEventNotFoundException(outboxEventId));

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_URL + "/{id}/actions", outboxEventId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.errorCode").value("OUTBOX_EVENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Outbox event not found: " + outboxEventId))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(outboxEventAdminActionLogQueryService).getOutboxEventActionLogs(eq(outboxEventId), any(Pageable.class));
        verifyNoMoreInteractions(outboxEventAdminActionLogQueryService);
        verifyNoInteractions(outboxEventQueryService, outboxEventAdminCommandService);
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
                Instant.parse("2026-01-01T10:01:30Z"),
                null,
                3,
                null,
                2,
                Instant.parse("2026-01-01T10:03:00Z"),
                "admin@example.com");
        when(outboxEventAdminCommandService.requeueFailedEvent(eventId)).thenReturn(response);

        mockMvc.perform(post(ADMIN_OUTBOX_EVENTS_URL + "/{id}/requeue", eventId)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(eventId.toString()))
                .andExpect(jsonPath("$.aggregateType").value("Order"))
                .andExpect(jsonPath("$.aggregateId").value(aggregateId.toString()))
                .andExpect(jsonPath("$.eventType").value("OrderPlaced"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.processedAt").value(nullValue()))
                .andExpect(jsonPath("$.lastAttemptAt").value("2026-01-01T10:01:30Z"))
                .andExpect(jsonPath("$.attempts").value(3))
                .andExpect(jsonPath("$.lastError").value(nullValue()))
                .andExpect(jsonPath("$.requeueCount").value(2))
                .andExpect(jsonPath("$.lastRequeuedAt").value("2026-01-01T10:03:00Z"))
                .andExpect(jsonPath("$.lastRequeuedBy").value("admin@example.com"))
                .andExpect(jsonPath("$.payload").doesNotExist());

        verify(outboxEventAdminCommandService).requeueFailedEvent(eventId);
        verifyNoMoreInteractions(outboxEventAdminCommandService);
        verifyNoInteractions(outboxEventQueryService);
    }

    @Test
    void requeueEvent_shouldReturnForbiddenForUserWithoutAdminRole() throws Exception {
        mockMvc.perform(post(ADMIN_OUTBOX_EVENTS_URL + "/{id}/requeue", UUID.randomUUID())
                        .with(user("user").roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventQueryService, outboxEventAdminCommandService, outboxEventAdminActionLogQueryService);
    }

    @Test
    void requeueEvent_shouldReturnForbiddenForAnonymous() throws Exception {
        mockMvc.perform(post(ADMIN_OUTBOX_EVENTS_URL + "/{id}/requeue", UUID.randomUUID())
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventQueryService, outboxEventAdminCommandService, outboxEventAdminActionLogQueryService);
    }

    @Test
    void requeueEvent_shouldReturnNotFoundWhenEventIsMissing() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(outboxEventAdminCommandService.requeueFailedEvent(eventId)).thenThrow(new OutboxEventNotFoundException(eventId));

        mockMvc.perform(post(ADMIN_OUTBOX_EVENTS_URL + "/{id}/requeue", eventId)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
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
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("OUTBOX_EVENT_REQUEUE_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("Outbox event can be requeued only when it is FAILED or DEAD_LETTER."))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(outboxEventAdminCommandService).requeueFailedEvent(eventId);
    }

    @Test
    void getSummary_shouldReturnForbiddenForAnonymous() throws Exception {
        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_SUMMARY_URL))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventQueryService, outboxEventAdminCommandService, outboxEventAdminActionLogQueryService);
    }

    @Test
    void getSummary_shouldReturnForbiddenForUserWithoutAdminRole() throws Exception {
        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_SUMMARY_URL)
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxEventQueryService, outboxEventAdminCommandService, outboxEventAdminActionLogQueryService);
    }

    @Test
    void getSummary_shouldReturnSummaryForAdmin() throws Exception {
        when(outboxEventQueryService.getSummary()).thenReturn(new OutboxEventSummaryDTO(
                2L,
                3L,
                1L,
                1L,
                7L,
                2L,
                5L,
                4L,
                5L,
                6L,
                15L,
                3,
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T11:00:00Z"),
                Instant.parse("2026-01-01T12:00:00Z"),
                Instant.parse("2026-01-01T11:30:00Z"),
                Instant.parse("2026-01-01T12:00:00Z"),
                Instant.parse("2026-01-01T09:30:00Z"),
                Instant.parse("2026-01-01T12:30:00Z")));

        mockMvc.perform(get(ADMIN_OUTBOX_EVENTS_SUMMARY_URL)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.pendingCount").value(2))
                .andExpect(jsonPath("$.processedCount").value(3))
                .andExpect(jsonPath("$.failedCount").value(1))
                .andExpect(jsonPath("$.deadLetterCount").value(1))
                .andExpect(jsonPath("$.totalCount").value(7))
                .andExpect(jsonPath("$.requeuedEventCount").value(2))
                .andExpect(jsonPath("$.totalRequeueCount").value(5))
                .andExpect(jsonPath("$.stalePendingCount").value(4))
                .andExpect(jsonPath("$.staleFailedCount").value(5))
                .andExpect(jsonPath("$.highAttemptFailedCount").value(6))
                .andExpect(jsonPath("$.staleThresholdMinutes").value(15))
                .andExpect(jsonPath("$.highFailedAttemptsThreshold").value(3))
                .andExpect(jsonPath("$.oldestPendingCreatedAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.newestFailedCreatedAt").value("2026-01-01T11:00:00Z"))
                .andExpect(jsonPath("$.newestAttemptAt").value("2026-01-01T12:00:00Z"))
                .andExpect(jsonPath("$.newestProcessedAttemptAt").value("2026-01-01T11:30:00Z"))
                .andExpect(jsonPath("$.newestFailedAttemptAt").value("2026-01-01T12:00:00Z"))
                .andExpect(jsonPath("$.oldestDeadLetterCreatedAt").value("2026-01-01T09:30:00Z"))
                .andExpect(jsonPath("$.newestDeadLetterAttemptAt").value("2026-01-01T12:30:00Z"));

        verify(outboxEventQueryService).getSummary();
        verifyNoMoreInteractions(outboxEventQueryService);
    }

    private static OutboxEventAdminSearchCriteria emptyCriteria() {
        return OutboxEventAdminSearchCriteria.builder()
                .build();
    }

    private static OutboxEventAdminSearchCriteria criteriaWithCreatedRange(Instant createdFrom, Instant createdTo) {
        return OutboxEventAdminSearchCriteria.builder()
                .createdFrom(createdFrom)
                .createdTo(createdTo)
                .build();
    }

    private static OutboxEventAdminSearchCriteria criteriaWithProcessedRange(Instant processedFrom, Instant processedTo) {
        return OutboxEventAdminSearchCriteria.builder()
                .processedFrom(processedFrom)
                .processedTo(processedTo)
                .build();
    }

    private static OutboxEventAdminSearchCriteria criteriaWithLastAttemptRange(
            Instant lastAttemptFrom,
            Instant lastAttemptTo) {
        return OutboxEventAdminSearchCriteria.builder()
                .lastAttemptFrom(lastAttemptFrom)
                .lastAttemptTo(lastAttemptTo)
                .build();
    }

    private static OutboxEventAdminSearchCriteria criteriaWithNextAttemptRange(
            Instant nextAttemptFrom,
            Instant nextAttemptTo) {
        return OutboxEventAdminSearchCriteria.builder()
                .nextAttemptFrom(nextAttemptFrom)
                .nextAttemptTo(nextAttemptTo)
                .build();
    }

    private static OutboxEventAdminSearchCriteria criteriaWithAttempts(Integer attemptsMin, Integer attemptsMax) {
        return OutboxEventAdminSearchCriteria.builder()
                .attemptsMin(attemptsMin)
                .attemptsMax(attemptsMax)
                .build();
    }

    private static OutboxEventAdminSearchCriteria criteriaWithRequeuedOnly(Boolean requeuedOnly) {
        return OutboxEventAdminSearchCriteria.builder()
                .requeuedOnly(requeuedOnly)
                .build();
    }

    private static OutboxEventAdminSearchCriteria criteriaWithLastErrorContains(String lastErrorContains) {
        return OutboxEventAdminSearchCriteria.builder()
                .lastErrorContains(lastErrorContains)
                .build();
    }

    private static OutboxEventAdminSearchCriteria criteriaWithProblemType(OutboxEventProblemType problemType) {
        return OutboxEventAdminSearchCriteria.builder()
                .problemType(problemType)
                .build();
    }

    private static OutboxEventAdminSearchCriteria criteriaWithAllFilters(
            UUID aggregateId,
            Instant createdFrom,
            Instant createdTo,
            Instant processedFrom,
            Instant processedTo) {
        return OutboxEventAdminSearchCriteria.builder()
                .status(OutboxEventStatus.PENDING)
                .aggregateType("Order")
                .aggregateId(aggregateId)
                .eventType("Placed")
                .lastErrorContains("timeout")
                .createdFrom(createdFrom)
                .createdTo(createdTo)
                .processedFrom(processedFrom)
                .processedTo(processedTo)
                .attemptsMin(2)
                .attemptsMax(4)
                .requeuedOnly(Boolean.TRUE)
                .problemType(OutboxEventProblemType.HIGH_ATTEMPT_FAILED)
                .build();
    }

}
