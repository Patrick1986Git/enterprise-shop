package com.company.shop.module.order.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.common.dto.PageResponseDTO;
import com.company.shop.module.order.dto.OrderCheckoutRequestDTO;
import com.company.shop.module.order.dto.OrderResponseDTO;
import com.company.shop.module.order.service.OrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/me/orders")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Orders", description = "Operations on orders owned by the authenticated user.")
public class CurrentUserOrderController {

    private final OrderService orderService;

    public CurrentUserOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    @Operation(summary = "List the authenticated user's orders")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Orders returned successfully."),
            @ApiResponse(responseCode = "401", description = "Unauthorized.")
    })
    public PageResponseDTO<OrderResponseDTO> getCurrentUserOrders(@PageableDefault(size = 10) Pageable pageable) {
        return PageResponseDTO.from(orderService.findMyOrders(pageable));
    }

    @PostMapping("/checkout")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Checkout the cart and create an order")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Order created successfully."),
            @ApiResponse(responseCode = "400", description = "Invalid request payload."),
            @ApiResponse(responseCode = "401", description = "Unauthorized.")
    })
    public OrderResponseDTO checkout(@Valid @RequestBody OrderCheckoutRequestDTO request) {
        return orderService.placeOrderFromCart(request);
    }
}
