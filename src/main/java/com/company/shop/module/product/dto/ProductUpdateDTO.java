package com.company.shop.module.product.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@Schema(description = "Catalog update payload. Stock is inventory-owned and cannot be changed by this operation.")
@JsonIgnoreProperties(ignoreUnknown = false)
public record ProductUpdateDTO(
        @Schema(description = "Product version returned by the latest GET; stale versions are rejected.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "{validation.product.version.required}")
        @PositiveOrZero(message = "{validation.product.version.positiveOrZero}") Long version,
        @NotBlank(message = "{validation.product.name.required}")
        @Size(max = 255, message = "{validation.product.name.size}") String name,
        @NotBlank(message = "{validation.product.sku.required}")
        @Size(max = 50, message = "{validation.product.sku.size}") String sku,
        @Size(max = 5000, message = "{validation.product.description.size}") String description,
        @NotNull(message = "{validation.product.price.required}")
        @DecimalMin(value = "0.01", message = "{validation.product.price.min}")
        @Digits(integer = 10, fraction = 2, message = "{validation.product.price.digits}") BigDecimal price,
        @NotNull(message = "{validation.product.category.required}") UUID categoryId,
        List<@NotNull(message = "{validation.product.imageUrl.required}")
                @Size(max = 512, message = "{validation.product.imageUrl.size}") String> imageUrls) {
    public Long getVersion() { return version; }
    public String getName() { return name; }
    public String getSku() { return sku; }
    public String getDescription() { return description; }
    public BigDecimal getPrice() { return price; }
    public UUID getCategoryId() { return categoryId; }
    public List<String> getImageUrls() { return imageUrls; }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown product catalog field: " + field);
    }
}
