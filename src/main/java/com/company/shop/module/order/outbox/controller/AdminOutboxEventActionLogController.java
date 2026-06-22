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
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
    @Operation(
            operationId = "searchOutboxEventActionLogs",
            summary = "Search outbox event admin action logs",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Outbox event admin action logs returned successfully."),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError")
    })
    public PageResponseDTO<OutboxEventAdminActionLogResponseDTO> searchActionLogs(
            @Parameter(description = "Filter action logs for a specific outbox event identifier.")
            @RequestParam(required = false) UUID outboxEventId,
            @Parameter(description = "Filter by admin action type, such as REQUEUE.")
            @RequestParam(required = false) OutboxEventAdminActionType actionType,
            @Parameter(description = "Filter by the email address of the admin actor who performed the action.")
            @RequestParam(required = false) String actorEmail,
            @Parameter(description = "Filter action logs created at or after this timestamp.")
            @RequestParam(required = false) Instant createdFrom,
            @Parameter(description = "Filter action logs created at or before this timestamp.")
            @RequestParam(required = false) Instant createdTo,
            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponseDTO.from(outboxEventAdminActionLogQueryService.searchActionLogs(
                outboxEventId, actionType, actorEmail, createdFrom, createdTo, pageable));
    }
}
