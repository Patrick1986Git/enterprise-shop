package com.company.shop.module.order.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.common.exception.GlobalExceptionHandler;
import com.company.shop.common.i18n.MessageService;
import com.company.shop.config.SecurityConfig;
import com.company.shop.module.order.dto.OrderDetailedResponseDTO;
import com.company.shop.module.order.dto.OrderItemResponseDTO;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.exception.OrderNotFoundException;
import com.company.shop.module.order.service.OrderService;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.company.shop.support.TestMeterRegistryConfig;

@WebMvcTest(controllers = OrderController.class)
@ActiveProfiles("test")
@Import({ SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class, MessageService.class, TestMeterRegistryConfig.class })
class OrderControllerWebMvcTest {

    private static final String ORDER_BY_ID_URL = "/api/v1/orders/{id}";
    private static final UUID ORDER_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID PRODUCT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 3, 15, 10, 30);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.validate(anyString())).thenReturn(false);
    }

    @Test
    void getOrderById_shouldReturnForbiddenForAnonymous() throws Exception {
        mockMvc.perform(get(ORDER_BY_ID_URL, ORDER_ID))
                .andExpect(status().isForbidden());

        verifyNoInteractions(orderService);
    }

    @Test
    void getOrderById_shouldReturnStableContractForAuthenticatedUser() throws Exception {
        when(orderService.findById(eq(ORDER_ID))).thenReturn(sampleDetailedOrder(ORDER_ID));

        mockMvc.perform(get(ORDER_BY_ID_URL, ORDER_ID)
                        .with(user("john").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value("99999999-9999-9999-9999-999999999999"))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.totalAmount").value(199.98))
                .andExpect(jsonPath("$.createdAt").value("2026-03-15T10:30:00"))
                .andExpect(jsonPath("$.userEmail").value("john@example.com"))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
                .andExpect(jsonPath("$.items[0].productName").value("Test Product"))
                .andExpect(jsonPath("$.items[0].sku").value("SKU-TEST-001"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].price").value(99.99))
                .andExpect(jsonPath("$.items[0].subtotal").value(199.98));

        verify(orderService).findById(eq(ORDER_ID));
        verifyNoMoreInteractions(orderService);
    }

    @Test
    void getOrderById_shouldReturnStableContractWhenOrderHasNoItems() throws Exception {
        when(orderService.findById(eq(ORDER_ID))).thenReturn(sampleDetailedOrderWithoutItems(ORDER_ID));

        mockMvc.perform(get(ORDER_BY_ID_URL, ORDER_ID)
                        .with(user("john").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value("99999999-9999-9999-9999-999999999999"))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.totalAmount").value(199.98))
                .andExpect(jsonPath("$.createdAt").value("2026-03-15T10:30:00"))
                .andExpect(jsonPath("$.userEmail").value("john@example.com"))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0));

        verify(orderService).findById(eq(ORDER_ID));
        verifyNoMoreInteractions(orderService);
    }

    @Test
    void getOrderById_shouldReturnNotFoundWhenOrderDoesNotExist() throws Exception {
        when(orderService.findById(eq(ORDER_ID))).thenThrow(new OrderNotFoundException(ORDER_ID));

        mockMvc.perform(get(ORDER_BY_ID_URL, ORDER_ID)
                        .with(user("john").roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Order not found: 99999999-9999-9999-9999-999999999999"))
                .andExpect(jsonPath("$.errors").value(nullValue()))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(orderService).findById(eq(ORDER_ID));
        verifyNoMoreInteractions(orderService);
    }

    @Test
    void getOrderById_shouldReturnBadRequestWhenPathVariableIsInvalidUuid() throws Exception {
        mockMvc.perform(get(ORDER_BY_ID_URL, "not-a-uuid")
                        .with(user("john").roles("USER")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                .andExpect(jsonPath("$.message").value("Invalid request parameter: id"))
                .andExpect(jsonPath("$.errors.parameter").value("id"))
                .andExpect(jsonPath("$.timestamp").exists());

        verifyNoInteractions(orderService);
    }

    private OrderDetailedResponseDTO sampleDetailedOrder(UUID id) {
        return new OrderDetailedResponseDTO(
                id,
                OrderStatus.PAID,
                new BigDecimal("199.98"),
                CREATED_AT,
                "john@example.com",
                List.of(sampleOrderItem()));
    }

    private OrderDetailedResponseDTO sampleDetailedOrderWithoutItems(UUID id) {
        return new OrderDetailedResponseDTO(
                id,
                OrderStatus.PAID,
                new BigDecimal("199.98"),
                CREATED_AT,
                "john@example.com",
                List.of());
    }

    private OrderItemResponseDTO sampleOrderItem() {
        return new OrderItemResponseDTO(
                PRODUCT_ID,
                "Test Product",
                "SKU-TEST-001",
                2,
                new BigDecimal("99.99"),
                new BigDecimal("199.98"));
    }
}