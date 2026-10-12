package com.company.shop.module.user.dto;

import com.company.shop.validation.annotation.Utf8Length;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class PasswordRecoveryReplacement {
    @NotBlank(message = "{validation.user.password.required}")
    @Size(min = 8, max = 72, message = "{validation.user.password.size}")
    @Utf8Length(max = 72, message = "{validation.user.password.maxSize}")
    private final String newPassword;

    @NotBlank(message = "{validation.user.password.confirmation.required}")
    @Utf8Length(max = 72, message = "{validation.user.password.confirmation.size}")
    private final String newPasswordRepeat;

    public PasswordRecoveryReplacement(String newPassword, String newPasswordRepeat) {
        this.newPassword = newPassword;
        this.newPasswordRepeat = newPasswordRepeat;
    }

    public String getNewPassword() { return newPassword; }

    @AssertTrue(message = "{validation.user.password.confirmation.matches}")
    public boolean isPasswordConfirmed() {
        return newPassword == null || newPasswordRepeat == null
                || newPassword.isBlank() || newPasswordRepeat.isBlank()
                || newPassword.equals(newPasswordRepeat);
    }
}
