package com.company.shop.module.user.service;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.company.shop.module.user.api.internal.CurrentUserFacade;
import com.company.shop.module.user.api.internal.CurrentUserSnapshot;
import com.company.shop.module.user.entity.User;

@Service
public class CurrentUserFacadeImpl implements CurrentUserFacade {

    private final UserService userService;

    public CurrentUserFacadeImpl(UserService userService) {
        this.userService = userService;
    }

    @Override
    public CurrentUserSnapshot getCurrentUser() {
        User user = userService.getCurrentUserEntity();

        Set<String> roles = user.getRoles().stream()
                .map(role -> role.getName())
                .collect(Collectors.toUnmodifiableSet());

        return new CurrentUserSnapshot(user.getId(), user.getEmail(), roles);
    }

    @Override
    public boolean currentUserHasRole(String roleName) {
        return getCurrentUser().hasRole(roleName);
    }
}
