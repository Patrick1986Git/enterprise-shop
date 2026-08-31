package com.company.shop.module.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.support.WebMvcSliceTestConfig;
import com.company.shop.module.order.dto.OrderResponseDTO;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.service.OrderService;
import com.company.shop.module.order.expiration.ReservationExpirationRecoveryService;
import com.company.shop.module.order.expiration.ReservationExpirationRecoveryResult;
import com.company.shop.module.order.expiration.ReservationExpirationWorkQueryService;
import com.company.shop.module.order.expiration.ReservationExpirationWorkResponseDTO;
import com.company.shop.module.order.expiration.ReservationExpirationWorkStatus;
import com.company.shop.module.order.expiration.ReservationExpirationWorkNotFoundException;
import com.company.shop.module.order.expiration.LegacyReservationAdoptionResult;
import com.company.shop.module.order.expiration.LegacyReservationResponseDTO;
import com.company.shop.module.order.expiration.LegacyReservationService;
import com.company.shop.module.order.expiration.LegacyReservationSortInvalidException;
import java.time.Instant;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.company.shop.security.jwt.JwtTokenProvider;

@WebMvcTest(controllers = AdminOrderController.class)
@ActiveProfiles("test")
@Import(WebMvcSliceTestConfig.class)
class AdminOrderControllerWebMvcTest {

    private static final String ADMIN_ORDERS_URL = "/api/v1/admin/orders";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private ReservationExpirationRecoveryService reservationExpirationRecoveryService;

    @MockitoBean
    private ReservationExpirationWorkQueryService reservationExpirationWorkQueryService;

    @MockitoBean
    private LegacyReservationService legacyReservationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.validate(anyString())).thenReturn(false);
    }

    @Test
    void getOrders_shouldReturnForbiddenForAnonymous() throws Exception {
        mockMvc.perform(get(ADMIN_ORDERS_URL))
                .andExpect(status().isForbidden());

        verifyNoInteractions(orderService);
    }

    @Test
    void legacyReservationOperations_shouldRequireAdminAndReturnSafeContracts() throws Exception {
        UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        UUID workId = UUID.fromString("00000000-0000-0000-0000-000000000112");
        Instant dueAt = Instant.parse("2026-08-31T12:00:00Z");
        var legacy = new LegacyReservationResponseDTO(orderId, OrderStatus.NEW,
                LocalDateTime.of(2025, 1, 1, 0, 0), null,
                com.company.shop.module.order.entity.PaymentStatus.PENDING, true);
        when(legacyReservationService.findUnmanaged(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(legacy)));
        when(legacyReservationService.adopt(orderId)).thenReturn(new LegacyReservationAdoptionResult(
                orderId, workId, dueAt, ReservationExpirationWorkStatus.PENDING, true));

        String discoveryUrl = ADMIN_ORDERS_URL + "/legacy-reservations";
        mockMvc.perform(get(discoveryUrl)).andExpect(status().isForbidden());
        mockMvc.perform(get(discoveryUrl).with(user("user").roles("USER"))).andExpect(status().isForbidden());
        mockMvc.perform(get(discoveryUrl).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.content[0].paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.content[0].providerPaymentAttached").value(true))
                .andExpect(jsonPath("$.content[0].reservationExpiresAt").doesNotExist());

        String adoptionUrl = ADMIN_ORDERS_URL + "/{orderId}/legacy-reservation/adopt";
        mockMvc.perform(post(adoptionUrl, orderId).with(csrf()).with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(adoptionUrl, orderId).with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workId").value(workId.toString()))
                .andExpect(jsonPath("$.adopted").value(true))
                .andExpect(jsonPath("$.workStatus").value("PENDING"));
    }

    @Test
    void getLegacyReservations_shouldReturnBadRequestForUnsupportedSort() throws Exception {
        when(legacyReservationService.findUnmanaged(any(Pageable.class)))
                .thenThrow(new LegacyReservationSortInvalidException("paymentStatus"));

        mockMvc.perform(get(ADMIN_ORDERS_URL + "/legacy-reservations")
                        .with(user("admin").roles("ADMIN"))
                        .param("sort", "paymentStatus,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("LEGACY_RESERVATION_SORT_INVALID"));
    }

    @Test
    void recoverReservationExpirationWork_shouldRequireAdminAndReturnSafeOperationalContract() throws Exception {
        UUID workId = UUID.fromString("00000000-0000-0000-0000-000000000061");
        UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000000062");
        Instant recoveredAt = Instant.parse("2026-08-17T12:00:00Z");
        when(reservationExpirationRecoveryService.recover(workId)).thenReturn(new ReservationExpirationRecoveryResult(
                workId, orderId, ReservationExpirationWorkStatus.PENDING, 10, 1, recoveredAt));

        mockMvc.perform(post(ADMIN_ORDERS_URL + "/reservation-expiration-work/{workId}/recover", workId)
                        .with(csrf())
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workId").value(workId.toString()))
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(10))
                .andExpect(jsonPath("$.recoveryCount").value(1))
                .andExpect(jsonPath("$.lastError").doesNotExist());

        verify(reservationExpirationRecoveryService).recover(workId);
    }

    @Test
    void getReservationExpirationWork_shouldReturnFailedWorkIdAndOperationalStateForAdmin() throws Exception {
        UUID workId = UUID.fromString("00000000-0000-0000-0000-000000000071");
        UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000000072");
        Instant failedAt = Instant.parse("2026-08-29T12:00:00Z");
        ReservationExpirationWorkResponseDTO response = new ReservationExpirationWorkResponseDTO(
                workId, orderId, ReservationExpirationWorkStatus.FAILED, failedAt.minusSeconds(3600), failedAt,
                null, 10, "provider unavailable", null, failedAt, 2, failedAt.minusSeconds(60), "admin@example.com");
        when(reservationExpirationWorkQueryService.findAll(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1));

        mockMvc.perform(get(ADMIN_ORDERS_URL + "/reservation-expiration-work")
                        .with(user("admin").roles("ADMIN"))
                        .param("status", "FAILED")
                        .param("orderId", orderId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(workId.toString()))
                .andExpect(jsonPath("$.content[0].orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.content[0].status").value("FAILED"))
                .andExpect(jsonPath("$.content[0].lastError").value("provider unavailable"))
                .andExpect(jsonPath("$.content[0].attempts").value(10))
                .andExpect(jsonPath("$.content[0].failedAt").value(failedAt.toString()))
                .andExpect(jsonPath("$.content[0].recoveryCount").value(2))
                .andExpect(jsonPath("$.content[0].lastRecoveredBy").value("admin@example.com"))
                .andExpect(jsonPath("$.content[0].claimToken").doesNotExist());

        verify(reservationExpirationWorkQueryService).findAll(
                org.mockito.ArgumentMatchers.eq(ReservationExpirationWorkStatus.FAILED),
                org.mockito.ArgumentMatchers.eq(orderId), any(Pageable.class));
    }

    @Test
    void getReservationExpirationWork_shouldDenyAnonymousAndNonAdmin() throws Exception {
        String url = ADMIN_ORDERS_URL + "/reservation-expiration-work";
        mockMvc.perform(get(url)).andExpect(status().isForbidden());
        mockMvc.perform(get(url).with(user("user").roles("USER"))).andExpect(status().isForbidden());
        verifyNoInteractions(reservationExpirationWorkQueryService);
    }

    @Test
    void getReservationExpirationWorkById_shouldReturnDetailForAdmin() throws Exception {
        UUID workId = UUID.fromString("00000000-0000-0000-0000-000000000073");
        UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000000074");
        when(reservationExpirationWorkQueryService.findById(workId)).thenReturn(
                new ReservationExpirationWorkResponseDTO(workId, orderId, ReservationExpirationWorkStatus.FAILED,
                        Instant.EPOCH, Instant.EPOCH, null, 3, "failed", null, Instant.EPOCH, 0, null, null));

        mockMvc.perform(get(ADMIN_ORDERS_URL + "/reservation-expiration-work/{workId}", workId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(workId.toString()))
                .andExpect(jsonPath("$.claimToken").doesNotExist());
        verify(reservationExpirationWorkQueryService).findById(workId);
    }

    @Test
    void getReservationExpirationWorkById_shouldReturnNotFoundContractWhenMissing() throws Exception {
        UUID workId = UUID.fromString("00000000-0000-0000-0000-000000000075");
        when(reservationExpirationWorkQueryService.findById(workId))
                .thenThrow(new ReservationExpirationWorkNotFoundException(workId));

        mockMvc.perform(get(ADMIN_ORDERS_URL + "/reservation-expiration-work/{workId}", workId)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESERVATION_EXPIRATION_WORK_NOT_FOUND"));
    }

    @Test
    void recoverReservationExpirationWork_shouldRejectNonAdmin() throws Exception {
        mockMvc.perform(post(ADMIN_ORDERS_URL + "/reservation-expiration-work/{workId}/recover", UUID.randomUUID())
                        .with(csrf())
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reservationExpirationRecoveryService);
    }

    @Test
    void getOrders_shouldReturnForbiddenForUserWithoutAdminRole() throws Exception {
        mockMvc.perform(get(ADMIN_ORDERS_URL)
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(orderService);
    }

    @Test
    void getOrders_shouldMapCustomPageableParamsForAdmin() throws Exception {
        when(orderService.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(
                List.of(),
                PageRequest.of(2, 5),
                0));

        mockMvc.perform(get(ADMIN_ORDERS_URL)
                        .with(user("admin").roles("ADMIN"))
                        .param("page", "2")
                        .param("size", "5")
                        .param("sort", "createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.number").value(2))
                .andExpect(jsonPath("$.size").value(5));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderService).findAll(pageableCaptor.capture());
        verifyNoMoreInteractions(orderService);

        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(5);
        assertThat(pageable.getSort().getOrderFor("createdAt")).isNotNull();
        assertThat(pageable.getSort().getOrderFor("createdAt").getDirection().name()).isEqualTo("DESC");
    }

    @Test
    void getOrders_shouldReturnStablePagedContractForAdmin() throws Exception {
        OrderResponseDTO order = new OrderResponseDTO(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                OrderStatus.NEW,
                new BigDecimal("149.99"),
                LocalDateTime.of(2026, 1, 10, 12, 30),
                null);

        when(orderService.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(
                List.of(order),
                PageRequest.of(0, 20),
                1));

        mockMvc.perform(get(ADMIN_ORDERS_URL)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.content[0].status").value("NEW"))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.numberOfElements").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.empty").value(false))
                .andExpect(jsonPath("$.pageable").doesNotExist())
                .andExpect(jsonPath("$.sort").doesNotExist());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderService).findAll(pageableCaptor.capture());
        verifyNoMoreInteractions(orderService);

        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(0);
        assertThat(pageable.getPageSize()).isEqualTo(20);
    }
}
