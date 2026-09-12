package com.company.shop.module.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.company.shop.module.order.exception.StripePaymentConflictNotFoundException;
import com.company.shop.module.order.exception.StripePaymentConflictSortInvalidException;
import com.company.shop.module.order.service.StripePaymentConflictQueryService;
import com.company.shop.module.order.service.StripePaymentConflictDispositionCommandService;
import com.company.shop.module.order.service.StripePaymentConflictDispositionQueryService;
import com.company.shop.module.order.dto.StripePaymentConflictDispositionResponseDTO;
import com.company.shop.module.order.entity.StripePaymentConflictDispositionType;
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
    @MockitoBean StripePaymentConflictDispositionCommandService dispositionCommandService;
    @MockitoBean StripePaymentConflictDispositionQueryService dispositionQueryService;
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

    @Test
    void findById_shouldRequireAdminAndReturnOnlySanitizedEvidence() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000000202");
        UUID paymentId = UUID.fromString("00000000-0000-0000-0000-000000000203");
        var dto = new StripePaymentConflictResponseDTO(id, orderId, paymentId, "evt_detail", "pi_detail",
                "payment_intent.succeeded", OrderStatus.CANCELLED, PaymentStatus.FAILED,
                StripePaymentConflictReason.TERMINAL_STATE_CONTRADICTION, Instant.parse("2026-09-12T00:00:00Z"));
        when(queryService.findById(id)).thenReturn(dto);
        String detailUrl = URL + "/" + id;

        mockMvc.perform(get(detailUrl)).andExpect(status().isForbidden());
        mockMvc.perform(get(detailUrl).with(user("user").roles("USER"))).andExpect(status().isForbidden());
        mockMvc.perform(get(detailUrl).with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.stripeEventId").value("evt_detail"))
                .andExpect(jsonPath("$.providerPaymentId").value("pi_detail"))
                .andExpect(jsonPath("$.eventType").value("payment_intent.succeeded"))
                .andExpect(jsonPath("$.orderStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.paymentStatus").value("FAILED"))
                .andExpect(jsonPath("$.reason").value("TERMINAL_STATE_CONTRADICTION"))
                .andExpect(jsonPath("$.observedAt").value("2026-09-12T00:00:00Z"))
                .andExpect(jsonPath("$.rawPayload").doesNotExist())
                .andExpect(jsonPath("$.stripeSignature").doesNotExist())
                .andExpect(jsonPath("$.clientSecret").doesNotExist())
                .andExpect(jsonPath("$.jwt").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void findById_shouldReturnSanitizedNotFoundContract() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000204");
        when(queryService.findById(id)).thenThrow(new StripePaymentConflictNotFoundException(id));

        mockMvc.perform(get(URL + "/" + id).with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("STRIPE_PAYMENT_CONFLICT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Stripe payment conflict not found: " + id));
    }

    @Test
    void findAll_shouldReturnBadRequestForInvalidSort() throws Exception {
        when(queryService.findAll(any(Pageable.class)))
                .thenThrow(new StripePaymentConflictSortInvalidException("unsafe"));

        mockMvc.perform(get(URL).queryParam("sort", "unsafe,asc").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("STRIPE_PAYMENT_CONFLICT_SORT_INVALID"))
                .andExpect(jsonPath("$.message").value("Unsupported conflict sort: unsafe"));
    }

    @Test
    void appendDisposition_shouldRequireAdminAndExposeNoActorInput() throws Exception {
        UUID id = UUID.randomUUID();
        var response = new StripePaymentConflictDispositionResponseDTO(UUID.randomUUID(), id,
                StripePaymentConflictDispositionType.ACKNOWLEDGED, "admin@example.com",
                Instant.parse("2026-09-12T01:00:00Z"));
        when(dispositionCommandService.append(id, StripePaymentConflictDispositionType.ACKNOWLEDGED))
                .thenReturn(response);
        String body = "{\"actionType\":\"ACKNOWLEDGED\",\"actorEmail\":\"attacker@example.com\"}";

        mockMvc.perform(post(URL + "/" + id + "/dispositions").with(csrf())
                .contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(URL + "/" + id + "/dispositions").with(csrf()).with(user("user").roles("USER"))
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mockMvc.perform(post(URL + "/" + id + "/dispositions").with(csrf())
                .with(user("authenticated-admin").roles("ADMIN"))
                .contentType("application/json").content(body)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.actorEmail").value("admin@example.com"))
                .andExpect(jsonPath("$.actionType").value("ACKNOWLEDGED"));
    }

    @Test
    void appendDisposition_shouldRejectMissingOrInvalidAction() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post(URL + "/" + id + "/dispositions").with(csrf()).with(user("admin").roles("ADMIN"))
                .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(URL + "/" + id + "/dispositions").with(csrf()).with(user("admin").roles("ADMIN"))
                .contentType("application/json").content("{\"actionType\":\"RESOLVED\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(dispositionCommandService);
    }

    @Test
    void findDispositions_shouldRequireAdminAndReturnBoundedHistory() throws Exception {
        UUID id = UUID.randomUUID();
        var response = new StripePaymentConflictDispositionResponseDTO(UUID.randomUUID(), id,
                StripePaymentConflictDispositionType.ESCALATED, "admin@example.com", Instant.now());
        when(dispositionQueryService.findByConflictId(eq(id), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(response)));

        mockMvc.perform(get(URL + "/" + id + "/dispositions")).andExpect(status().isForbidden());
        mockMvc.perform(get(URL + "/" + id + "/dispositions").with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(URL + "/" + id + "/dispositions?page=1&size=5")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actionType").value("ESCALATED"));
    }
}
