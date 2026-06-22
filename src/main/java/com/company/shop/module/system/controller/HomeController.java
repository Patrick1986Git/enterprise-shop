package com.company.shop.module.system.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "System", description = "Public API entry point.")
public class HomeController {

    @GetMapping
    @Operation(operationId = "getApiStatus", summary = "Get API status message")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "API status message returned successfully.")
    })
    public String getApiStatus() {
        return "Enterprise Shop API is running!";
    }
}
