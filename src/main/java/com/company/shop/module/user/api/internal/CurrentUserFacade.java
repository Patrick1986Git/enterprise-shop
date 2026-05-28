package com.company.shop.module.user.api.internal;

public interface CurrentUserFacade {

    CurrentUserSnapshot getCurrentUser();

    boolean currentUserHasRole(String roleName);
}
