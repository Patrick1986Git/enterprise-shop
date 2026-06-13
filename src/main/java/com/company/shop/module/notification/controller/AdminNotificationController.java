package com.company.shop.module.notification.controller;

import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.common.dto.PageResponseDTO;
import com.company.shop.module.notification.dto.NotificationAdminActionLogResponseDTO;
import com.company.shop.module.notification.dto.NotificationResponseDTO;
import com.company.shop.module.notification.dto.NotificationSummaryDTO;
import com.company.shop.module.notification.entity.NotificationStatus;
import com.company.shop.module.notification.service.NotificationAdminActionLogQueryService;
import com.company.shop.module.notification.service.NotificationAdminCommandService;
import com.company.shop.module.notification.service.NotificationQueryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/admin/notifications")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Notifications", description = "Admin-only notification visibility endpoints.")
public class AdminNotificationController {

    private final NotificationQueryService notificationQueryService;
    private final NotificationAdminCommandService notificationAdminCommandService;
    private final NotificationAdminActionLogQueryService notificationAdminActionLogQueryService;

    public AdminNotificationController(
            NotificationQueryService notificationQueryService,
            NotificationAdminCommandService notificationAdminCommandService,
            NotificationAdminActionLogQueryService notificationAdminActionLogQueryService) {
        this.notificationQueryService = notificationQueryService;
        this.notificationAdminCommandService = notificationAdminCommandService;
        this.notificationAdminActionLogQueryService = notificationAdminActionLogQueryService;
    }

    @GetMapping
    @Operation(summary = "List notifications (admin-only)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Notifications returned successfully."),
            @ApiResponse(responseCode = "401", description = "Unauthorized."),
            @ApiResponse(responseCode = "403", description = "Forbidden (admin role required).")
    })
    public PageResponseDTO<NotificationResponseDTO> getNotifications(
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String recipient,
            @RequestParam(required = false) Boolean requeuedOnly,
            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponseDTO.from(notificationQueryService.getNotifications(
                status, type, recipient, requeuedOnly, pageable));
    }

    @GetMapping("/summary")
    @Operation(summary = "Get notification summary (admin-only)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Notification summary returned successfully."),
            @ApiResponse(responseCode = "401", description = "Unauthorized."),
            @ApiResponse(responseCode = "403", description = "Forbidden (admin role required).")
    })
    public NotificationSummaryDTO getSummary() {
        return notificationQueryService.getSummary();
    }

    @PostMapping("/{id}/requeue")
    @Operation(summary = "Requeue failed notification for delivery (admin-only)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Notification requeued successfully."),
            @ApiResponse(responseCode = "401", description = "Unauthorized."),
            @ApiResponse(responseCode = "403", description = "Forbidden (admin role required)."),
            @ApiResponse(responseCode = "404", description = "Notification not found."),
            @ApiResponse(responseCode = "409", description = "Notification cannot be requeued from its current status.")
    })
    public NotificationResponseDTO requeueNotification(@PathVariable UUID id) {
        return notificationAdminCommandService.requeueFailedNotification(id);
    }

    @GetMapping("/{id}/actions")
    @Operation(summary = "Get notification admin action logs")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Notification admin action logs returned successfully."),
            @ApiResponse(responseCode = "401", description = "Unauthorized."),
            @ApiResponse(responseCode = "403", description = "Forbidden (admin role required)."),
            @ApiResponse(responseCode = "404", description = "Notification not found.")
    })
    public PageResponseDTO<NotificationAdminActionLogResponseDTO> getNotificationActionLogs(
            @PathVariable UUID id,
            Pageable pageable) {
        return PageResponseDTO.from(
                notificationAdminActionLogQueryService.getNotificationActionLogs(id, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get notification by ID (admin-only)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Notification found."),
            @ApiResponse(responseCode = "401", description = "Unauthorized."),
            @ApiResponse(responseCode = "403", description = "Forbidden (admin role required)."),
            @ApiResponse(responseCode = "404", description = "Notification not found.")
    })
    public NotificationResponseDTO getNotification(@PathVariable UUID id) {
        return notificationQueryService.getNotification(id);
    }
}
