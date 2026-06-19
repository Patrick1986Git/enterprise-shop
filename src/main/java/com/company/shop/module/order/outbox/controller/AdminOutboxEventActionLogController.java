package com.company.shop.module.order.outbox.controller;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.common.dto.PageResponseDTO;
import com.company.shop.module.order.outbox.OutboxEventAdminActionLogQueryService;
import com.company.shop.module.order.outbox.OutboxEventAdminActionType;
import com.company.shop.module.order.outbox.dto.OutboxEventAdminActionLogResponseDTO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/admin/outbox-event-actions")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Outbox Event Actions", description = "Admin-only outbox event action log visibility endpoints.")
public class AdminOutboxEventActionLogController {

    private final OutboxEventAdminActionLogQueryService outboxEventAdminActionLogQueryService;

    public AdminOutboxEventActionLogController(
            OutboxEventAdminActionLogQueryService outboxEventAdminActionLogQueryService) {
        this.outboxEventAdminActionLogQueryService = outboxEventAdminActionLogQueryService;
    }

    @GetMapping
    @Operation(summary = "Search outbox event admin action logs")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Outbox event admin action logs returned successfully."),
            @ApiResponse(responseCode = "401", description = "Unauthorized."),
            @ApiResponse(responseCode = "403", description = "Forbidden (admin role required).")
    })
    public PageResponseDTO<OutboxEventAdminActionLogResponseDTO> searchActionLogs(
            @RequestParam(required = false) UUID outboxEventId,
            @RequestParam(required = false) OutboxEventAdminActionType actionType,
            @RequestParam(required = false) String actorEmail,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponseDTO.from(outboxEventAdminActionLogQueryService.searchActionLogs(
                outboxEventId, actionType, actorEmail, createdFrom, createdTo, pageable));
    }
}
