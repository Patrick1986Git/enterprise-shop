package com.company.shop.validation.annotation;

import com.company.shop.module.user.dto.PasswordChangeRequestDTO;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordChangeMatchesValidator
        implements ConstraintValidator<PasswordChangeMatches, PasswordChangeRequestDTO> {

    @Override
    public boolean isValid(PasswordChangeRequestDTO request, ConstraintValidatorContext context) {
        if (request == null || isBlank(request.getNewPassword()) || isBlank(request.getNewPasswordRepeat())) {
            return true;
        }
        if (request.getNewPassword().equals(request.getNewPasswordRepeat())) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("newPasswordRepeat")
                .addConstraintViolation();
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
