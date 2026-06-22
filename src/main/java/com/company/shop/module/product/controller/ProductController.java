package com.company.shop.module.product.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.module.product.dto.ProductResponseDTO;
import com.company.shop.module.product.dto.ProductSearchCriteria;
import com.company.shop.module.product.service.ProductService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products", description = "Public product browsing and search endpoints.")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    @Operation(
            operationId = "getProducts",
            summary = "List products"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Products returned successfully.")
    })
    public Page<ProductResponseDTO> getProducts(
            @Parameter(description = "Zero-based page index.")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Number of products to return per page.")
            @RequestParam(defaultValue = "12") int size,
            @Parameter(description = "Optional sort expression in property,direction format.")
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageRequest.of(page, size, buildSort(sort));
        return productService.findAll(pageable);
    }

    @GetMapping("/category/{categoryId}")
    @Operation(
            operationId = "getProductsByCategory",
            summary = "List products by category"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Category products returned successfully."),
            @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundError")
    })
    public Page<ProductResponseDTO> getProductsByCategory(
            @Parameter(description = "Category identifier.")
            @PathVariable UUID categoryId,
            @Parameter(description = "Zero-based page index.")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Number of products to return per page.")
            @RequestParam(defaultValue = "12") int size,
            @Parameter(description = "Optional sort expression in property,direction format.")
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageRequest.of(page, size, buildSort(sort));
        return productService.findAllByCategory(categoryId, pageable);
    }

    @GetMapping("/slug/{slug}")
    @Operation(
            operationId = "getProductBySlug",
            summary = "Get product details by slug"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Product found."),
            @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundError")
    })
    public ProductResponseDTO getProductBySlug(
            @Parameter(description = "URL-safe product slug.")
            @PathVariable String slug) {
        return productService.findBySlug(slug);
    }

    @GetMapping("/search")
    @Operation(
            operationId = "searchProducts",
            summary = "Search products"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Search results returned successfully.")
    })
    public Page<ProductResponseDTO> searchProducts(
            @Parameter(description = "Product search filter criteria.")
            @Valid @ModelAttribute ProductSearchCriteria criteria,
            @Parameter(description = "Pagination and sorting options.")
            @PageableDefault(size = 12, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return productService.searchProducts(criteria, pageable);
    }

    private Sort buildSort(String sortParam) {
        if (sortParam == null || sortParam.isBlank()) {
            return Sort.unsorted();
        }

        String[] parts = sortParam.split(",");
        String property = parts[0].trim();
        if (property.isEmpty()) {
            return Sort.unsorted();
        }

        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length > 1) {
            direction = Sort.Direction.fromOptionalString(parts[1].trim()).orElse(Sort.Direction.ASC);
        }
        return Sort.by(direction, property);
    }
}
