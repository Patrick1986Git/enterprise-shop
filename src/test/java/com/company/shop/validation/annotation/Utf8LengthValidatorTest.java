package com.company.shop.validation.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class Utf8LengthValidatorTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void validate_shouldAllowNullAndExactUtf8ByteLimit() {
        assertThat(validate(null)).isEmpty();
        assertThat(validate("a".repeat(72))).isEmpty();
        assertThat(validate("🔐".repeat(18))).isEmpty();
    }

    @Test
    void validate_shouldRejectValuesBeyondUtf8ByteLimit() {
        assertThat(validate("a".repeat(73))).hasSize(1);
        assertThat(validate("🔐".repeat(19))).hasSize(1);
    }

    private Set<ConstraintViolation<Value>> validate(String value) {
        return validator.validate(new Value(value));
    }

    private record Value(@Utf8Length(max = 72, message = "too long") String text) {
    }
}
