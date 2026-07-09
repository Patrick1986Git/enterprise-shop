package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.company.shop.module.order.entity.OrderStatus;

class OrderPlacedEventPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void orderPlacedPayload_shouldSerializeAndDeserializeWithStableJsonShape() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        OrderPlacedEventPayload payload = new OrderPlacedEventPayload(
                orderId,
                userId,
                "customer@example.com",
                OrderStatus.NEW,
                new BigDecimal("42.50"),
                "2026-05-31T10:15:30",
                List.of(new OrderPlacedEventItemPayload(
                        productId,
                        "Product",
                        "SKU-1",
                        new BigDecimal("12.50"),
                        2)));

        String json = objectMapper.writeValueAsString(payload);
        OrderPlacedEventPayload deserialized = objectMapper.readValue(json, OrderPlacedEventPayload.class);

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(root.get("userId").asText()).isEqualTo(userId.toString());
        assertThat(root.get("userEmail").asText()).isEqualTo("customer@example.com");
        assertThat(root.get("status").asText()).isEqualTo(OrderStatus.NEW.name());
        assertThat(root.get("totalAmount").decimalValue()).isEqualByComparingTo("42.50");
        assertThat(root.get("createdAt").asText()).isEqualTo("2026-05-31T10:15:30");
        assertThat(root.get("items").get(0).get("productId").asText()).isEqualTo(productId.toString());
        assertThat(root.get("items").get(0).get("productName").asText()).isEqualTo("Product");
        assertThat(root.get("items").get(0).get("productSku").asText()).isEqualTo("SKU-1");
        assertThat(root.get("items").get(0).get("price").decimalValue()).isEqualByComparingTo("12.50");
        assertThat(root.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(deserialized).isEqualTo(payload);
    }
}
