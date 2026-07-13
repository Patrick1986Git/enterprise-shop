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
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
    @Operation(
            operationId = "getOutboxEvents",
            summary = "List outbox events (admin-only)",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Outbox events returned successfully."),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError")
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
            @Parameter(description = "Filter by exact event-specific payload contract version.")
            @RequestParam(required = false) Integer eventVersion,
            @Parameter(description = "Filter events whose last processing error contains the provided text.")
            @RequestParam(required = false) String lastErrorContains,
            @Parameter(description = "Filter events created at or after this timestamp.")
            @RequestParam(required = false) Instant createdFrom,
            @Parameter(description = "Filter events created at or before this timestamp.")
            @RequestParam(required = false) Instant createdTo,
            @Parameter(description = "Filter events processed at or after this timestamp.")
            @RequestParam(required = false) Instant processedFrom,
            @Parameter(description = "Filter events processed at or before this timestamp.")
            @RequestParam(required = false) Instant processedTo,
            @Parameter(description = "Filter events whose last processing attempt occurred at or after this timestamp.")
            @RequestParam(required = false) Instant lastAttemptFrom,
            @Parameter(description = "Filter events whose last processing attempt occurred at or before "
                    + "this timestamp.")
            @RequestParam(required = false) Instant lastAttemptTo,
            @Parameter(description = "Filter events whose next scheduled retry attempt is at or after this timestamp.")
            @RequestParam(required = false) Instant nextAttemptFrom,
            @Parameter(description = "Filter events whose next scheduled retry attempt is at or before this timestamp.")
            @RequestParam(required = false) Instant nextAttemptTo,
            @Parameter(description = "Filter events with attempts greater than or equal to this value.")
            @RequestParam(required = false) Integer attemptsMin,
            @Parameter(description = "Filter events with attempts less than or equal to this value.")
            @RequestParam(required = false) Integer attemptsMax,
            @Parameter(description = "When true, return only events that have been manually requeued at least once.")
            @RequestParam(required = false) Boolean requeuedOnly,
            @Parameter(description = "Filter operational problem categories. STALE_PENDING returns PENDING events "
                    + "older than the stale threshold by createdAt. STALE_FAILED returns FAILED events whose "
                    + "lastAttemptAt is older than the stale threshold. HIGH_ATTEMPT_FAILED returns FAILED events "
                    + "with attempts greater than or equal to the high failed attempts threshold. DEAD_LETTER returns "
                    + "terminal dead-lettered events.")
            @RequestParam(required = false) OutboxEventProblemType problemType,
            @PageableDefault(size = 20) Pageable pageable) {
        OutboxEventAdminSearchCriteria criteria = OutboxEventAdminSearchCriteria.builder()
                .status(status)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .eventVersion(eventVersion)
                .lastErrorContains(lastErrorContains)
                .createdFrom(createdFrom)
                .createdTo(createdTo)
                .processedFrom(processedFrom)
                .processedTo(processedTo)
                .lastAttemptFrom(lastAttemptFrom)
                .lastAttemptTo(lastAttemptTo)
                .nextAttemptFrom(nextAttemptFrom)
                .nextAttemptTo(nextAttemptTo)
                .attemptsMin(attemptsMin)
                .attemptsMax(attemptsMax)
                .requeuedOnly(requeuedOnly)
                .problemType(problemType)
                .build();
        return PageResponseDTO.from(outboxEventQueryService.getEvents(criteria, pageable));
    }

    @GetMapping("/{id}")
    @Operation(
            operationId = "getOutboxEventById",
            summary = "Get outbox event details",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Outbox event details returned successfully."),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError"),
            @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundError")
    })
    public OutboxEventDetailResponseDTO getEvent(
            @Parameter(description = "Outbox event identifier.")
            @PathVariable UUID id) {
        return outboxEventQueryService.getEvent(id);
    }

    @GetMapping("/{id}/actions")
    @Operation(
            operationId = "getOutboxEventActionLogs",
            summary = "List outbox event admin action logs",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Outbox event admin action logs returned successfully."),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError"),
            @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundError")
    })
    public PageResponseDTO<OutboxEventAdminActionLogResponseDTO> getEventActionLogs(
            @Parameter(description = "Resource identifier.") @PathVariable UUID id,
            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponseDTO.from(outboxEventAdminActionLogQueryService.getOutboxEventActionLogs(id, pageable));
    }

    @PostMapping("/{id}/requeue")
    @Operation(
            operationId = "requeueOutboxEvent",
            summary = "Requeue failed or dead-lettered outbox event for processing",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Outbox event requeued successfully."),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError"),
            @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundError"),
            @ApiResponse(responseCode = "409", ref = "#/components/responses/ConflictError")
    })
    public OutboxEventResponseDTO requeueEvent(
            @Parameter(description = "Outbox event identifier.")
            @PathVariable UUID id) {
        return outboxEventAdminCommandService.requeueFailedEvent(id);
    }

    @GetMapping("/summary")
    @Operation(
            operationId = "getOutboxEventSummary",
            summary = "Get outbox event summary",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Outbox event summary returned successfully."),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError")
    })
    public OutboxEventSummaryDTO getSummary() {
        return outboxEventQueryService.getSummary();
    }
}
