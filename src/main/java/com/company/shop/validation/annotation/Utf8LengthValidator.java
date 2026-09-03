package com.company.shop.validation.annotation;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class Utf8LengthValidator implements ConstraintValidator<Utf8Length, String> {
    private int max;

    @Override
    public void initialize(Utf8Length constraintAnnotation) {
        max = constraintAnnotation.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
    }
}
