package com.company.shop.module.order.dto;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Request payload for creating a new order.")
public class OrderCreateRequestDTO {

    @Schema(description = "Items to include in the order.", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "{validation.order.items.required}")
    @Valid
    private List<OrderItemRequestDTO> items;

    @Schema(description = "Optional promotional discount code.", example = "SAVE10", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String discountCode;

    public List<OrderItemRequestDTO> getItems() { return items; }
    public String getDiscountCode() { return discountCode; }

    @Schema(description = "Request payload for an individual order item.")
    public static class OrderItemRequestDTO {
        @Schema(description = "Identifier of the product to order.", example = "11111111-1111-1111-1111-111111111111", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "{validation.order.item.product.required}")
        private UUID productId;

        @Schema(description = "Quantity of the product to order.", example = "2", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @Min(value = 1, message = "{validation.order.item.quantity.min}")
        private int quantity;

        public UUID getProductId() { return productId; }
        public int getQuantity() { return quantity; }
    }
}
