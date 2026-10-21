package com.company.shop.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.config.SecurityConfig;
import com.company.shop.module.cart.controller.CartController;
import com.company.shop.module.cart.service.CartService;
import com.company.shop.module.category.controller.AdminCategoryController;
import com.company.shop.module.category.service.CategoryService;
import com.company.shop.module.product.controller.ProductController;
import com.company.shop.module.product.exception.ProductNotFoundException;
import com.company.shop.module.product.service.ProductService;
import com.company.shop.security.AuthController;
import com.company.shop.security.AuthService;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.company.shop.support.WebMvcSliceTestConfig;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = {
		AuthController.class,
		ProductController.class,
		CartController.class,
		AdminCategoryController.class,
		ApiErrorContractWebMvcTest.TestBusinessExceptionController.class
})
@Import({ WebMvcSliceTestConfig.class, SecurityConfig.class, ApiErrorContractWebMvcTest.TestControllerConfig.class })
class ApiErrorContractWebMvcTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private AuthService authService;

	@MockitoBean
	private ProductService productService;

	@MockitoBean
	private CartService cartService;

	@MockitoBean
	private CategoryService categoryService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@MockitoBean
	private UserDetailsServiceImpl userDetailsService;

	@ParameterizedTest
	@CsvSource({
			"text/plain,en,Unsupported request media type.",
			"application/x-www-form-urlencoded,en,Unsupported request media type.",
			"multipart/form-data,en,Unsupported request media type.",
			"text/plain,pl,Nieobsługiwany typ treści żądania.",
			"application/x-www-form-urlencoded,pl,Nieobsługiwany typ treści żądania.",
			"multipart/form-data,pl,Nieobsługiwany typ treści żądania."
	})
	void unsupportedMediaType_shouldReturnLocalizedContractWithoutCallingAuthService(
			String contentType, String language, String message) throws Exception {
		for (String path : new String[] { "/api/v1/auth/register", "/api/v1/auth/login" }) {
			ResultActions result = mockMvc.perform(post(path)
					.header("Accept-Language", language)
					.contentType(contentType)
					.content("synthetic-untrusted-body"));

			assertApiErrorShape(result, 415, "UNSUPPORTED_MEDIA_TYPE")
					.andExpect(jsonPath("$.message").value(message))
					.andExpect(jsonPath("$.errors").value(nullValue()))
					.andExpect(r -> assertThat(r.getResolvedException())
							.isExactlyInstanceOf(HttpMediaTypeNotSupportedException.class));
		}
		verifyNoInteractions(authService);
	}

	@ParameterizedTest
	@ValueSource(strings = { "text/plain", "application/x-www-form-urlencoded", "multipart/form-data" })
	void unsupportedMediaType_shouldPreserveSecurityAndNeverCallMutationServices(String contentType) throws Exception {
		mockMvc.perform(post("/api/v1/me/cart/items").contentType(contentType).content("{}"))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/v1/admin/categories").with(user("test").roles("USER"))
				.contentType(contentType).content("{}"))
				.andExpect(status().isForbidden());

		assertApiErrorShape(mockMvc.perform(post("/api/v1/me/cart/items").with(user("test").roles("USER"))
				.contentType(contentType).content("{}")), 415, "UNSUPPORTED_MEDIA_TYPE");
		assertApiErrorShape(mockMvc.perform(post("/api/v1/admin/categories").with(user("admin").roles("ADMIN"))
				.contentType(contentType).content("{\"name\":\"Synthetic category\"}")), 415, "UNSUPPORTED_MEDIA_TYPE");
		verifyNoInteractions(cartService, categoryService);
	}

	@Test
	void unexpectedServerFailureWithMediaTypeCause_shouldRemainGenericServerError() throws Exception {
		org.mockito.Mockito.when(productService.findBySlug("server-failure"))
				.thenThrow(new IllegalStateException("synthetic-internal-diagnostic",
						new HttpMediaTypeNotSupportedException("synthetic-content-type")));
		assertApiErrorShape(mockMvc.perform(get("/api/v1/products/slug/server-failure")
				.header("Accept-Language", "en")), 500, null)
				.andExpect(jsonPath("$.message").value(
						"An unexpected server error occurred. Please contact support if the problem persists."))
				.andExpect(jsonPath("$.errors").value(nullValue()));
	}

	@ParameterizedTest
	@CsvSource({
			"en,Request method not allowed.",
			"pl,Niedozwolona metoda żądania."
	})
	void unsupportedMethodAndUnmatchedRoute_shouldReturnDistinctContracts(String language, String message) throws Exception {
		assertApiErrorShape(mockMvc.perform(get("/api/v1/auth/register")
				.header("Accept-Language", language)), 405, "METHOD_NOT_ALLOWED")
				.andExpect(header().string("Allow", "POST"))
				.andExpect(jsonPath("$.message").value(message))
				.andExpect(jsonPath("$.errors").value(nullValue()))
				.andExpect(r -> assertThat(r.getResolvedException())
						.isExactlyInstanceOf(HttpRequestMethodNotSupportedException.class));
		assertApiErrorShape(mockMvc.perform(get("/api/v1/auth/missing")), 404, "ENDPOINT_NOT_FOUND");
		verifyNoInteractions(authService);
	}

	@Test
	void unsupportedMethod_shouldPreserveSecurityAndNeverCallMutationServices() throws Exception {
		mockMvc.perform(get("/api/v1/me/cart/items"))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/admin/categories"))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/admin/categories").with(user("test").roles("USER")))
				.andExpect(status().isForbidden());

		assertApiErrorShape(mockMvc.perform(get("/api/v1/me/cart/items").with(user("test").roles("USER"))),
				405, "METHOD_NOT_ALLOWED").andExpect(header().string("Allow", "POST"));
		assertApiErrorShape(mockMvc.perform(get("/api/v1/admin/categories").with(user("admin").roles("ADMIN"))),
				405, "METHOD_NOT_ALLOWED").andExpect(header().string("Allow", "POST"));
		verifyNoInteractions(cartService, categoryService);
	}

	@Test
	void validationFailed_shouldReturnLocalizedContractInEnglish() throws Exception {
		String invalidRegisterPayload = objectMapper.writeValueAsString(new Object() {
			public final String email = "invalid-email";
			public final String password = "short";
			public final String passwordRepeat = "different";
			public final String firstName = "";
			public final String lastName = "";
		});

		ResultActions result = mockMvc.perform(post("/api/v1/auth/register")
				.with(csrf())
				.header("Accept-Language", "en")
				.contentType(MediaType.APPLICATION_JSON)
				.content(invalidRegisterPayload));

		assertApiErrorShape(result, 400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.message").value("Validation failed"))
				.andExpect(jsonPath("$.errors").isMap())
				.andExpect(jsonPath("$.errors", hasKey("email")))
				.andExpect(jsonPath("$.errors.email", not(empty())));

		verify(authService, never()).register(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void validationFailed_shouldReturnLocalizedContractInPolish() throws Exception {
		String invalidRegisterPayload = """
				{
				  "email": "invalid-email",
				  "password": "short",
				  "passwordRepeat": "different",
				  "firstName": "",
				  "lastName": ""
				}
				""";

		ResultActions result = mockMvc.perform(post("/api/v1/auth/register")
				.with(csrf())
				.header("Accept-Language", "pl")
				.contentType(MediaType.APPLICATION_JSON)
				.content(invalidRegisterPayload));

		assertApiErrorShape(result, 400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.message").value("Walidacja nie powiodła się"));
	}

	@Test
	void requestInvalid_shouldReturnLocalizedContractForMalformedJsonInPolish() throws Exception {
		ResultActions result = mockMvc.perform(post("/api/v1/auth/register")
				.with(csrf())
				.header("Accept-Language", "pl")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ invalid json }"));

		assertApiErrorShape(result, 400, "REQUEST_INVALID")
				.andExpect(jsonPath("$.message").value("Treść żądania ma niepoprawny format."))
				.andExpect(jsonPath("$.errors").value(nullValue()));
	}

	@Test
	void accessDenied_shouldReturnLocalizedContract() throws Exception {
		ResultActions result = mockMvc.perform(get("/api/v1/test/access-denied")
				.with(user("test").roles("USER"))
				.header("Accept-Language", "pl"));

		assertApiErrorShape(result, 403, "ACCESS_DENIED")
				.andExpect(jsonPath("$.message").value("Brak uprawnień do dostępu do tego zasobu"))
				.andExpect(jsonPath("$.errors").value(nullValue()));
	}

	@Test
	void unknownBusinessError_shouldReturnFallbackErrorCodeAndLocalizedMessage() throws Exception {
		ResultActions result = mockMvc.perform(get("/api/v1/test/business-unknown")
				.with(user("test").roles("USER"))
				.header("Accept-Language", "en"));

		assertApiErrorShape(result, 400, "UNKNOWN_BUSINESS_ERROR")
				.andExpect(jsonPath("$.message").value("Business validation failed"))
				.andExpect(jsonPath("$.errors").value(nullValue()));
	}

	@Test
	void realBusinessException_shouldReturnLocalizedContractInEnglishAndPolish() throws Exception {
		String missingProductSlug = "missing-product";
		org.mockito.Mockito.when(productService.findBySlug(missingProductSlug))
				.thenThrow(new ProductNotFoundException(missingProductSlug));

		ResultActions englishResult = mockMvc.perform(get("/api/v1/products/slug/{slug}", missingProductSlug)
				.header("Accept-Language", "en"));

		assertApiErrorShape(englishResult, 404, "PRODUCT_NOT_FOUND")
				.andExpect(jsonPath("$.message").value("Product not found for slug: missing-product"))
				.andExpect(jsonPath("$.errors").value(nullValue()));

		ResultActions polishResult = mockMvc.perform(get("/api/v1/products/slug/{slug}", missingProductSlug)
				.header("Accept-Language", "pl"));

		assertApiErrorShape(polishResult, 404, "PRODUCT_NOT_FOUND")
				.andExpect(jsonPath("$.message").value("Nie znaleziono produktu dla sluga: missing-product"))
				.andExpect(jsonPath("$.errors").value(nullValue()));
	}

	private ResultActions assertApiErrorShape(ResultActions result, int expectedStatus, String expectedErrorCode)
			throws Exception {
		return result.andExpect(status().is(expectedStatus))
				.andExpect(jsonPath("$.*", hasSize(5)))
				.andExpect(jsonPath("$.status").value(expectedStatus))
				.andExpect(jsonPath("$.message").exists())
				.andExpect(jsonPath("$.errorCode").value(expectedErrorCode))
				.andExpect(jsonPath("$.timestamp").exists());
	}

	@TestConfiguration
	static class TestControllerConfig {

		@Bean
		TestBusinessExceptionController testBusinessExceptionController() {
			return new TestBusinessExceptionController();
		}
	}

	@RestController
	@RequestMapping("/api/v1/test")
	static class TestBusinessExceptionController {

		@GetMapping("/business-unknown")
		void businessUnknown() {
			throw new UnknownCodeBusinessException();
		}

		@GetMapping("/access-denied")
		void accessDenied() {
			throw new AccessDeniedException("forbidden");
		}
	}

	static class UnknownCodeBusinessException extends BusinessException {
		UnknownCodeBusinessException() {
			super(HttpStatus.BAD_REQUEST, "Business validation failed");
		}
	}
}
