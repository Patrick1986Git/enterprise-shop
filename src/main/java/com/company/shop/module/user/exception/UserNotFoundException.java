package com.company.shop.module.user.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class UserNotFoundException extends BusinessException {

    public UserNotFoundException() {
        super(HttpStatus.NOT_FOUND,
                UserErrorCodes.USER_NOT_FOUND,
                "error.business.user.notFound",
                new Object[0],
                "User not found");
    }
}
