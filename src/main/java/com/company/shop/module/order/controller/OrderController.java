package com.company.shop.module.order.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.module.order.dto.OrderDetailedResponseDTO;
import com.company.shop.module.order.service.OrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/orders")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Orders", description = "Endpointy zamówień użytkownika.")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Szczegóły zamówienia po ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Zamówienie znalezione."),
            @ApiResponse(responseCode = "401", description = "Brak autoryzacji."),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień."),
            @ApiResponse(responseCode = "404", description = "Zamówienie nie zostało znalezione.")
    })
    public OrderDetailedResponseDTO getOrderById(@PathVariable UUID id) {
        return orderService.findById(id);
    }
}
