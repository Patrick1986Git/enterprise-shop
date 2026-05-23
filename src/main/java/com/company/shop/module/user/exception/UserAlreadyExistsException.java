package com.company.shop.module.user.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class UserAlreadyExistsException extends BusinessException {

    public UserAlreadyExistsException() {
        super(HttpStatus.CONFLICT,
                UserErrorCodes.USER_ALREADY_EXISTS,
                "error.business.user.alreadyExists",
                new Object[0],
                "User account already exists");
    }
}
