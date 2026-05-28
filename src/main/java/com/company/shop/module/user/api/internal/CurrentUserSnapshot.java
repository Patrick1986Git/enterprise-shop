package com.company.shop.module.user.api.internal;

import java.util.Set;
import java.util.UUID;

public record CurrentUserSnapshot(
        UUID id,
        String email,
        Set<String> roles
) {
    public boolean hasRole(String roleName) {
        return roles != null && roles.contains(roleName);
    }
}
