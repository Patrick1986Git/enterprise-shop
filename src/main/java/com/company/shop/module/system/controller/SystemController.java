package com.company.shop.module.system.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.module.system.dto.ApplicationStatusDTO;
import com.company.shop.module.system.service.ApplicationStatusService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "System", description = "Public technical system endpoints.")
public class SystemController {

    private final ApplicationStatusService statusService;

    public SystemController(ApplicationStatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping("/status")
    @Operation(summary = "Get application status")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Application status returned successfully.")
    })
    public ResponseEntity<ApplicationStatusDTO> getSystemStatus() {
        return ResponseEntity.ok(statusService.getApplicationStatus());
    }
}
