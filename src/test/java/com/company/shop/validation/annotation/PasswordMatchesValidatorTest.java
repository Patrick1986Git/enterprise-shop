package com.company.shop.validation.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.company.shop.module.user.dto.RegisterRequestDTO;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

class PasswordMatchesValidatorTest {

    private LocalValidatorFactoryBean validatorFactory;
    private Validator validator;
    private final PasswordMatchesValidator passwordMatchesValidator = new PasswordMatchesValidator();
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
    void passwordMatches_shouldPassWhenPasswordsAreEqual() {
        RegisterRequestDTO request = new RegisterRequestDTO("john@example.com", "secret123", "secret123", "John", "Doe");

        Set<ConstraintViolation<RegisterRequestDTO>> violations = validator.validate(request);

        assertThat(violations).noneMatch(v -> v.getPropertyPath().toString().equals("passwordRepeat")
                && v.getMessage().equals("Passwords do not match"));
    }

    @Test
    void passwordMatches_shouldFailWhenPasswordsAreDifferent() {
        RegisterRequestDTO request = new RegisterRequestDTO("john@example.com", "secret123", "secret124", "John", "Doe");

        Set<ConstraintViolation<RegisterRequestDTO>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath().toString()).isEqualTo("passwordRepeat");
                    assertThat(violation.getMessage()).isEqualTo("Passwords do not match");
                });
    }

    @Test
    void passwordMatches_shouldFailWhenPasswordsDifferOnlyByCase() {
        RegisterRequestDTO request = new RegisterRequestDTO("john@example.com", "Secret123", "secret123", "John", "Doe");

        Set<ConstraintViolation<RegisterRequestDTO>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath().toString()).isEqualTo("passwordRepeat");
                    assertThat(violation.getMessage()).isEqualTo("Passwords do not match");
                });
    }

    @Test
    void passwordMatches_shouldNotAddMismatchWhenPasswordIsNull() {
        RegisterRequestDTO request = new RegisterRequestDTO("john@example.com", null, "secret123", "John", "Doe");

        Set<ConstraintViolation<RegisterRequestDTO>> violations = validator.validate(request);

        assertThat(violations)
                .filteredOn(violation -> violation.getMessage().equals("Passwords do not match"))
                .isEmpty();
    }

    @Test
    void passwordMatches_shouldNotAddMismatchWhenPasswordRepeatIsBlank() {
        RegisterRequestDTO request = new RegisterRequestDTO("john@example.com", "secret123", "   ", "John", "Doe");

        Set<ConstraintViolation<RegisterRequestDTO>> violations = validator.validate(request);

        assertThat(violations)
                .filteredOn(violation -> violation.getMessage().equals("Passwords do not match"))
                .isEmpty();
    }

    @Test
    void isValid_shouldReturnTrueWhenRootDtoIsNull() {
        boolean isValid = passwordMatchesValidator.isValid(null, null);

        assertThat(isValid).isTrue();
    }

    @Test
    void passwordMatches_shouldAttachMismatchErrorToPasswordRepeatField() {
        RegisterRequestDTO request = new RegisterRequestDTO("john@example.com", "secret123", "different", "John", "Doe");

        Set<ConstraintViolation<RegisterRequestDTO>> violations = validator.validate(request);

        assertThat(violations)
                .filteredOn(violation -> violation.getMessage().equals("Passwords do not match"))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsOnly("passwordRepeat");
    }
}
