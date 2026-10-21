package com.company.shop.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.company.shop.security.jwt.JwtTokenProvider;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.tomcat.threads.max=8", "server.tomcat.max-connections=32", "server.tomcat.accept-count=8"
})
@ActiveProfiles("test")
@Import(StatelessApiClientHttpIT.SessionObservation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class StatelessApiClientHttpIT {

    private static final String DATABASE = "stateless_client_test";
    private static final String MIGRATION = "client_migration";
    private static final String RUNTIME = "client_runtime";
    private static final String OPERATOR = "client_operator";
    private static final String DB_PASSWORD = "synthetic_database_password";
    private static final String PASSWORD = "SyntheticPassword123!";
    private static final String UNTRUSTED_MARKER = "synthetic-untrusted-media-marker";
    private static final String NEW_PASSWORD = "ReplacementPassword456!";
    private static final Map<String, HttpSession> SESSIONS = new ConcurrentHashMap<>();
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName(DATABASE).withUsername("postgres").withPassword(DB_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath("docker/postgres/tsearch_data/polish.dict"), "/usr/local/share/postgresql/tsearch_data/polish.dict")
            .withCopyFileToContainer(MountableFile.forHostPath("docker/postgres/tsearch_data/polish.affix"), "/usr/local/share/postgresql/tsearch_data/polish.affix")
            .withCopyFileToContainer(MountableFile.forHostPath("docker/postgres/tsearch_data/polish.stop"), "/usr/local/share/postgresql/tsearch_data/polish.stop");

    static {
        POSTGRES.start();
        try (Connection connection = connection("postgres"); Statement sql = connection.createStatement()) {
            for (String role : List.of(MIGRATION, RUNTIME, OPERATOR)) {
                sql.execute("CREATE ROLE " + role + " LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '" + DB_PASSWORD + "'");
            }
            sql.execute("ALTER DATABASE " + DATABASE + " OWNER TO " + MIGRATION);
            sql.execute("ALTER SCHEMA public OWNER TO " + MIGRATION);
            sql.execute("GRANT CONNECT ON DATABASE " + DATABASE + " TO " + RUNTIME);
            sql.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME);
            sql.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION + " IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO " + RUNTIME);
            sql.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION + " IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO " + RUNTIME);
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate runtime;
    @Autowired private JwtTokenProvider tokens;
    @Autowired private UserDetailsServiceImpl userDetails;

    private final String administratorEmail = email();
    private final List<String> issuedTokens = new ArrayList<>();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", () -> RUNTIME);
        properties.add("spring.datasource.password", () -> DB_PASSWORD);
        properties.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        properties.add("spring.flyway.user", () -> MIGRATION);
        properties.add("spring.flyway.password", () -> DB_PASSWORD);
    }

    @BeforeAll
    void bootstrapSyntheticAdministratorThroughOfflineOperator() throws Exception {
        register(administratorEmail);
        promote(administratorEmail);
    }

    @AfterEach
    void secretsShouldStayOutOfApplicationLogs(CapturedOutput output) {
        String logs = output.getAll();
        for (String secret : List.of(PASSWORD, NEW_PASSWORD, DB_PASSWORD, UNTRUSTED_MARKER)) {
            assertThat(logs.contains(secret)).isFalse();
        }
        for (String token : issuedTokens) {
            assertThat(logs.contains(token)).isFalse();
        }
    }

    @Test
    void registrationAndLogin_shouldWorkWithoutCsrfAndPreserveValidationAndRoleBoundaries() throws Exception {
        String email = email();
        register(email);
        assertThat(runtime.queryForObject("SELECT count(*) FROM users WHERE email=? AND enabled AND NOT deleted", Long.class, email)).isOne();
        assertThat(runtime.queryForList("SELECT r.name FROM roles r JOIN user_roles ur ON ur.role_id=r.id JOIN users u ON u.id=ur.user_id WHERE u.email=?", String.class, email))
                .containsExactly(SecurityConstants.ROLE_USER);

        String token = login(email, PASSWORD);
        assertThat(tokens.validate(token)).isTrue();
        assertThat(tokens.getUsername(token)).isEqualTo(email);
        assertThat(send("GET", "/api/v1/me", null, token).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/admin/users", null, token).statusCode()).isEqualTo(403);
        assertThat(send("DELETE", "/api/v1/me/cart", null, token).statusCode()).isEqualTo(204);
        assertThat(send("POST", "/api/v1/admin/categories", Map.of("name", "Forbidden"), token).statusCode()).isEqualTo(403);

        HttpResponse<String> duplicate = send("POST", "/api/v1/auth/register", registration(email), null);
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(json.readTree(duplicate.body()).path("errorCode").asString()).isEqualTo("USER_ALREADY_EXISTS");
        HttpResponse<String> invalid = send("POST", "/api/v1/auth/register", Map.of(), null);
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(json.readTree(invalid.body()).path("errorCode").asString()).isEqualTo("VALIDATION_FAILED");

        for (String proof : List.of("invalid-proof", "stale-proof")) {
            assertThat(send("GET", "/api/v1/me", null, token, "X-CSRF-TOKEN", proof).statusCode()).isEqualTo(200);
            assertThat(send("DELETE", "/api/v1/me/cart", null, null, "X-CSRF-TOKEN", proof).statusCode()).isEqualTo(403);
        }
    }

    @Test
    void api_shouldIgnoreCookiesBasicAndStoredUserOrAdminSessionContexts() throws Exception {
        String email = email();
        register(email);
        String token = login(email, PASSWORD);
        String basic = "Basic " + Base64.getEncoder().encodeToString((email + ":" + PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (String path : List.of("/api/v1/me", "/api/v1/admin/users")) {
            assertThat(send("GET", path, null, null).statusCode()).isEqualTo(403);
            assertThat(send("GET", path, null, "forged-token").statusCode()).isEqualTo(403);
            assertThat(send("GET", path, null, null, "Authorization", basic).statusCode()).isEqualTo(403);
            assertThat(send("GET", path, null, null, "Cookie", "JSESSIONID=forged; Authorization=Bearer%20" + token + "; access_token=" + token).statusCode()).isEqualTo(403);
        }

        HttpResponse<String> outside = send("POST", "/css/session-probe", Map.of(), null);
        assertThat(outside.statusCode()).isEqualTo(403);
        String cookie = outside.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        HttpSession session = SESSIONS.get(cookie.substring("JSESSIONID=".length()));
        assertThat(session != null).isTrue();
        assertSessionCannotAuthenticate(session, cookie, email);

        assertSessionCannotAuthenticate(session, cookie, administratorEmail);
        HttpResponse<String> bearerResponse = send("GET", "/api/v1/admin/users", null, login(administratorEmail, PASSWORD), "Cookie", cookie);
        assertThat(bearerResponse.statusCode()).isEqualTo(200);
        String returnedCookie = bearerResponse.headers().firstValue("Set-Cookie").orElse(cookie).split(";", 2)[0];
        assertThat(send("GET", "/api/v1/admin/users", null, null, "Cookie", returnedCookie).statusCode()).isEqualTo(403);
        assertThat(send("DELETE", "/api/v1/me/cart", null, null, "Cookie", returnedCookie).statusCode()).isEqualTo(403);
    }

    @Test
    void mutations_shouldRetainOwnershipAndImmediateCredentialAndAccountRevocation() throws Exception {
        String email = email();
        String other = email();
        register(email);
        register(other);
        String token = login(email, PASSWORD);
        String otherToken = login(other, PASSWORD);
        UUID otherId = runtime.queryForObject("SELECT id FROM users WHERE email=?", UUID.class, other);
        assertThat(send("PUT", "/api/v1/admin/users/" + otherId, Map.of("firstName", "Forbidden", "lastName", "Change"), token).statusCode()).isEqualTo(403);

        assertThat(send("PUT", "/api/v1/me/password", Map.of("currentPassword", PASSWORD,
                "newPassword", NEW_PASSWORD, "newPasswordRepeat", NEW_PASSWORD), token).statusCode()).isEqualTo(204);
        assertThat(send("GET", "/api/v1/me", null, token).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/v1/me", null, login(email, NEW_PASSWORD)).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/me", null, otherToken).statusCode()).isEqualTo(200);

        String adminToken = login(administratorEmail, PASSWORD);
        assertThat(send("POST", "/api/v1/admin/categories", Map.of("name", "HTTP category " + UUID.randomUUID()), adminToken).statusCode()).isEqualTo(201);
        assertThat(send("PUT", "/api/v1/admin/users/" + otherId + "/disable", null, adminToken).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/me", null, otherToken).statusCode()).isEqualTo(403);
        assertThat(send("PUT", "/api/v1/admin/users/" + otherId + "/enable", null, adminToken).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/me", null, otherToken).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/v1/me", null, login(other, PASSWORD)).statusCode()).isEqualTo(200);
    }

    @Test
    void corsContentTypesAndOutsideScope_shouldRetainIndependentSecurityBoundaries() throws Exception {
        String email = email();
        HttpResponse<String> preflight = send("OPTIONS", "/api/v1/auth/register", null, null,
                "Origin", "http://localhost:3000", "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "content-type,authorization");
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:3000");
        assertThat(send("POST", "/api/v1/auth/register", registration(email), null, "Origin", "https://untrusted.example").statusCode()).isEqualTo(403);
        assertThat(send("OPTIONS", "/api/v1/auth/register", null, null, "Origin", "null",
                "Access-Control-Request-Method", "POST").statusCode()).isEqualTo(403);
        for (String contentType : List.of("text/plain", "application/x-www-form-urlencoded")) {
            HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/auth/register"))
                    .header("Content-Type", contentType).header("Origin", "http://localhost:3000")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(registration(email)))).build();
            assertApiError(client.send(request, HttpResponse.BodyHandlers.ofString()), 415, "UNSUPPORTED_MEDIA_TYPE");
        }
        // Missing multipart framing fails in the servlet parser, before MVC body conversion.
        assertApiError(sendRaw("POST", "/api/v1/auth/register", json.writeValueAsString(registration(email)),
                "multipart/form-data", null), 500, null);
        assertThat(runtime.queryForObject("SELECT count(*) FROM users WHERE email=?", Long.class, email)).isZero();
        assertThat(send("POST", "/api/v1/auth/register", registration(email), null, "Origin", "http://localhost:3000").statusCode()).isEqualTo(201);
        assertThat(send("GET", "/api/v1/me", null, login(email, PASSWORD), "Origin", "https://untrusted.example").statusCode()).isEqualTo(403);
        for (String path : List.of("/css/csrf-probe", "/api/v10/auth/register", "/api/v1-other/auth/register")) {
            assertThat(send("POST", path, Map.of(), login(email, PASSWORD)).statusCode()).isEqualTo(403);
        }
        assertThat(send("POST", "/api/v1/webhooks/stripe", Map.of(), null, "Stripe-Signature", "invalid").statusCode()).isEqualTo(400);
    }

    @Test
    void unsupportedMediaTypes_shouldRejectTypedBodiesWithoutChangingProtectedState() throws Exception {
        String email = email();
        String rejectedEmail = email();
        register(email);
        String userToken = login(email, PASSWORD);
        String adminToken = login(administratorEmail, PASSWORD);
        UUID userId = runtime.queryForObject("SELECT id FROM users WHERE email=?", UUID.class, email);
        String categoryBody = json.writeValueAsString(Map.of("name", "Media audit " + UUID.randomUUID()));
        HttpResponse<String> category = send("POST", "/api/v1/admin/categories", json.readTree(categoryBody), adminToken);
        assertThat(category.statusCode()).isEqualTo(201);
        String categoryId = json.readTree(category.body()).path("id").asString();
        String id = UUID.randomUUID().toString();
        String registrationBody = json.writeValueAsString(registration(rejectedEmail));
        String loginBody = json.writeValueAsString(Map.of("email", email, "password", PASSWORD));
        String passwordBody = json.writeValueAsString(Map.of("currentPassword", PASSWORD,
                "newPassword", NEW_PASSWORD, "newPasswordRepeat", NEW_PASSWORD));
        List<String[]> requests = List.of(
                new String[] {"POST", "/api/v1/auth/register", null, registrationBody},
                new String[] {"POST", "/api/v1/auth/login", null, loginBody},
                new String[] {"POST", "/api/v1/me/cart/items", userToken, "{\"productId\":\"" + id + "\",\"quantity\":1}"},
                new String[] {"PATCH", "/api/v1/me/cart/items/" + id, userToken, "{\"quantity\":2}"},
                new String[] {"POST", "/api/v1/me/orders/checkout", userToken, "{}"},
                new String[] {"PUT", "/api/v1/me/password", userToken, passwordBody},
                new String[] {"POST", "/api/v1/reviews", userToken, "{\"productId\":\"" + id + "\",\"rating\":5,\"comment\":\"Synthetic\"}"},
                new String[] {"POST", "/api/v1/admin/categories", adminToken, categoryBody},
                new String[] {"PUT", "/api/v1/admin/categories/" + categoryId, adminToken, categoryBody},
                new String[] {"POST", "/api/v1/admin/products", adminToken, "{}"},
                new String[] {"PUT", "/api/v1/admin/products/" + id, adminToken, "{}"},
                new String[] {"PUT", "/api/v1/admin/users/" + userId, adminToken, "{\"firstName\":\"Changed\",\"lastName\":\"Rejected\"}"},
                new String[] {"POST", "/api/v1/admin/orders/stripe-payment-conflicts/" + id + "/dispositions", adminToken, "{}"});
        Map<String, String> before = protectedState();
        for (String mediaType : List.of("text/plain", "application/x-www-form-urlencoded",
                "multipart/form-data; boundary=synthetic-boundary", "application/octet-stream", "application/xml")) {
            for (String[] request : requests) {
                String body = mediaType.startsWith("multipart/")
                        ? "--synthetic-boundary\r\nContent-Disposition: form-data; name=\"payload\"\r\n"
                                + "Content-Type: application/json\r\n\r\n" + request[3] + "\r\n--synthetic-boundary--\r\n"
                        : mediaType.equals("application/x-www-form-urlencoded") ? "payload=" + java.net.URLEncoder.encode(request[3], java.nio.charset.StandardCharsets.UTF_8)
                        : request[3];
                assertApiError(sendRaw(request[0], request[1], body, mediaType, request[2]), 415, "UNSUPPORTED_MEDIA_TYPE");
            }
            assertThat(sendRaw("POST", "/api/v1/me/cart/items", "{}", mediaType, null).statusCode()).isEqualTo(403);
            assertThat(sendRaw("POST", "/api/v1/admin/categories", categoryBody, mediaType, userToken).statusCode()).isEqualTo(403);
        }
        assertApiError(sendRaw("POST", "/api/v1/auth/register", UNTRUSTED_MARKER,
                "application/x-" + UNTRUSTED_MARKER, null), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertThat(protectedState()).isEqualTo(before);
        assertThat(runtime.queryForObject("SELECT count(*) FROM users WHERE email=?", Long.class, rejectedEmail)).isZero();
        assertApiError(sendRaw("POST", "/api/v1/auth/register", "{ malformed", "application/json", null), 400, "REQUEST_INVALID");
        HttpResponse<String> missingFields = send("POST", "/api/v1/auth/register", Map.of(), null);
        assertThat(missingFields.statusCode()).isEqualTo(400);
        assertThat(json.readTree(missingFields.body()).path("errorCode").asString()).isEqualTo("VALIDATION_FAILED");
        assertApiError(sendRaw("POST", "/api/v1/auth/register", "", "application/json", null), 400, "REQUEST_INVALID");
        assertThat(send("GET", "/api/v1/me", null, userToken).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/v1/auth/register", registration(rejectedEmail), null).statusCode()).isEqualTo(201);
    }

    @Test
    void unsupportedMethod_shouldReturnLocalizedContractWithoutChangingProtectedState() throws Exception {
        String email = email();
        register(email);
        String userToken = login(email, PASSWORD);
        String adminToken = login(administratorEmail, PASSWORD);
        Map<String, String> before = protectedState();
        for (String language : List.of("en", "pl")) {
            HttpResponse<String> response = send("GET", "/api/v1/auth/register?probe=" + UNTRUSTED_MARKER,
                    Map.of("probe", UNTRUSTED_MARKER), null,
                    "Accept-Language", language, "X-Probe", UNTRUSTED_MARKER);
            assertApiError(response, 405, "METHOD_NOT_ALLOWED");
            assertThat(response.headers().allValues("Allow")).containsExactly("POST");
            assertThat(json.readTree(response.body()).path("message").asString()).isEqualTo(
                    language.equals("pl") ? "Niedozwolona metoda żądania." : "Request method not allowed.");
            assertThat(response.body()).doesNotContain(UNTRUSTED_MARKER);
        }
        for (String path : List.of("/api/v1/me/cart/items", "/api/v1/admin/categories")) {
            assertThat(send("GET", path, null, null).statusCode()).isEqualTo(403);
            assertThat(send("GET", path, null, "forged-token").statusCode()).isEqualTo(403);
            HttpResponse<String> response = send("GET", path, null,
                    path.contains("/admin/") ? adminToken : userToken);
            assertApiError(response, 405, "METHOD_NOT_ALLOWED");
            assertThat(response.headers().allValues("Allow")).containsExactly("POST");
        }
        assertThat(send("GET", "/api/v1/admin/categories", null, userToken).statusCode()).isEqualTo(403);
        assertApiError(send("GET", "/api/v1/auth/missing", null, null), 404, "ENDPOINT_NOT_FOUND");
        assertThat(protectedState()).isEqualTo(before);
    }

    private Map<String, String> protectedState() {
        Map<String, String> state = new java.util.LinkedHashMap<>();
        for (String table : List.of("roles", "users", "user_roles", "categories", "products", "product_images",
                "carts", "cart_items", "orders", "order_items", "payments", "product_reviews", "stripe_payment_conflict_dispositions")) {
            state.put(table, runtime.queryForObject("SELECT md5(COALESCE(string_agg(to_jsonb(t)::text, ',' ORDER BY to_jsonb(t)::text), '')) FROM "
                    + table + " t", String.class));
        }
        return state;
    }

    private void assertApiError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        JsonNode error = json.readTree(response.body());
        assertThat(error.size()).isEqualTo(5);
        assertThat(error.path("status").asInt()).isEqualTo(status);
        assertThat(error.path("message").asString()).isNotBlank();
        if (status == 415) assertThat(error.path("message").asString()).isEqualTo("Unsupported request media type.");
        assertThat(error.path("errorCode").isNull() ? null : error.path("errorCode").asString()).isEqualTo(code);
        assertThat(error.path("errors").isNull()).isTrue();
        assertThat(java.time.LocalDateTime.parse(error.path("timestamp").asString())).isNotNull();
    }

    private HttpResponse<String> sendRaw(String method, String path, String body, String contentType, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", contentType).header("Accept-Language", "en").header("Idempotency-Key", "synthetic-media-audit");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertSessionCannotAuthenticate(HttpSession session, String cookie, String email) throws Exception {
        var details = userDetails.loadUserByUsername(email);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        assertThat(send("GET", "/api/v1/me", null, null, "Cookie", cookie).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/v1/admin/users", null, null, "Cookie", cookie).statusCode()).isEqualTo(403);
        assertThat(send("DELETE", "/api/v1/me/cart", null, null, "Cookie", cookie).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/v1/admin/categories", Map.of("name", "Forbidden"), null, "Cookie", cookie).statusCode()).isEqualTo(403);
    }

    private void register(String email) throws Exception {
        assertThat(send("POST", "/api/v1/auth/register", registration(email), null).statusCode()).isEqualTo(201);
    }

    private Map<String, String> registration(String email) {
        return Map.of("email", email, "password", PASSWORD, "passwordRepeat", PASSWORD, "firstName", "Synthetic", "lastName", "Client");
    }

    private String login(String email, String password) throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/auth/login", Map.of("email", email, "password", password), null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("type").asString()).isEqualTo("Bearer");
        assertThat(body.has("refreshToken")).isFalse();
        String token = body.path("token").asString();
        assertThat(tokens.validate(token)).isTrue();
        issuedTokens.add(token);
        return token;
    }

    private HttpResponse<String> send(String method, String path, Object body, String token, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (headers.length > 0) request.headers(headers);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpRequest builtRequest = request.build();
        int sessionsBefore = SESSIONS.size();
        HttpResponse<String> response = client.send(builtRequest, HttpResponse.BodyHandlers.ofString());
        if (path.startsWith("/api/v1/")) {
            assertThat(SESSIONS.size()).isEqualTo(sessionsBefore);
            if (builtRequest.headers().firstValue("Cookie").isEmpty()) {
                assertThat(response.headers().allValues("Set-Cookie").isEmpty()).isTrue();
            }
        }
        return response;
    }

    private void promote(String email) throws Exception {
        POSTGRES.copyFileToContainer(MountableFile.forHostPath("scripts/configure-local-admin-operator.sql"), "/tmp/configure-client-operator.sql");
        var configured = POSTGRES.execInContainer("psql", "-X", "-U", "postgres", "-d", DATABASE,
                "--set=operator_login=" + OPERATOR, "--set=runtime_login=" + RUNTIME,
                "--set=migration_login=" + MIGRATION, "--set=expected_database=" + DATABASE,
                "--file=/tmp/configure-client-operator.sql");
        assertThat(configured.getExitCode()).isZero();
        UUID id = runtime.queryForObject("SELECT id FROM users WHERE email=?", UUID.class, email);
        try (Connection operator = connection(OPERATOR);
                var statement = operator.prepareStatement("SELECT administrator_lifecycle.promote_zero_administrator(?,?,?)")) {
            statement.setObject(1, id);
            statement.setString(2, DATABASE);
            statement.setString(3, "local");
            try (var result = statement.executeQuery()) { assertThat(result.next()).isTrue(); }
        }
    }

    private static Connection connection(String user) throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), user, DB_PASSWORD);
    }

    private String email() { return "http-" + UUID.randomUUID() + "@example.invalid"; }
    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }

    @TestConfiguration(proxyBeanMethods = false)
    static class SessionObservation {
        @Bean
        ServletListenerRegistrationBean<HttpSessionListener> observedSessions() {
            return new ServletListenerRegistrationBean<>(new HttpSessionListener() {
                @Override public void sessionCreated(HttpSessionEvent event) {
                    SESSIONS.put(event.getSession().getId(), event.getSession());
                }
                @Override public void sessionDestroyed(HttpSessionEvent event) {
                    SESSIONS.remove(event.getSession().getId());
                }
            });
        }
    }
}
