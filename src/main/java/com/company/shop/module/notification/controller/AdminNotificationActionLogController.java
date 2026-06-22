package com.company.shop.module.notification.controller;

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
import com.company.shop.module.notification.dto.NotificationAdminActionLogResponseDTO;
import com.company.shop.module.notification.entity.NotificationAdminActionType;
import com.company.shop.module.notification.service.NotificationAdminActionLogQueryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/admin/notification-actions")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Notification Actions", description = "Admin-only notification action log visibility endpoints.")
public class AdminNotificationActionLogController {

    private final NotificationAdminActionLogQueryService notificationAdminActionLogQueryService;

    public AdminNotificationActionLogController(
            NotificationAdminActionLogQueryService notificationAdminActionLogQueryService) {
        this.notificationAdminActionLogQueryService = notificationAdminActionLogQueryService;
    }

    @GetMapping
    @Operation(
            operationId = "searchNotificationActionLogs",
            summary = "Search notification admin action logs",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Notification admin action logs returned successfully."),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError")
    })
    public PageResponseDTO<NotificationAdminActionLogResponseDTO> searchActionLogs(
            @Parameter(description = "Filter action logs for a specific notification identifier.")
            @RequestParam(required = false) UUID notificationId,
            @Parameter(description = "Filter by admin action type, such as REQUEUE.")
            @RequestParam(required = false) NotificationAdminActionType actionType,
            @Parameter(description = "Filter by the email address of the admin actor who performed the action.")
            @RequestParam(required = false) String actorEmail,
            @Parameter(description = "Filter action logs created at or after this timestamp.")
            @RequestParam(required = false) Instant createdFrom,
            @Parameter(description = "Filter action logs created at or before this timestamp.")
            @RequestParam(required = false) Instant createdTo,
            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponseDTO.from(notificationAdminActionLogQueryService.searchActionLogs(
                notificationId, actionType, actorEmail, createdFrom, createdTo, pageable));
    }
}
