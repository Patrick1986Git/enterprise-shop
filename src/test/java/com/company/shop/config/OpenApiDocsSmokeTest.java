package com.company.shop.config;

import static com.company.shop.config.OpenApiGroupsConfig.ADMIN_API_GROUP;
import static com.company.shop.config.OpenApiGroupsConfig.ALL_API_GROUP;
import static com.company.shop.config.OpenApiGroupsConfig.CUSTOMER_API_GROUP;
import static com.company.shop.config.OpenApiGroupsConfig.PUBLIC_API_GROUP;
import static com.company.shop.config.OpenApiGroupsConfig.SYSTEM_API_GROUP;
import static com.company.shop.config.OpenApiGroupsConfig.WEBHOOKS_API_GROUP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.company.shop.module.cart.service.CartService;
import com.company.shop.module.category.service.CategoryService;
import com.company.shop.module.notification.delivery.NotificationDeliveryProcessor;
import com.company.shop.module.notification.outbox.OrderPlacedNotificationHandler;
import com.company.shop.module.notification.service.NotificationAdminActionLogQueryService;
import com.company.shop.module.notification.service.NotificationAdminCommandService;
import com.company.shop.module.notification.service.NotificationQueryService;
import com.company.shop.module.notification.service.NotificationService;
import com.company.shop.module.order.outbox.OrderOutboxEventRecorder;
import com.company.shop.module.order.outbox.OutboxEventAdminActionLogQueryService;
import com.company.shop.module.order.outbox.OutboxEventAdminCommandService;
import com.company.shop.module.order.outbox.OutboxEventFailureRecorder;
import com.company.shop.module.order.outbox.OutboxEventProcessor;
import com.company.shop.module.order.outbox.OutboxEventQueryService;
import com.company.shop.module.order.outbox.OutboxEventTransactionalWorker;
import com.company.shop.module.order.service.OrderService;
import com.company.shop.module.order.expiration.ReservationExpirationRecoveryService;
import com.company.shop.module.order.service.PaymentService;
import com.company.shop.module.order.service.checkout.OrderCheckoutProcessor;
import com.company.shop.module.order.service.StripeWebhookEventRegistrar;
import com.company.shop.module.order.service.query.OrderQueryProcessor;
import com.company.shop.module.product.api.internal.ProductCatalogFacade;
import com.company.shop.module.product.service.ProductReviewService;
import com.company.shop.module.product.service.ProductService;
import com.company.shop.module.system.service.ApplicationStatusService;
import com.company.shop.module.user.repository.RoleRepository;
import com.company.shop.module.user.service.UserService;
import com.company.shop.security.AuthService;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.UserRolesStartupValidator;
import com.company.shop.security.jwt.JwtTokenProvider;
import io.swagger.v3.core.util.Yaml;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
        }
)
@AutoConfigureMockMvc
class OpenApiDocsSmokeTest {

    private static final String API_DOCS_ENDPOINT = "/api-docs";
    private static final Path OPENAPI_OUTPUT_DIRECTORY = Path.of("target", "generated-docs", "openapi");
    private static final Path SITE_OUTPUT_DIRECTORY = Path.of("target", "generated-docs", "site");
    private static final List<String> OPENAPI_GROUPS = List.of(
            ALL_API_GROUP,
            PUBLIC_API_GROUP,
            CUSTOMER_API_GROUP,
            ADMIN_API_GROUP,
            WEBHOOKS_API_GROUP,
            SYSTEM_API_GROUP);
    private static final Map<String, String> OPENAPI_SITE_SECTIONS = new LinkedHashMap<>();

    static {
        OPENAPI_SITE_SECTIONS.put("Default", "openapi");
        OPENAPI_SITE_SECTIONS.put("All API", ALL_API_GROUP);
        OPENAPI_SITE_SECTIONS.put("Public API", PUBLIC_API_GROUP);
        OPENAPI_SITE_SECTIONS.put("Customer API", CUSTOMER_API_GROUP);
        OPENAPI_SITE_SECTIONS.put("Admin API", ADMIN_API_GROUP);
        OPENAPI_SITE_SECTIONS.put("Webhooks API", WEBHOOKS_API_GROUP);
        OPENAPI_SITE_SECTIONS.put("System API", SYSTEM_API_GROUP);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @MockitoBean
    private RoleRepository roleRepository;

    @MockitoBean
    private UserRolesStartupValidator userRolesStartupValidator;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private ApplicationStatusService applicationStatusService;

    @MockitoBean
    private CategoryService categoryService;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private ProductCatalogFacade productCatalogFacade;

    @MockitoBean
    private ProductReviewService productReviewService;

    @MockitoBean
    private CartService cartService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private NotificationQueryService notificationQueryService;

    @MockitoBean
    private NotificationAdminCommandService notificationAdminCommandService;

    @MockitoBean
    private NotificationAdminActionLogQueryService notificationAdminActionLogQueryService;

    @MockitoBean
    private OrderPlacedNotificationHandler orderPlacedNotificationHandler;

    @MockitoBean
    private NotificationDeliveryProcessor notificationDeliveryProcessor;

    @MockitoBean
    private OrderService orderService;
    @MockitoBean
    private ReservationExpirationRecoveryService reservationExpirationRecoveryService;

    @MockitoBean
    private OrderCheckoutProcessor orderCheckoutProcessor;

    @MockitoBean
    private OrderQueryProcessor orderQueryProcessor;

    @MockitoBean
    private OrderOutboxEventRecorder orderOutboxEventRecorder;

    @MockitoBean
    private OutboxEventProcessor outboxEventProcessor;

    @MockitoBean
    private OutboxEventTransactionalWorker outboxEventTransactionalWorker;

    @MockitoBean
    private OutboxEventFailureRecorder outboxEventFailureRecorder;

    @MockitoBean
    private OutboxEventQueryService outboxEventQueryService;

    @MockitoBean
    private OutboxEventAdminCommandService outboxEventAdminCommandService;

    @MockitoBean
    private OutboxEventAdminActionLogQueryService outboxEventAdminActionLogQueryService;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private StripeWebhookEventRegistrar stripeWebhookEventRegistrar;

    @MockitoBean(name = "jpaMappingContext")
    private JpaMetamodelMappingContext jpaMappingContext;

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
                        "/api/v1/webhooks/stripe",
                        "/api/v1/admin/notifications",
                        "/api/v1/admin/notifications/summary",
                        "/api/v1/admin/outbox-events",
                        "/api/v1/admin/outbox-events/{id}",
                        "/api/v1/admin/outbox-events/{id}/requeue",
                        "/api/v1/admin/outbox-events/{id}/actions",
                        "/api/v1/admin/outbox-event-actions",
                        "/api/v1/admin/outbox-events/summary",
                        "/api/v1/admin/notifications/{id}/requeue",
                        "/api/v1/admin/notifications/{id}/actions",
                        "/api/v1/admin/notification-actions");

        assertThat(pathKeys)
                .as("Generated OpenAPI path keys: %s", pathKeys)
                .anyMatch(path -> path.matches("^/api/v1/admin/products/\\{[^/]+\\}$"));

        assertThat(pathKeys)
                .as("Generated OpenAPI path keys: %s", pathKeys)
                .anyMatch(path -> path.matches("^/api/v1/admin/categories/\\{[^/]+\\}$"));
    }

    @Test
    void groupedOpenApiDocs_shouldBePublicForEachConfiguredGroup() throws Exception {
        for (String group : OPENAPI_GROUPS) {
            mockMvc.perform(get(API_DOCS_ENDPOINT + "/{group}", group))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", containsString("application/json")))
                    .andExpect(jsonPath("$.openapi").isNotEmpty())
                    .andExpect(jsonPath("$.paths").exists());
        }
    }

    @Test
    void groupedOpenApiDocs_shouldExposeRepresentativePathsForConfiguredAudiences() throws Exception {
        Map<String, Object> adminPaths = paths(readOpenApi(API_DOCS_ENDPOINT + "/" + ADMIN_API_GROUP));
        assertThat(adminPaths)
                .containsKeys("/api/v1/admin/notifications", "/api/v1/admin/outbox-events")
                .doesNotContainKeys("/api/v1/products", "/api/v1/auth/login");

        Map<String, Object> webhookPaths = paths(readOpenApi(API_DOCS_ENDPOINT + "/" + WEBHOOKS_API_GROUP));
        assertThat(webhookPaths).containsOnlyKeys("/api/v1/webhooks/stripe");

        Map<String, Object> systemPaths = paths(readOpenApi(API_DOCS_ENDPOINT + "/" + SYSTEM_API_GROUP));
        assertThat(systemPaths).containsKey("/api/v1/system/status");

        Map<String, Object> publicPaths = paths(readOpenApi(API_DOCS_ENDPOINT + "/" + PUBLIC_API_GROUP));
        assertThat(publicPaths)
                .containsKeys("/api/v1/auth/login", "/api/v1/products", "/api/v1/categories", "/api/v1/system/status")
                .doesNotContainKeys("/api/v1/admin/notifications", "/api/v1/me", "/api/v1/webhooks/stripe");

        Map<String, Object> customerPaths = paths(readOpenApi(API_DOCS_ENDPOINT + "/" + CUSTOMER_API_GROUP));
        assertThat(customerPaths)
                .containsKeys("/api/v1/me", "/api/v1/me/cart", "/api/v1/reviews")
                .doesNotContainKeys("/api/v1/products", "/api/v1/admin/notifications");
    }

    @Test
    void groupedOpenApiDocs_shouldRetainSharedComponents() throws Exception {
        Map<String, Object> openApi = readOpenApi(API_DOCS_ENDPOINT + "/" + ALL_API_GROUP);
        Map<String, Object> components = objectMapper.convertValue(
                openApi.get("components"),
                new TypeReference<>() {
                });
        Map<String, Object> schemas = objectMapper.convertValue(
                components.get("schemas"),
                new TypeReference<>() {
                });
        Map<String, Object> securitySchemes = objectMapper.convertValue(
                components.get("securitySchemes"),
                new TypeReference<>() {
                });

        assertThat(schemas).containsKey("ApiError");
        assertThat(securitySchemes).containsKey("bearerAuth");
    }

    @Test
    void openApiDocs_shouldContainReusableApiErrorComponents() throws Exception {
        MvcResult result = mockMvc.perform(get(API_DOCS_ENDPOINT))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> openApi = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<>() {
                });
        Map<String, Object> components = objectMapper.convertValue(
                openApi.get("components"),
                new TypeReference<>() {
                });
        Map<String, Object> schemas = objectMapper.convertValue(
                components.get("schemas"),
                new TypeReference<>() {
                });
        Map<String, Object> apiErrorSchema = objectMapper.convertValue(
                schemas.get("ApiError"),
                new TypeReference<>() {
                });
        Map<String, Object> properties = objectMapper.convertValue(
                apiErrorSchema.get("properties"),
                new TypeReference<>() {
                });

        assertThat(apiErrorSchema.get("description")).isEqualTo("Standard API error response.");
        assertThat(properties).containsKeys("status", "message", "errorCode", "errors", "timestamp");

        Map<String, Object> responses = objectMapper.convertValue(
                components.get("responses"),
                new TypeReference<>() {
                });

        List.of(
                "BadRequestError",
                "UnauthorizedError",
                "ForbiddenError",
                "NotFoundError",
                "ConflictError",
                "InternalServerError")
                .forEach(responseName -> assertApiErrorResponseComponent(responses, responseName));
    }

    @Test
    void openApiDocs_shouldExposeStableUniqueOperationIds() throws Exception {
        Map<String, Object> paths = paths(readOpenApi(API_DOCS_ENDPOINT));

        List<String> operationIds = paths.values().stream()
                .map(pathItem -> objectMapper.convertValue(pathItem, new TypeReference<Map<String, Object>>() {
                }))
                .flatMap(pathItem -> pathItem.values().stream())
                .map(operation -> objectMapper.convertValue(operation, new TypeReference<Map<String, Object>>() {
                }))
                .map(operation -> (String) operation.get("operationId"))
                .filter(Objects::nonNull)
                .toList();

        assertThat(operationIds)
                .as("Every OpenAPI operation should have a stable operationId.")
                .allSatisfy(operationId -> assertThat(operationId).isNotBlank());
        assertThat(operationIds)
                .as("Operation IDs should be unique for client generation.")
                .doesNotHaveDuplicates();

        int operationCount = paths.values().stream()
                .map(pathItem -> objectMapper.convertValue(pathItem, new TypeReference<Map<String, Object>>() {
                }))
                .mapToInt(pathItem -> pathItem.size())
                .sum();
        assertThat(operationIds).hasSize(operationCount);
    }

    @Test
    void openApiDocs_shouldDocumentRepresentativeEndpointSecurityAndErrorReferences() throws Exception {
        Map<String, Object> paths = paths(readOpenApi(API_DOCS_ENDPOINT));

        assertThat(operation(paths, "/api/v1/me/cart", "get").get("security").toString())
                .as("Authenticated cart endpoint should document bearerAuth.")
                .contains("bearerAuth");
        assertThat(operation(paths, "/api/v1/admin/notifications", "get").get("security").toString())
                .as("Admin notification endpoint should document bearerAuth.")
                .contains("bearerAuth");

        assertResponseRef(paths, "/api/v1/auth/login", "post", "401", "#/components/responses/UnauthorizedError");
        assertResponseRef(paths, "/api/v1/admin/notifications", "get", "403", "#/components/responses/ForbiddenError");
        assertResponseRef(paths, "/api/v1/products/slug/{slug}", "get", "404", "#/components/responses/NotFoundError");
    }

    @Test
    void openApiDocs_shouldDocumentRepresentativeParameters() throws Exception {
        Map<String, Object> paths = paths(readOpenApi(API_DOCS_ENDPOINT));

        assertParameterDescription(
                paths,
                "/api/v1/products",
                "get",
                "page",
                "Zero-based page index.");
        assertParameterDescription(
                paths,
                "/api/v1/admin/outbox-event-actions",
                "get",
                "outboxEventId",
                "Filter action logs for a specific outbox event identifier.");
    }

    private Map<String, Object> readOpenApi(String endpoint) throws Exception {
        MvcResult result = mockMvc.perform(get(endpoint))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<>() {
                });
    }

    private Map<String, Object> paths(Map<String, Object> openApi) {
        return objectMapper.convertValue(
                openApi.get("paths"),
                new TypeReference<>() {
                });
    }

    private Map<String, Object> operation(Map<String, Object> paths, String path, String method) {
        Map<String, Object> pathItem = objectMapper.convertValue(
                paths.get(path),
                new TypeReference<>() {
                });

        return objectMapper.convertValue(
                pathItem.get(method),
                new TypeReference<>() {
                });
    }

    private void assertResponseRef(
            Map<String, Object> paths,
            String path,
            String method,
            String responseCode,
            String expectedRef) {
        Map<String, Object> operation = operation(paths, path, method);
        Map<String, Object> responses = objectMapper.convertValue(
                operation.get("responses"),
                new TypeReference<>() {
                });
        Map<String, Object> response = objectMapper.convertValue(
                responses.get(responseCode),
                new TypeReference<>() {
                });

        assertThat(response).containsEntry("$ref", expectedRef);
    }

    private void assertParameterDescription(
            Map<String, Object> paths,
            String path,
            String method,
            String parameterName,
            String expectedDescription) {
        Map<String, Object> operation = operation(paths, path, method);
        List<Map<String, Object>> parameters = objectMapper.convertValue(
                operation.get("parameters"),
                new TypeReference<>() {
                });

        assertThat(parameters)
                .filteredOn(parameter -> parameterName.equals(parameter.get("name")))
                .singleElement()
                .extracting(parameter -> parameter.get("description"))
                .isEqualTo(expectedDescription);
    }

    @Test
    void openApiDocsArtifacts_shouldBeGeneratedForDefaultAndGroupedDocs() throws Exception {
        Files.createDirectories(OPENAPI_OUTPUT_DIRECTORY);
        Files.createDirectories(SITE_OUTPUT_DIRECTORY);

        writeOpenApiArtifacts("openapi", readOpenApi(API_DOCS_ENDPOINT));
        for (String group : OPENAPI_GROUPS) {
            writeOpenApiArtifacts(group, readOpenApi(API_DOCS_ENDPOINT + "/" + group));
        }

        assertGeneratedOpenApiArtifactPair("openapi");
        OPENAPI_GROUPS.forEach(group -> assertGeneratedOpenApiArtifactPair(group));

        writeStaticApiDocsSite();

        assertStaticApiDocsSite();
    }

    private void assertApiErrorResponseComponent(Map<String, Object> responses, String responseName) {
        Map<String, Object> response = objectMapper.convertValue(
                responses.get(responseName),
                new TypeReference<>() {
                });
        Map<String, Object> content = objectMapper.convertValue(
                response.get("content"),
                new TypeReference<>() {
                });
        Map<String, Object> jsonContent = objectMapper.convertValue(
                content.get("application/json"),
                new TypeReference<>() {
                });
        Map<String, Object> schema = objectMapper.convertValue(
                jsonContent.get("schema"),
                new TypeReference<>() {
                });

        assertThat(response.get("description")).isInstanceOf(String.class);
        assertThat(content).containsKey("application/json");
        assertThat(schema).containsEntry("$ref", "#/components/schemas/ApiError");
    }

    private void writeStaticApiDocsSite() throws Exception {
        for (String fileName : OPENAPI_SITE_SECTIONS.values()) {
            copyOpenApiArtifactToSite(fileName + ".json");
            copyOpenApiArtifactToSite(fileName + ".yaml");
        }

        Files.writeString(
                SITE_OUTPUT_DIRECTORY.resolve("index.html"),
                buildStaticApiDocsIndex(),
                StandardCharsets.UTF_8);
    }

    private void copyOpenApiArtifactToSite(String fileName) throws Exception {
        Files.copy(
                OPENAPI_OUTPUT_DIRECTORY.resolve(fileName),
                SITE_OUTPUT_DIRECTORY.resolve(fileName),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private String buildStaticApiDocsIndex() {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html>\n")
                .append("<html lang=\"en\">\n")
                .append("<head>\n")
                .append("  <meta charset=\"utf-8\">\n")
                .append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("  <title>Enterprise Shop API Documentation</title>\n")
                .append("</head>\n")
                .append("<body>\n")
                .append("  <main>\n")
                .append("    <h1>Enterprise Shop API Documentation</h1>\n")
                .append("    <p>Download or browse the generated OpenAPI specifications.</p>\n");

        OPENAPI_SITE_SECTIONS.forEach((label, fileName) -> html
                .append("    <section>\n")
                .append("      <h2>").append(label).append("</h2>\n")
                .append("      <ul>\n")
                .append("        <li><a href=\"").append(fileName).append(".json\">")
                .append(fileName).append(".json</a></li>\n")
                .append("        <li><a href=\"").append(fileName).append(".yaml\">")
                .append(fileName).append(".yaml</a></li>\n")
                .append("      </ul>\n")
                .append("    </section>\n"));

        return html.append("  </main>\n")
                .append("</body>\n")
                .append("</html>\n")
                .toString();
    }

    private void assertStaticApiDocsSite() {
        Path indexPath = SITE_OUTPUT_DIRECTORY.resolve("index.html");

        assertThat(indexPath).exists().isRegularFile();
        assertThat(readFileSize(indexPath)).isPositive();

        String index = readString(indexPath);
        OPENAPI_SITE_SECTIONS.values().forEach(fileName -> {
            Path jsonPath = SITE_OUTPUT_DIRECTORY.resolve(fileName + ".json");
            Path yamlPath = SITE_OUTPUT_DIRECTORY.resolve(fileName + ".yaml");

            assertThat(jsonPath).exists().isRegularFile();
            assertThat(yamlPath).exists().isRegularFile();
            assertThat(readFileSize(jsonPath)).isPositive();
            assertThat(readFileSize(yamlPath)).isPositive();
            assertThat(index).contains("href=\"" + fileName + ".json\"");
            assertThat(index).contains("href=\"" + fileName + ".yaml\"");
        });
    }

    private void writeOpenApiArtifacts(String fileName, Map<String, Object> openApi) throws Exception {
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(openApi);
        String yaml = Yaml.pretty(openApi);

        Files.writeString(OPENAPI_OUTPUT_DIRECTORY.resolve(fileName + ".json"), json, StandardCharsets.UTF_8);
        Files.writeString(OPENAPI_OUTPUT_DIRECTORY.resolve(fileName + ".yaml"), yaml, StandardCharsets.UTF_8);
    }

    private void assertGeneratedOpenApiArtifactPair(String fileName) {
        Path jsonPath = OPENAPI_OUTPUT_DIRECTORY.resolve(fileName + ".json");
        Path yamlPath = OPENAPI_OUTPUT_DIRECTORY.resolve(fileName + ".yaml");

        assertThat(jsonPath).exists().isRegularFile();
        assertThat(yamlPath).exists().isRegularFile();
        assertThat(readFileSize(jsonPath)).isPositive();
        assertThat(readFileSize(yamlPath)).isPositive();

        assertThat(readJsonArtifact(jsonPath)).containsKey("openapi");
        assertThat(readYamlArtifact(yamlPath)).containsKey("openapi");
    }

    private long readFileSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception ex) {
            throw new AssertionError("Failed to read generated OpenAPI artifact size: " + path, ex);
        }
    }

    private String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new AssertionError("Failed to read generated documentation artifact: " + path, ex);
        }
    }

    private Map<String, Object> readJsonArtifact(Path path) {
        try {
            return objectMapper.readValue(readString(path), new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new AssertionError("Generated OpenAPI JSON artifact is not parseable: " + path, ex);
        }
    }

    private Map<String, Object> readYamlArtifact(Path path) {
        try {
            Map<?, ?> yaml = Yaml.mapper().readValue(readString(path), Map.class);
            return objectMapper.convertValue(yaml, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new AssertionError("Generated OpenAPI YAML artifact is not parseable: " + path, ex);
        }
    }

}
