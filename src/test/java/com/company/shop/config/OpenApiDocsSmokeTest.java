package com.company.shop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.company.shop.module.cart.service.CartService;
import com.company.shop.module.category.service.CategoryService;
import com.company.shop.module.order.service.OrderService;
import com.company.shop.module.order.service.PaymentService;
import com.company.shop.module.order.service.StripeWebhookEventRegistrar;
import com.company.shop.module.product.service.ProductReviewService;
import com.company.shop.module.product.service.ProductService;
import com.company.shop.module.system.service.ApplicationStatusService;
import com.company.shop.module.user.repository.RoleRepository;
import com.company.shop.module.user.service.UserService;
import com.company.shop.security.AuthService;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.UserRolesStartupValidator;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
        }
)
@Import(OpenApiDocsSmokeTest.OpenApiDocsSmokeTestConfiguration.class)
@AutoConfigureMockMvc
class OpenApiDocsSmokeTest {

    private static final String API_DOCS_ENDPOINT = "/api-docs";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private RoleRepository roleRepository;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.validate(anyString())).thenReturn(false);
        when(roleRepository.existsByName(anyString())).thenReturn(true);
    }

    @Test
    void openApiDocs_shouldBePublicAndContainCorePaths() throws Exception {
        MvcResult result = mockMvc.perform(get(API_DOCS_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/json")))
                .andExpect(jsonPath("$.openapi").isNotEmpty())
                .andExpect(jsonPath("$.paths").exists())
                .andReturn();

        Map<String, Object> openApi = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<>() {
                });
        Map<String, Object> paths = objectMapper.convertValue(
                openApi.get("paths"),
                new TypeReference<>() {
                });
        Set<String> pathKeys = paths.keySet();

        assertThat(paths)
                .as("Generated OpenAPI path keys: %s", pathKeys)
                .containsKeys(
                        "/api/v1/auth/login",
                        "/api/v1/products",
                        "/api/v1/me",
                        "/api/v1/webhooks/stripe");

        assertThat(pathKeys)
                .as("Generated OpenAPI path keys: %s", pathKeys)
                .anyMatch(path -> path.matches("^/api/v1/admin/products/\\{[^/]+\\}$"));

        assertThat(pathKeys)
                .as("Generated OpenAPI path keys: %s", pathKeys)
                .anyMatch(path -> path.matches("^/api/v1/admin/categories/\\{[^/]+\\}$"));
    }

    @TestConfiguration
    static class OpenApiDocsSmokeTestConfiguration {

        @Bean
        @Primary
        JwtTokenProvider jwtTokenProvider() {
            return mock(JwtTokenProvider.class);
        }

        @Bean
        @Primary
        UserDetailsServiceImpl userDetailsService() {
            return mock(UserDetailsServiceImpl.class);
        }

        @Bean
        @Primary
        RoleRepository roleRepository() {
            return mock(RoleRepository.class);
        }

        @Bean
        @Primary
        UserRolesStartupValidator userRolesStartupValidator() {
            return mock(UserRolesStartupValidator.class);
        }

        @Bean
        @Primary
        AuthService authService() {
            return mock(AuthService.class);
        }

        @Bean
        @Primary
        ApplicationStatusService applicationStatusService() {
            return mock(ApplicationStatusService.class);
        }

        @Bean
        @Primary
        CategoryService categoryService() {
            return mock(CategoryService.class);
        }

        @Bean
        @Primary
        ProductService productService() {
            return mock(ProductService.class);
        }

        @Bean
        @Primary
        ProductReviewService productReviewService() {
            return mock(ProductReviewService.class);
        }

        @Bean
        @Primary
        CartService cartService() {
            return mock(CartService.class);
        }

        @Bean
        @Primary
        UserService userService() {
            return mock(UserService.class);
        }

        @Bean
        @Primary
        OrderService orderService() {
            return mock(OrderService.class);
        }

        @Bean
        @Primary
        PaymentService paymentService() {
            return mock(PaymentService.class);
        }

        @Bean
        @Primary
        StripeWebhookEventRegistrar stripeWebhookEventRegistrar() {
            return mock(StripeWebhookEventRegistrar.class);
        }

    }
}
