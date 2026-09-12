package com.company.shop.module.user.service;

import org.springframework.stereotype.Service;

import com.company.shop.module.user.api.internal.CurrentUserAssociationFacade;
import com.company.shop.module.user.entity.User;

@Service
public class CurrentUserAssociationFacadeImpl implements CurrentUserAssociationFacade {

    private final UserService userService;

    public CurrentUserAssociationFacadeImpl(UserService userService) {
        this.userService = userService;
    }

    @Override
    public User getCurrentUserForAssociation() {
        return userService.getCurrentUserEntity();
    }
}
