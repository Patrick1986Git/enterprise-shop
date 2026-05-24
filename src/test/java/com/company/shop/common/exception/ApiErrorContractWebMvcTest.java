package com.company.shop.common.exception;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.config.SecurityConfig;
import com.company.shop.module.product.controller.AdminProductController;
import com.company.shop.module.product.controller.ProductController;
import com.company.shop.module.product.exception.ProductNotFoundException;
import com.company.shop.module.product.service.ProductService;
import com.company.shop.security.AuthController;
import com.company.shop.security.AuthService;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.company.shop.support.WebMvcSliceTestConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {
		AuthController.class,
		ProductController.class,
		AdminProductController.class,
		ApiErrorContractWebMvcTest.TestBusinessExceptionController.class
})
@Import({ WebMvcSliceTestConfig.class, SecurityConfig.class })
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

		mockMvc.perform(post("/api/v1/auth/register")
						.with(csrf())
						.header("Accept-Language", "en")
						.contentType(MediaType.APPLICATION_JSON)
						.content(invalidRegisterPayload))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Validation failed"))
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors").isMap())
				.andExpect(jsonPath("$.errors", hasKey("email")))
				.andExpect(jsonPath("$.errors.email", not(empty())))
				.andExpect(jsonPath("$.timestamp").exists());

		assertApiErrorShape();
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

		mockMvc.perform(post("/api/v1/auth/register")
						.with(csrf())
						.header("Accept-Language", "pl")
						.contentType(MediaType.APPLICATION_JSON)
						.content(invalidRegisterPayload))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Walidacja nie powiodła się"))
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
	}

	@Test
	void requestInvalid_shouldReturnLocalizedContractForMalformedJsonInPolish() throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.with(csrf())
						.header("Accept-Language", "pl")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ invalid json }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Treść żądania ma niepoprawny format JSON."))
				.andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
				.andExpect(jsonPath("$.errors").value(nullValue()))
				.andExpect(jsonPath("$.timestamp").exists());

		assertApiErrorShape();
	}

	@Test
	void accessDenied_shouldReturnLocalizedContract() throws Exception {
		mockMvc.perform(get("/api/v1/admin/products/{id}", UUID.randomUUID())
						.with(user("user").roles("USER"))
						.header("Accept-Language", "pl"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.status").value(403))
				.andExpect(jsonPath("$.message").value("Brak uprawnień do dostępu do tego zasobu"))
				.andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
				.andExpect(jsonPath("$.errors").value(nullValue()))
				.andExpect(jsonPath("$.timestamp").exists());

		assertApiErrorShape();
	}

	@Test
	void unknownBusinessError_shouldReturnFallbackErrorCodeAndLocalizedMessage() throws Exception {
		mockMvc.perform(get("/api/v1/test/business-unknown").header("Accept-Language", "en"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Business validation failed"))
				.andExpect(jsonPath("$.errorCode").value("UNKNOWN_BUSINESS_ERROR"))
				.andExpect(jsonPath("$.errors").value(nullValue()))
				.andExpect(jsonPath("$.timestamp").exists());

		assertApiErrorShape();
	}

	@Test
	void realBusinessException_shouldReturnLocalizedContractInEnglishAndPolish() throws Exception {
		String missingProductSlug = "missing-product";
		org.mockito.Mockito.when(productService.findBySlug(missingProductSlug))
				.thenThrow(new ProductNotFoundException(missingProductSlug));

		mockMvc.perform(get("/api/v1/products/slug/{slug}", missingProductSlug).header("Accept-Language", "en"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.message").value("Product not found for slug: missing-product"))
				.andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"))
				.andExpect(jsonPath("$.errors").value(nullValue()))
				.andExpect(jsonPath("$.timestamp").exists());

		mockMvc.perform(get("/api/v1/products/slug/{slug}", missingProductSlug).header("Accept-Language", "pl"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Nie znaleziono produktu dla slug: missing-product"))
				.andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
	}

	private void assertApiErrorShape() throws Exception {
		mockMvc.perform(get("/api/v1/test/business-unknown"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.*", hasSize(5)))
				.andExpect(jsonPath("$.status").exists())
				.andExpect(jsonPath("$.message").exists())
				.andExpect(jsonPath("$.errorCode").exists())
				.andExpect(jsonPath("$.errors").exists())
				.andExpect(jsonPath("$.timestamp").exists());
	}

	@RestController
	@RequestMapping("/api/v1/test")
	static class TestBusinessExceptionController {

		@GetMapping("/business-unknown")
		@PreAuthorize("permitAll()")
		void businessUnknown() {
			throw new UnknownCodeBusinessException();
		}
	}

	static class UnknownCodeBusinessException extends BusinessException {
		UnknownCodeBusinessException() {
			super(HttpStatus.BAD_REQUEST, "Business validation failed");
		}
	}
}
