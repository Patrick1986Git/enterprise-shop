package com.company.shop.module.order.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.UUID;

import com.company.shop.common.dto.PageResponseDTO;
import com.company.shop.module.order.dto.OrderResponseDTO;
import com.company.shop.module.order.service.OrderService;
import com.company.shop.module.order.expiration.ReservationExpirationRecoveryResult;
import com.company.shop.module.order.expiration.ReservationExpirationRecoveryService;
import com.company.shop.module.order.expiration.ReservationExpirationWorkQueryService;
import com.company.shop.module.order.expiration.ReservationExpirationWorkResponseDTO;
import com.company.shop.module.order.expiration.ReservationExpirationWorkStatus;
import com.company.shop.module.order.expiration.LegacyReservationAdoptionResult;
import com.company.shop.module.order.expiration.LegacyReservationResponseDTO;
import com.company.shop.module.order.expiration.LegacyReservationService;

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
    private final ReservationExpirationWorkQueryService reservationExpirationWorkQueryService;
    private final LegacyReservationService legacyReservationService;

    public AdminOrderController(OrderService orderService,
            ReservationExpirationRecoveryService reservationExpirationRecoveryService,
            ReservationExpirationWorkQueryService reservationExpirationWorkQueryService,
            LegacyReservationService legacyReservationService) {
        this.orderService = orderService;
        this.reservationExpirationRecoveryService = reservationExpirationRecoveryService;
        this.reservationExpirationWorkQueryService = reservationExpirationWorkQueryService;
        this.legacyReservationService = legacyReservationService;
    }

    @GetMapping("/legacy-reservations")
    @Operation(operationId = "getLegacyReservations",
            summary = "List unmanaged legacy reservations requiring operator review",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Unmanaged legacy reservations returned successfully."),
            @ApiResponse(responseCode = "400", ref = "#/components/responses/BadRequestError"),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError")
    })
    public PageResponseDTO<LegacyReservationResponseDTO> getLegacyReservations(
            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponseDTO.from(legacyReservationService.findUnmanaged(pageable));
    }

    @PostMapping("/{orderId}/legacy-reservation/adopt")
    @Operation(operationId = "adoptLegacyReservation",
            summary = "Adopt an unmanaged legacy reservation for provider-aware reconciliation",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Legacy reservation adoption applied or already managed."),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError"),
            @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundError"),
            @ApiResponse(responseCode = "409", ref = "#/components/responses/ConflictError")
    })
    public LegacyReservationAdoptionResult adoptLegacyReservation(@PathVariable UUID orderId) {
        return legacyReservationService.adopt(orderId);
    }

    @GetMapping("/reservation-expiration-work")
    @Operation(operationId = "getReservationExpirationWork",
            summary = "List reservation expiration work for operational recovery",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Reservation expiration work returned successfully."),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError")
    })
    public PageResponseDTO<ReservationExpirationWorkResponseDTO> getReservationExpirationWork(
            @RequestParam(required = false) ReservationExpirationWorkStatus status,
            @RequestParam(required = false) UUID orderId,
            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponseDTO.from(reservationExpirationWorkQueryService.findAll(status, orderId, pageable));
    }

    @GetMapping("/reservation-expiration-work/{workId}")
    @Operation(operationId = "getReservationExpirationWorkById",
            summary = "Inspect reservation expiration work before recovery",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Reservation expiration work returned successfully."),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError"),
            @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundError")
    })
    public ReservationExpirationWorkResponseDTO getReservationExpirationWork(@PathVariable UUID workId) {
        return reservationExpirationWorkQueryService.findById(workId);
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
