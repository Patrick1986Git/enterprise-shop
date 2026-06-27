package com.company.shop.module.notification.controller;

import java.time.Instant;
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
import com.company.shop.module.notification.NotificationAdminSearchCriteria;
import com.company.shop.module.notification.NotificationDeliveryState;
import com.company.shop.module.notification.dto.NotificationAdminActionLogResponseDTO;
import com.company.shop.module.notification.dto.NotificationResponseDTO;
import com.company.shop.module.notification.dto.NotificationSummaryDTO;
import com.company.shop.module.notification.entity.NotificationStatus;
import com.company.shop.module.notification.service.NotificationAdminActionLogQueryService;
import com.company.shop.module.notification.service.NotificationAdminCommandService;
import com.company.shop.module.notification.service.NotificationQueryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
    @Operation(
            operationId = "getNotifications",
            summary = "List notifications (admin-only)",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Notifications returned successfully."),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError")
    })
    public PageResponseDTO<NotificationResponseDTO> getNotifications(
            @Parameter(description = "Filter by notification delivery status.")
            @RequestParam(required = false) NotificationStatus status,
            @Parameter(description = "Filter by pending delivery state. DUE_PENDING means PENDING notifications due now, "
                    + "with nextAttemptAt null or nextAttemptAt less than or equal to now. SCHEDULED_PENDING means "
                    + "PENDING notifications scheduled for later, with nextAttemptAt greater than now.")
            @RequestParam(required = false) NotificationDeliveryState deliveryState,
            @Parameter(description = "Filter by source outbox event identifier.")
            @RequestParam(required = false) UUID sourceEventId,
            @Parameter(description = "Filter by notification type, such as the logical notification category.")
            @RequestParam(required = false) String type,
            @Parameter(description = "Filter by recipient address or identifier.")
            @RequestParam(required = false) String recipient,
            @Parameter(description = "Filter by text contained in the last delivery error, case-insensitively.")
            @RequestParam(required = false) String lastErrorContains,
            @Parameter(description = "When true, return only notifications that have been manually requeued "
                    + "at least once.")
            @RequestParam(required = false) Boolean requeuedOnly,
            @Parameter(description = "Filter notifications with attempts greater than or equal to this value.")
            @RequestParam(required = false) Integer attemptsMin,
            @Parameter(description = "Filter notifications with attempts less than or equal to this value.")
            @RequestParam(required = false) Integer attemptsMax,
            @Parameter(description = "Filter notifications whose last attempt timestamp is greater than or equal to this value.")
            @RequestParam(required = false) Instant lastAttemptFrom,
            @Parameter(description = "Filter notifications whose last attempt timestamp is less than or equal to this value.")
            @RequestParam(required = false) Instant lastAttemptTo,
            @Parameter(description = "Filter notifications whose sent timestamp is greater than or equal to this value.")
            @RequestParam(required = false) Instant sentFrom,
            @Parameter(description = "Filter notifications whose sent timestamp is less than or equal to this value.")
            @RequestParam(required = false) Instant sentTo,
            @PageableDefault(size = 20) Pageable pageable) {
        NotificationAdminSearchCriteria criteria = NotificationAdminSearchCriteria.builder()
                .status(status)
                .deliveryState(deliveryState)
                .sourceEventId(sourceEventId)
                .type(type)
                .recipient(recipient)
                .lastErrorContains(lastErrorContains)
                .requeuedOnly(requeuedOnly)
                .attemptsMin(attemptsMin)
                .attemptsMax(attemptsMax)
                .lastAttemptFrom(lastAttemptFrom)
                .lastAttemptTo(lastAttemptTo)
                .sentFrom(sentFrom)
                .sentTo(sentTo)
                .build();
        return PageResponseDTO.from(notificationQueryService.getNotifications(criteria, pageable));
    }

    @GetMapping("/summary")
    @Operation(
            operationId = "getNotificationSummary",
            summary = "Get notification summary (admin-only)",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Notification summary returned successfully."),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError")
    })
    public NotificationSummaryDTO getSummary() {
        return notificationQueryService.getSummary();
    }

    @PostMapping("/{id}/requeue")
    @Operation(
            operationId = "requeueNotification",
            summary = "Requeue failed notification for delivery (admin-only)",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Notification requeued successfully."),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError"),
            @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundError"),
            @ApiResponse(responseCode = "409", ref = "#/components/responses/ConflictError")
    })
    public NotificationResponseDTO requeueNotification(
            @Parameter(description = "Notification identifier.")
            @PathVariable UUID id) {
        return notificationAdminCommandService.requeueFailedNotification(id);
    }

    @GetMapping("/{id}/actions")
    @Operation(
            operationId = "getNotificationActionLogs",
            summary = "Get notification admin action logs",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Notification admin action logs returned successfully."),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError"),
            @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundError")
    })
    public PageResponseDTO<NotificationAdminActionLogResponseDTO> getNotificationActionLogs(
            @Parameter(description = "Resource identifier.") @PathVariable UUID id,
            Pageable pageable) {
        return PageResponseDTO.from(
                notificationAdminActionLogQueryService.getNotificationActionLogs(id, pageable));
    }

    @GetMapping("/{id}")
    @Operation(
            operationId = "getNotificationById",
            summary = "Get notification by ID (admin-only)",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Notification found."),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError"),
            @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundError")
    })
    public NotificationResponseDTO getNotification(
            @Parameter(description = "Notification identifier.")
            @PathVariable UUID id) {
        return notificationQueryService.getNotification(id);
    }
}
