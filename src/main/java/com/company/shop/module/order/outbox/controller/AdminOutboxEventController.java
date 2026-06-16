package com.company.shop.module.order.outbox.controller;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.common.dto.PageResponseDTO;
import com.company.shop.module.order.outbox.OutboxEventQueryService;
import com.company.shop.module.order.outbox.OutboxEventStatus;
import com.company.shop.module.order.outbox.dto.OutboxEventDetailResponseDTO;
import com.company.shop.module.order.outbox.dto.OutboxEventResponseDTO;
import com.company.shop.module.order.outbox.dto.OutboxEventSummaryDTO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/admin/outbox-events")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Outbox Events", description = "Admin-only transactional outbox visibility endpoints.")
public class AdminOutboxEventController {

    private final OutboxEventQueryService outboxEventQueryService;

    public AdminOutboxEventController(OutboxEventQueryService outboxEventQueryService) {
        this.outboxEventQueryService = outboxEventQueryService;
    }

    @GetMapping
    @Operation(summary = "List outbox events (admin-only)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Outbox events returned successfully."),
            @ApiResponse(responseCode = "401", description = "Unauthorized."),
            @ApiResponse(responseCode = "403", description = "Forbidden (admin role required).")
    })
    public PageResponseDTO<OutboxEventResponseDTO> getEvents(
            @RequestParam(required = false) OutboxEventStatus status,
            @RequestParam(required = false) String aggregateType,
            @RequestParam(required = false) UUID aggregateId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponseDTO.from(outboxEventQueryService.getEvents(
                status, aggregateType, aggregateId, eventType, createdFrom, createdTo, pageable));
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
