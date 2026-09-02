package com.company.shop.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@SpringBootTest(
		classes = ProductionFallbackErrorHttpTest.TestApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
class ProductionFallbackErrorHttpTest {

	private static final String EXCEPTION_MESSAGE_SENTINEL = "SHOULD_NOT_LEAK_EXCEPTION_MESSAGE";
	private static final String BINDING_DETAIL_SENTINEL = "SHOULD_NOT_LEAK_BINDING_DETAIL";
	private static final String INTERNAL_DETAIL_SENTINEL =
			"jdbc:postgresql://internal-db /srv/enterprise-shop com.company.shop.internal.SecretService";

	@LocalServerPort
	private int port;

	@Test
	void fallbackError_shouldSuppressInternalExceptionDetailsInProduction() throws Exception {
		HttpResponse<String> response = get("/test-only/fallback-exception");

		assertThat(response.statusCode()).isEqualTo(500);
		assertThat(response.body())
				.contains("\"status\":500")
				.contains("\"path\":\"/test-only/fallback-exception\"")
				.doesNotContain(
						EXCEPTION_MESSAGE_SENTINEL,
						INTERNAL_DETAIL_SENTINEL,
						SQLException.class.getName(),
						ProductionFallbackErrorHttpTest.class.getName(),
						"\"exception\"",
						"\"trace\"");
	}

	@Test
	void fallbackError_shouldSuppressBindingInternalsInProduction() throws Exception {
		HttpResponse<String> response = get("/test-only/fallback-binding");

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.body())
				.contains("\"status\":400")
				.doesNotContain(BINDING_DETAIL_SENTINEL, "\"errors\"");
	}

	private HttpResponse<String> get(String path) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Accept", "application/json")
				.GET()
				.build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration(exclude = {
			DataSourceAutoConfiguration.class,
			HibernateJpaAutoConfiguration.class,
			FlywayAutoConfiguration.class,
			SecurityAutoConfiguration.class,
			ServletWebSecurityAutoConfiguration.class,
			UserDetailsServiceAutoConfiguration.class,
			ManagementWebSecurityAutoConfiguration.class
	})
	static class TestApplication {

		@Bean
		ServletRegistrationBean<HttpServlet> fallbackExceptionServlet() {
			return new ServletRegistrationBean<>(new HttpServlet() {
				@Override
				protected void service(HttpServletRequest request, HttpServletResponse response)
						throws ServletException {
					throw new ServletException(
							EXCEPTION_MESSAGE_SENTINEL + " " + INTERNAL_DETAIL_SENTINEL,
							new SQLException(INTERNAL_DETAIL_SENTINEL));
				}
			}, "/test-only/fallback-exception");
		}

		@Bean
		ServletRegistrationBean<HttpServlet> fallbackBindingServlet() {
			return new ServletRegistrationBean<>(new HttpServlet() {
				@Override
				protected void service(HttpServletRequest request, HttpServletResponse response)
						throws ServletException, IOException {
					BindException bindingError = new BindException(new Object(), "request");
					bindingError.addError(new FieldError(
							"request", "secretField", BINDING_DETAIL_SENTINEL));
					request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 400);
					request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, bindingError);
					request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());
					request.getRequestDispatcher("/error").forward(request, response);
				}
			}, "/test-only/fallback-binding");
		}
	}
}
