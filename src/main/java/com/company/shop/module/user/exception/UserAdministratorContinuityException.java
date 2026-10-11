package com.company.shop.module.user.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class UserAdministratorContinuityException extends BusinessException {

    public UserAdministratorContinuityException() {
        super(HttpStatus.CONFLICT, UserErrorCodes.USER_ADMINISTRATOR_CONTINUITY_REQUIRED,
                "error.business.user.administratorContinuity", new Object[0],
                "Administrator continuity must be preserved");
    }
}
