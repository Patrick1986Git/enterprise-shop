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
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
    @Operation(summary = "Search notification admin action logs")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Notification admin action logs returned successfully."),
            @ApiResponse(responseCode = "401", description = "Unauthorized."),
            @ApiResponse(responseCode = "403", description = "Forbidden (admin role required).")
    })
    public PageResponseDTO<NotificationAdminActionLogResponseDTO> searchActionLogs(
            @RequestParam(required = false) UUID notificationId,
            @RequestParam(required = false) NotificationAdminActionType actionType,
            @RequestParam(required = false) String actorEmail,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponseDTO.from(notificationAdminActionLogQueryService.searchActionLogs(
                notificationId, actionType, actorEmail, createdFrom, createdTo, pageable));
    }
}
