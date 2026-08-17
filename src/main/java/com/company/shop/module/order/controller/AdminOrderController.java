package com.company.shop.module.order.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import java.util.UUID;

import com.company.shop.common.dto.PageResponseDTO;
import com.company.shop.module.order.dto.OrderResponseDTO;
import com.company.shop.module.order.service.OrderService;
import com.company.shop.module.order.expiration.ReservationExpirationRecoveryResult;
import com.company.shop.module.order.expiration.ReservationExpirationRecoveryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/admin/orders")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Orders", description = "Admin-only order management endpoints.")
public class AdminOrderController {

    private final OrderService orderService;
    private final ReservationExpirationRecoveryService reservationExpirationRecoveryService;

    public AdminOrderController(OrderService orderService,
            ReservationExpirationRecoveryService reservationExpirationRecoveryService) {
        this.orderService = orderService;
        this.reservationExpirationRecoveryService = reservationExpirationRecoveryService;
    }

    @GetMapping
    @Operation(
            operationId = "getAdminOrders",
            summary = "List orders (admin-only)",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Orders returned successfully."),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError")
    })
    public PageResponseDTO<OrderResponseDTO> getOrders(@PageableDefault(size = 20) Pageable pageable) {
        return PageResponseDTO.from(orderService.findAll(pageable));
    }

    @PostMapping("/reservation-expiration-work/{workId}/recover")
    @Operation(operationId = "recoverReservationExpirationWork",
            summary = "Requeue failed reservation expiration work for provider-aware reconciliation",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Recovery request applied successfully."),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError"),
            @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundError"),
            @ApiResponse(responseCode = "409", ref = "#/components/responses/ConflictError")
    })
    public ReservationExpirationRecoveryResult recoverReservationExpirationWork(@PathVariable UUID workId) {
        return reservationExpirationRecoveryService.recover(workId);
    }
}
