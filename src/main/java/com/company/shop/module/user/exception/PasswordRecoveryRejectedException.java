package com.company.shop.module.user.exception;

/** Internal rejection; no HTTP contract or secret-bearing diagnostic is exposed. */
public final class PasswordRecoveryRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PasswordRecoveryRejectedException() {
        super("Password recovery rejected");
    }
}
