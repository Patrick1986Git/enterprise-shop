package com.company.shop.module.user.dto;

import com.company.shop.validation.annotation.PasswordChangeMatches;
import com.company.shop.validation.annotation.Utf8Length;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for changing the authenticated user's password.")
@PasswordChangeMatches
public class PasswordChangeRequestDTO {

    @Schema(format = "password", maxLength = 72, requiredMode = Schema.RequiredMode.REQUIRED,
            accessMode = Schema.AccessMode.WRITE_ONLY)
    @NotBlank(message = "{validation.user.password.required}")
    @Utf8Length(max = 72, message = "{validation.user.password.maxSize}")
    private String currentPassword;

    @Schema(format = "password", minLength = 8, maxLength = 72, requiredMode = Schema.RequiredMode.REQUIRED,
            accessMode = Schema.AccessMode.WRITE_ONLY)
    @NotBlank(message = "{validation.user.password.required}")
    @Size(min = 8, max = 72, message = "{validation.user.password.size}")
    @Utf8Length(max = 72, message = "{validation.user.password.maxSize}")
    private String newPassword;

    @Schema(format = "password", maxLength = 72, requiredMode = Schema.RequiredMode.REQUIRED,
            accessMode = Schema.AccessMode.WRITE_ONLY)
    @NotBlank(message = "{validation.user.password.confirmation.required}")
    @Utf8Length(max = 72, message = "{validation.user.password.confirmation.size}")
    private String newPasswordRepeat;

    public PasswordChangeRequestDTO() {
    }

    public PasswordChangeRequestDTO(String currentPassword, String newPassword, String newPasswordRepeat) {
        this.currentPassword = currentPassword;
        this.newPassword = newPassword;
        this.newPasswordRepeat = newPasswordRepeat;
    }

    public String getCurrentPassword() { return currentPassword; }
    public String getNewPassword() { return newPassword; }
    public String getNewPasswordRepeat() { return newPasswordRepeat; }
}
