package com.company.shop.module.order.outbox.controller;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.common.dto.PageResponseDTO;
import com.company.shop.module.order.outbox.OutboxEventAdminActionLogQueryService;
import com.company.shop.module.order.outbox.OutboxEventAdminCommandService;
import com.company.shop.module.order.outbox.OutboxEventAdminSearchCriteria;
import com.company.shop.module.order.outbox.OutboxEventProblemType;
import com.company.shop.module.order.outbox.OutboxEventQueryService;
import com.company.shop.module.order.outbox.OutboxEventStatus;
import com.company.shop.module.order.outbox.dto.OutboxEventAdminActionLogResponseDTO;
import com.company.shop.module.order.outbox.dto.OutboxEventDetailResponseDTO;
import com.company.shop.module.order.outbox.dto.OutboxEventResponseDTO;
import com.company.shop.module.order.outbox.dto.OutboxEventSummaryDTO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/admin/outbox-events")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Outbox Events", description = "Admin-only transactional outbox visibility endpoints.")
public class AdminOutboxEventController {

    private final OutboxEventQueryService outboxEventQueryService;
    private final OutboxEventAdminCommandService outboxEventAdminCommandService;
    private final OutboxEventAdminActionLogQueryService outboxEventAdminActionLogQueryService;

    public AdminOutboxEventController(
            OutboxEventQueryService outboxEventQueryService,
            OutboxEventAdminCommandService outboxEventAdminCommandService,
            OutboxEventAdminActionLogQueryService outboxEventAdminActionLogQueryService) {
        this.outboxEventQueryService = outboxEventQueryService;
        this.outboxEventAdminCommandService = outboxEventAdminCommandService;
        this.outboxEventAdminActionLogQueryService = outboxEventAdminActionLogQueryService;
    }

    @GetMapping
    @Operation(summary = "List outbox events (admin-only)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Outbox events returned successfully."),
            @ApiResponse(responseCode = "401", description = "Unauthorized."),
            @ApiResponse(responseCode = "403", description = "Forbidden (admin role required).")
    })
    public PageResponseDTO<OutboxEventResponseDTO> getEvents(
            @Parameter(description = "Filter by outbox processing status.")
            @RequestParam(required = false) OutboxEventStatus status,
            @Parameter(description = "Filter by aggregate type, such as the domain object category "
                    + "that produced the event.")
            @RequestParam(required = false) String aggregateType,
            @Parameter(description = "Filter by the aggregate identifier associated with the outbox event.")
            @RequestParam(required = false) UUID aggregateId,
            @Parameter(description = "Filter by event type, such as the logical name of the domain event.")
            @RequestParam(required = false) String eventType,
            @Parameter(description = "Filter events whose last processing error contains the provided text.")
            @RequestParam(required = false) String lastErrorContains,
            @Parameter(description = "Filter events created at or after this timestamp.")
            @RequestParam(required = false) Instant createdFrom,
            @Parameter(description = "Filter events created at or before this timestamp.")
            @RequestParam(required = false) Instant createdTo,
            @Parameter(description = "Filter events whose last processing attempt occurred at or after this timestamp.")
            @RequestParam(required = false) Instant lastAttemptFrom,
            @Parameter(description = "Filter events whose last processing attempt occurred at or before "
                    + "this timestamp.")
            @RequestParam(required = false) Instant lastAttemptTo,
            @Parameter(description = "Filter events with attempts greater than or equal to this value.")
            @RequestParam(required = false) Integer attemptsMin,
            @Parameter(description = "Filter events with attempts less than or equal to this value.")
            @RequestParam(required = false) Integer attemptsMax,
            @Parameter(description = "When true, return only events that have been manually requeued at least once.")
            @RequestParam(required = false) Boolean requeuedOnly,
            @Parameter(description = "Filter operational problem categories. STALE_PENDING returns PENDING events "
                    + "older than the stale threshold by createdAt. STALE_FAILED returns FAILED events whose "
                    + "lastAttemptAt is older than the stale threshold. HIGH_ATTEMPT_FAILED returns FAILED events "
                    + "with attempts greater than or equal to the high failed attempts threshold.")
            @RequestParam(required = false) OutboxEventProblemType problemType,
            @PageableDefault(size = 20) Pageable pageable) {
        OutboxEventAdminSearchCriteria criteria = new OutboxEventAdminSearchCriteria(
                status, aggregateType, aggregateId, eventType, lastErrorContains, createdFrom, createdTo,
                lastAttemptFrom, lastAttemptTo, attemptsMin, attemptsMax, requeuedOnly, problemType);
        return PageResponseDTO.from(outboxEventQueryService.getEvents(criteria, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get outbox event details")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Outbox event details returned successfully."),
            @ApiResponse(responseCode = "401", description = "Unauthorized."),
            @ApiResponse(responseCode = "403", description = "Forbidden (admin role required)."),
            @ApiResponse(responseCode = "404", description = "Outbox event not found.")
    })
    public OutboxEventDetailResponseDTO getEvent(@PathVariable UUID id) {
        return outboxEventQueryService.getEvent(id);
    }

    @GetMapping("/{id}/actions")
    @Operation(summary = "List outbox event admin action logs")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Outbox event admin action logs returned successfully."),
            @ApiResponse(responseCode = "401", description = "Unauthorized."),
            @ApiResponse(responseCode = "403", description = "Forbidden (admin role required)."),
            @ApiResponse(responseCode = "404", description = "Outbox event not found.")
    })
    public PageResponseDTO<OutboxEventAdminActionLogResponseDTO> getEventActionLogs(
            @PathVariable UUID id,
            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponseDTO.from(outboxEventAdminActionLogQueryService.getOutboxEventActionLogs(id, pageable));
    }

    @PostMapping("/{id}/requeue")
    @Operation(summary = "Requeue failed outbox event for processing")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Outbox event requeued successfully."),
            @ApiResponse(responseCode = "401", description = "Unauthorized."),
            @ApiResponse(responseCode = "403", description = "Forbidden (admin role required)."),
            @ApiResponse(responseCode = "404", description = "Outbox event not found."),
            @ApiResponse(responseCode = "409", description = "Outbox event cannot be requeued from its current status.")
    })
    public OutboxEventResponseDTO requeueEvent(@PathVariable UUID id) {
        return outboxEventAdminCommandService.requeueFailedEvent(id);
    }

    @GetMapping("/summary")
    @Operation(summary = "Get outbox event summary")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Outbox event summary returned successfully."),
            @ApiResponse(responseCode = "401", description = "Unauthorized."),
            @ApiResponse(responseCode = "403", description = "Forbidden (admin role required).")
    })
    public OutboxEventSummaryDTO getSummary() {
        return outboxEventQueryService.getSummary();
    }
}
