package com.company.shop.validation.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.company.shop.module.user.dto.PasswordChangeRequestDTO;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

class PasswordChangeMatchesValidatorTest {

    private final PasswordChangeMatchesValidator passwordChangeMatchesValidator =
            new PasswordChangeMatchesValidator();
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
    void isValid_shouldReturnTrueWhenRequestIsNull() {
        assertThat(passwordChangeMatchesValidator.isValid(null, null)).isTrue();
    }

    @Test
    void isValid_shouldReturnTrueWhenNewPasswordIsNull() {
        assertThat(passwordChangeMatchesValidator.isValid(request(null, "NewPassword123!"), null)).isTrue();
    }

    @Test
    void isValid_shouldReturnTrueWhenNewPasswordIsBlank() {
        assertThat(passwordChangeMatchesValidator.isValid(request("   ", "NewPassword123!"), null)).isTrue();
    }

    @Test
    void isValid_shouldReturnTrueWhenRepeatedPasswordIsNull() {
        assertThat(passwordChangeMatchesValidator.isValid(request("NewPassword123!", null), null)).isTrue();
    }

    @Test
    void isValid_shouldReturnTrueWhenRepeatedPasswordIsBlank() {
        assertThat(passwordChangeMatchesValidator.isValid(request("NewPassword123!", "   "), null)).isTrue();
    }

    @Test
    void isValid_shouldReturnTrueWhenNewPasswordsMatch() {
        assertThat(passwordChangeMatchesValidator.isValid(
                request("NewPassword123!", "NewPassword123!"), null)).isTrue();
    }

    @Test
    void validation_shouldAttachMismatchViolationToNewPasswordRepeat() {
        PasswordChangeRequestDTO request = request("NewPassword123!", "DifferentPassword456!");

        Set<ConstraintViolation<PasswordChangeRequestDTO>> violations = validator.validate(request);

        assertThat(violations)
                .filteredOn(violation -> violation.getMessage().equals("Passwords do not match"))
                .singleElement()
                .extracting(violation -> violation.getPropertyPath().toString())
                .isEqualTo("newPasswordRepeat");
    }

    private PasswordChangeRequestDTO request(String newPassword, String newPasswordRepeat) {
        return new PasswordChangeRequestDTO("CurrentPassword123!", newPassword, newPasswordRepeat);
    }
}
