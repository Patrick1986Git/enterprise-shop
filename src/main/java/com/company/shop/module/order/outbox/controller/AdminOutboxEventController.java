package com.company.shop.module.order.outbox.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.module.order.outbox.OutboxEventQueryService;
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
