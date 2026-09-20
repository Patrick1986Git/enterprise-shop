package com.company.shop.module.category.archfixture;

import com.company.shop.module.user.api.internal.CurrentUserFacade;

public class ForbiddenInternalApiConsumer {

    private final CurrentUserFacade currentUserFacade;

    public ForbiddenInternalApiConsumer(CurrentUserFacade currentUserFacade) {
        this.currentUserFacade = currentUserFacade;
    }

    public CurrentUserFacade getCurrentUserFacade() {
        return currentUserFacade;
    }
}
