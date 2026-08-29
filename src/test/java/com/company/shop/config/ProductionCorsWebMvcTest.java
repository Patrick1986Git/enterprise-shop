package com.company.shop.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.CorsFilter;

import com.company.shop.module.system.controller.HomeController;

class ProductionCorsWebMvcTest {

    @Test
    void configuredProductionOrigins_shouldAllowEveryExplicitOriginButNotLocalhost() throws Exception {
        MockMvc mockMvc = mockMvcWithOrigins(List.of("https://shop.example", "https://admin.example"));

        for (String origin : List.of("https://shop.example", "https://admin.example")) {
            mockMvc.perform(options("/api/v1")
                            .header("Origin", origin)
                            .header("Access-Control-Request-Method", "PATCH"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin))
                    .andExpect(header().string("Access-Control-Allow-Methods", containsString("PATCH")));
        }

        mockMvc.perform(options("/api/v1")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void emptyProductionOrigins_shouldFailClosedForCrossOriginRequests() throws Exception {
        mockMvcWithOrigins(List.of()).perform(options("/api/v1")
                        .header("Origin", "https://shop.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    private MockMvc mockMvcWithOrigins(List<String> origins) {
        SecurityConfig securityConfig = new SecurityConfig(null, null, new CorsProperties(origins));
        return MockMvcBuilders.standaloneSetup(new HomeController())
                .addFilters(new CorsFilter(securityConfig.corsConfigurationSource()))
                .build();
    }
}
