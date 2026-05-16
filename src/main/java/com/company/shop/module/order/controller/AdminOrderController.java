package com.company.shop.module.order.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.common.dto.PageResponseDTO;
import com.company.shop.module.order.dto.OrderResponseDTO;
import com.company.shop.module.order.service.OrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/admin/orders")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Orders", description = "Administracyjne endpointy zamówień.")
public class AdminOrderController {

    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    @Operation(summary = "Lista zamówień")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Zamówienia pobrane poprawnie."),
            @ApiResponse(responseCode = "401", description = "Brak autoryzacji."),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień.")
    })
    public PageResponseDTO<OrderResponseDTO> getOrders(@PageableDefault(size = 20) Pageable pageable) {
        return PageResponseDTO.from(orderService.findAll(pageable));
    }
}
