package com.company.shop.module.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.company.shop.module.order.dto.StripePaymentConflictResponseDTO;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.entity.StripePaymentConflictReason;
import com.company.shop.module.order.service.StripePaymentConflictQueryService;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.company.shop.support.WebMvcSliceTestConfig;

@WebMvcTest(controllers = AdminStripePaymentConflictController.class)
@ActiveProfiles("test")
@Import(WebMvcSliceTestConfig.class)
class AdminStripePaymentConflictControllerWebMvcTest {
    private static final String URL = "/api/v1/admin/orders/stripe-payment-conflicts";
    @Autowired MockMvc mockMvc;
    @MockitoBean StripePaymentConflictQueryService queryService;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean UserDetailsServiceImpl userDetailsService;

    @BeforeEach void setUp() { when(jwtTokenProvider.validate(anyString())).thenReturn(false); }

    @Test
    void findAll_shouldRequireAdminAndReturnSanitizedEvidence() throws Exception {
        UUID id = UUID.randomUUID();
        var dto = new StripePaymentConflictResponseDTO(id, UUID.randomUUID(), UUID.randomUUID(), "evt_1", "pi_1",
                "payment_intent.succeeded", OrderStatus.CANCELLED, PaymentStatus.FAILED,
                StripePaymentConflictReason.TERMINAL_STATE_CONTRADICTION, Instant.parse("2026-09-12T00:00:00Z"));
        when(queryService.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(dto)));

        mockMvc.perform(get(URL)).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).with(user("user").roles("USER"))).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.content[0].reason").value("TERMINAL_STATE_CONTRADICTION"))
                .andExpect(jsonPath("$.content[0].rawPayload").doesNotExist());
    }

    @Test
    void findAll_shouldNotQueryForAnonymousRequest() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isForbidden());
        verifyNoInteractions(queryService);
    }
}
