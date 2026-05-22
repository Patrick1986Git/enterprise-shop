package com.company.shop.module.user.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

class RegisterRequestDTOValidationTest {

	private LocalValidatorFactoryBean validatorFactory;
	private Validator validator;
	private Locale previousLocale;

	@BeforeEach
	void setUp() {
		previousLocale = Locale.getDefault();
		Locale.setDefault(Locale.ENGLISH);

		ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
		messageSource.setBasename("i18n/messages");
		messageSource.setDefaultEncoding("UTF-8");

		validatorFactory = new LocalValidatorFactoryBean();
		validatorFactory.setValidationMessageSource(messageSource);
		validatorFactory.afterPropertiesSet();

		validator = validatorFactory.getValidator();
	}

	@AfterEach
	void tearDown() {
		validatorFactory.destroy();
		Locale.setDefault(previousLocale);
	}

	@Test
	void validate_shouldReturnEmailConstraintViolationWhenEmailHasInvalidFormat() {
		RegisterRequestDTO request = new RegisterRequestDTO("invalid-email", "secret123", "secret123", "John", "Doe");

		Set<ConstraintViolation<RegisterRequestDTO>> violations = validator.validate(request);

		assertFieldHasConstraint(violations, "email", Email.class);
		assertThat(violations).filteredOn(violation -> violation.getMessage().equals("Passwords do not match"))
				.isEmpty();
	}

	@Test
	void validate_shouldReturnSizeConstraintViolationWhenPasswordIsTooShort() {
		RegisterRequestDTO request = new RegisterRequestDTO("john@example.com", "short", "short", "John", "Doe");

		Set<ConstraintViolation<RegisterRequestDTO>> violations = validator.validate(request);

		assertFieldHasConstraint(violations, "password", Size.class);
		assertThat(violations).filteredOn(violation -> violation.getMessage().equals("Passwords do not match"))
				.isEmpty();
	}

	@Test
	void validate_shouldReturnFieldLevelViolationsWhenFieldsAreEmptyStrings() {
		RegisterRequestDTO request = new RegisterRequestDTO("", "", "", "", "");

		Set<ConstraintViolation<RegisterRequestDTO>> violations = validator.validate(request);

		assertFieldHasConstraint(violations, "email", NotBlank.class);
		assertFieldHasConstraint(violations, "password", NotBlank.class);
		assertFieldHasConstraint(violations, "passwordRepeat", NotBlank.class);
		assertFieldHasConstraint(violations, "firstName", NotBlank.class);
		assertFieldHasConstraint(violations, "lastName", NotBlank.class);

		assertThat(violations).filteredOn(violation -> violation.getMessage().equals("Passwords do not match"))
				.isEmpty();
	}

	@Test
	void validate_shouldReturnFieldLevelViolationsWhenFieldsContainOnlyBlankSpaces() {
		RegisterRequestDTO request = new RegisterRequestDTO(" ", " ", " ", " ", " ");

		Set<ConstraintViolation<RegisterRequestDTO>> violations = validator.validate(request);

		assertFieldHasConstraint(violations, "email", NotBlank.class);
		assertFieldHasConstraint(violations, "password", NotBlank.class);
		assertFieldHasConstraint(violations, "passwordRepeat", NotBlank.class);
		assertFieldHasConstraint(violations, "firstName", NotBlank.class);
		assertFieldHasConstraint(violations, "lastName", NotBlank.class);

		assertThat(violations).filteredOn(violation -> violation.getMessage().equals("Passwords do not match"))
				.isEmpty();
	}

	@Test
	void validate_shouldReturnFieldLevelViolationsWhenAllFieldsAreNull() {
		RegisterRequestDTO request = new RegisterRequestDTO(null, null, null, null, null);

		Set<ConstraintViolation<RegisterRequestDTO>> violations = validator.validate(request);

		assertFieldHasConstraint(violations, "email", NotBlank.class);
		assertFieldHasConstraint(violations, "password", NotBlank.class);
		assertFieldHasConstraint(violations, "passwordRepeat", NotBlank.class);
		assertFieldHasConstraint(violations, "firstName", NotBlank.class);
		assertFieldHasConstraint(violations, "lastName", NotBlank.class);

		assertThat(violations).filteredOn(violation -> violation.getMessage().equals("Passwords do not match"))
				.isEmpty();
	}

	@Test
	void validate_shouldAggregateFieldLevelAndPasswordMismatchViolationsWhenAllApply() {
		RegisterRequestDTO request = new RegisterRequestDTO("invalid-email", "short", "different", "", "");

		Set<ConstraintViolation<RegisterRequestDTO>> violations = validator.validate(request);

		assertFieldHasConstraint(violations, "email", Email.class);
		assertFieldHasConstraint(violations, "password", Size.class);
		assertFieldHasConstraint(violations, "firstName", NotBlank.class);
		assertFieldHasConstraint(violations, "lastName", NotBlank.class);

		assertThat(violations).filteredOn(violation -> violation.getPropertyPath().toString().equals("passwordRepeat"))
				.extracting(ConstraintViolation::getMessage).contains("Passwords do not match");
	}

	private void assertFieldHasConstraint(Set<ConstraintViolation<RegisterRequestDTO>> violations, String field,
			Class<? extends Annotation> annotationType) {
		assertThat(violations).anySatisfy(violation -> {
			assertThat(violation.getPropertyPath().toString()).isEqualTo(field);
			assertThat(violation.getConstraintDescriptor().getAnnotation().annotationType()).isEqualTo(annotationType);
		});
	}
}
