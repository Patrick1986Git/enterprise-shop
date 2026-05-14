package com.company.shop.common.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.common.exception.BusinessException;
import com.company.shop.common.exception.GlobalExceptionHandler;
import com.company.shop.config.SecurityConfig;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.company.shop.support.TestMeterRegistryConfig;

@WebMvcTest(controllers = RequestIdFilterErrorWebMvcTest.TestErrorController.class)
@Import({ SecurityConfig.class, JwtAuthenticationFilter.class, RequestIdFilter.class, GlobalExceptionHandler.class,
        RequestIdFilterErrorWebMvcTest.TestErrorController.class, TestMeterRegistryConfig.class })
class RequestIdFilterErrorWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.validate(anyString())).thenReturn(false);
    }

    @Test
    void errorResponse_shouldKeepIncomingRequestIdInResponseHeader() throws Exception {
        mockMvc.perform(get("/test-request-id-error/fail")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "incoming-error-request-id")
                        .with(user("test-user").roles("USER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, "incoming-error-request-id"));
    }

    @RestController
    @RequestMapping("/test-request-id-error")
    static class TestErrorController {

        @GetMapping("/fail")
        void fail() {
            throw new TestBusinessException(HttpStatus.BAD_REQUEST, "bad request", "REQUEST_INVALID");
        }
    }

    static class TestBusinessException extends BusinessException {
        TestBusinessException(HttpStatus status, String message, String errorCode) {
            super(status, message, errorCode);
        }
    }
}
