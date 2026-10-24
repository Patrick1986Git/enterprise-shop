package com.company.shop.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletException;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();
    private Map<String, String> previousMdc;

    @BeforeEach
    void setUp() {
        previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (previousMdc != null) {
            MDC.setContextMap(previousMdc);
        }
    }

    @Test
    void doFilterInternal_shouldReuseIncomingRequestId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "incoming-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("incoming-123");
    }

    @Test
    void doFilterInternal_shouldGenerateRequestIdWhenHeaderMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertUuid(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER));
    }

    @Test
    void doFilterInternal_shouldGenerateRequestIdWhenHeaderBlank() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertUuid(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER));
    }

    @Test
    void doFilterInternal_shouldGenerateRequestIdWhenHeaderTooLong() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "a".repeat(101));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertUuid(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER));
    }

    @ParameterizedTest
    @ValueSource(strings = { "abc\n123", "abc\r123", "abc\u0000123", "abc\t123", "abc 123", "abc\u007f123", "abc\u0080123" })
    void doFilterInternal_shouldGenerateRequestIdWhenHeaderContainsUnsafeCharacter(String incomingRequestId)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, incomingRequestId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertUuid(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER));
    }

    @Test
    void doFilterInternal_shouldAcceptRequestIdAtLengthLimit() throws ServletException, IOException {
        String requestId = "a".repeat(100);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, requestId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo(requestId);
    }

    @Test
    void doFilterInternal_shouldTrimIncomingRequestId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "  incoming-123  ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("incoming-123");
    }

    @Test
    void doFilterInternal_shouldClearMdcAfterRequest() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> assertThat(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY)).isNotBlank());

        assertThat(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY)).isNull();
    }

    @Test
    void doFilterInternal_shouldClearMdcWhenFilterChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw new ServletException("boom");
        })).isInstanceOf(ServletException.class);

        assertThat(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY)).isNull();
        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isNotBlank();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "outer-123")
    void doFilterInternal_shouldRestoreMdcAfterRequest(String previousRequestId) throws ServletException, IOException {
        MDC.put("operation", "checkout");
        if (previousRequestId != null) {
            MDC.put(RequestIdFilter.REQUEST_ID_MDC_KEY, previousRequestId);
        }
        Map<String, String> expectedMdc = MDC.getCopyOfContextMap();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "incoming-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY)).isEqualTo("incoming-123");
            assertThat(MDC.get("operation")).isEqualTo("checkout");
            assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("incoming-123");
        });

        assertThat(MDC.getCopyOfContextMap()).isEqualTo(expectedMdc);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "outer-123")
    void doFilterInternal_shouldRestoreMdcWhenFilterChainThrows(String previousRequestId) {
        MDC.put("operation", "checkout");
        if (previousRequestId != null) {
            MDC.put(RequestIdFilter.REQUEST_ID_MDC_KEY, previousRequestId);
        }
        Map<String, String> expectedMdc = MDC.getCopyOfContextMap();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "incoming-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletException failure = new ServletException("chain failure");

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY)).isEqualTo("incoming-123");
            throw failure;
        })).isSameAs(failure);

        assertThat(MDC.getCopyOfContextMap()).isEqualTo(expectedMdc);
        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("incoming-123");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "outer-123")
    void doFilterInternal_shouldRestoreMdcWhenResponseHeaderFails(String previousRequestId) {
        MDC.put("operation", "checkout");
        if (previousRequestId != null) {
            MDC.put(RequestIdFilter.REQUEST_ID_MDC_KEY, previousRequestId);
        }
        Map<String, String> expectedMdc = MDC.getCopyOfContextMap();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "incoming-123");
        IllegalStateException failure = new IllegalStateException("header failure");
        MockHttpServletResponse response = new MockHttpServletResponse() {
            @Override
            public void setHeader(String name, String value) {
                assertThat(name).isEqualTo(RequestIdFilter.REQUEST_ID_HEADER);
                assertThat(value).isEqualTo("incoming-123");
                assertThat(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY)).isEqualTo("incoming-123");
                throw failure;
            }
        };
        MockFilterChain filterChain = new MockFilterChain();

        assertThatThrownBy(() -> filter.doFilter(request, response, filterChain)).isSameAs(failure);

        assertThat(MDC.getCopyOfContextMap()).isEqualTo(expectedMdc);
        assertThat(filterChain.getRequest()).isNull();
    }

    @Test
    void doFilterInternal_shouldRestoreOuterRequestIdAfterNestedRequest() throws ServletException, IOException {
        MockHttpServletRequest outerRequest = new MockHttpServletRequest();
        outerRequest.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "outer-123");
        MockHttpServletResponse outerResponse = new MockHttpServletResponse();
        MockHttpServletRequest innerRequest = new MockHttpServletRequest();
        innerRequest.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "inner-456");
        MockHttpServletResponse innerResponse = new MockHttpServletResponse();

        filter.doFilter(outerRequest, outerResponse, (req, res) -> {
            assertThat(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY)).isEqualTo("outer-123");

            filter.doFilter(innerRequest, innerResponse,
                    (innerReq, innerRes) -> assertThat(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY)).isEqualTo("inner-456"));

            assertThat(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY)).isEqualTo("outer-123");
        });

        assertThat(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY)).isNull();
        assertThat(outerResponse.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("outer-123");
        assertThat(innerResponse.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("inner-456");
    }

    @Test
    void doFilterInternal_shouldKeepSequentialRequestsIsolated() throws ServletException, IOException {
        MDC.put("operation", "checkout");
        for (String requestId : new String[] { "first-123", "second-456" }) {
            assertThat(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY)).isNull();
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, requestId);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response,
                    (req, res) -> assertThat(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY)).isEqualTo(requestId));

            assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo(requestId);
            assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("operation", "checkout"));
        }
    }

    private void assertUuid(String value) {
        assertThat(value).isNotBlank();
        assertThat(UUID.fromString(value)).isNotNull();
    }
}
