package com.company.shop.module.order.controller;

import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.company.shop.common.dto.PageResponseDTO;
import com.company.shop.module.order.dto.StripePaymentConflictDispositionRequestDTO;
import com.company.shop.module.order.dto.StripePaymentConflictDispositionResponseDTO;
import com.company.shop.module.order.dto.StripePaymentConflictResponseDTO;
import com.company.shop.module.order.service.StripePaymentConflictDispositionCommandService;
import com.company.shop.module.order.service.StripePaymentConflictDispositionQueryService;
import com.company.shop.module.order.service.StripePaymentConflictQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/orders/stripe-payment-conflicts")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Orders", description = "Admin-only order management endpoints.")
public class AdminStripePaymentConflictController {
    private final StripePaymentConflictQueryService queryService;
    private final StripePaymentConflictDispositionCommandService dispositionCommandService;
    private final StripePaymentConflictDispositionQueryService dispositionQueryService;
    public AdminStripePaymentConflictController(StripePaymentConflictQueryService queryService,
            StripePaymentConflictDispositionCommandService dispositionCommandService,
            StripePaymentConflictDispositionQueryService dispositionQueryService) {
        this.queryService = queryService;
        this.dispositionCommandService = dispositionCommandService;
        this.dispositionQueryService = dispositionQueryService;
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

    @PostMapping("/{conflictId}/dispositions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "appendStripePaymentConflictDisposition",
            summary = "Append a non-financial investigation disposition",
            security = @SecurityRequirement(name = "bearerAuth"))
    public StripePaymentConflictDispositionResponseDTO appendDisposition(
            @PathVariable UUID conflictId, @Valid @RequestBody StripePaymentConflictDispositionRequestDTO request) {
        return dispositionCommandService.append(conflictId, request.actionType());
    }

    @GetMapping("/{conflictId}/dispositions")
    @Operation(operationId = "getStripePaymentConflictDispositions",
            summary = "List append-only investigation disposition history",
            security = @SecurityRequirement(name = "bearerAuth"))
    public PageResponseDTO<StripePaymentConflictDispositionResponseDTO> findDispositions(
            @PathVariable UUID conflictId, @PageableDefault(size = 20) Pageable pageable) {
        return PageResponseDTO.from(dispositionQueryService.findByConflictId(conflictId, pageable));
    }
}
