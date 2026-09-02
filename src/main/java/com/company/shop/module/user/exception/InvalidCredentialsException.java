package com.company.shop.module.user.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class InvalidCredentialsException extends BusinessException {

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED,
                UserErrorCodes.USER_INVALID_CREDENTIALS,
                "error.business.user.invalidCredentials",
                new Object[0],
                "Invalid email or password");
    }
}
