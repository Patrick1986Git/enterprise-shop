package com.company.shop.module.order.controller;

import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.company.shop.common.dto.PageResponseDTO;
import com.company.shop.module.order.dto.StripePaymentConflictResponseDTO;
import com.company.shop.module.order.service.StripePaymentConflictQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/admin/orders/stripe-payment-conflicts")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Orders", description = "Admin-only order management endpoints.")
public class AdminStripePaymentConflictController {
    private final StripePaymentConflictQueryService queryService;
    public AdminStripePaymentConflictController(StripePaymentConflictQueryService queryService) {
        this.queryService = queryService;
    }
    @GetMapping
    @Operation(operationId = "getStripePaymentConflicts", summary = "List immutable Stripe payment conflicts",
            security = @SecurityRequirement(name = "bearerAuth"))
    public PageResponseDTO<StripePaymentConflictResponseDTO> findAll(@PageableDefault(size = 20) Pageable pageable) {
        return PageResponseDTO.from(queryService.findAll(pageable));
    }
    @GetMapping("/{conflictId}")
    @Operation(operationId = "getStripePaymentConflict", summary = "Inspect immutable Stripe payment conflict evidence",
            security = @SecurityRequirement(name = "bearerAuth"))
    public StripePaymentConflictResponseDTO findById(@PathVariable UUID conflictId) {
        return queryService.findById(conflictId);
    }
}
