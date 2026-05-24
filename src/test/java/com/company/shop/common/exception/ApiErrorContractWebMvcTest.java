package com.company.shop.common.exception;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.config.SecurityConfig;
import com.company.shop.module.product.controller.ProductController;
import com.company.shop.module.product.exception.ProductNotFoundException;
import com.company.shop.module.product.service.ProductService;
import com.company.shop.security.AuthController;
import com.company.shop.security.AuthService;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.company.shop.support.WebMvcSliceTestConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = {
		AuthController.class,
		ProductController.class,
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
	private JwtTokenProvider jwtTokenProvider;

	@MockitoBean
	private UserDetailsServiceImpl userDetailsService;

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
