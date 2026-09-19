package com.company.shop.module.user.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class CurrentPasswordIncorrectException extends BusinessException {

    public CurrentPasswordIncorrectException() {
        super(HttpStatus.BAD_REQUEST, UserErrorCodes.USER_CURRENT_PASSWORD_INCORRECT,
                "error.business.user.currentPasswordIncorrect", new Object[0],
                "Current password is incorrect");
    }
}
