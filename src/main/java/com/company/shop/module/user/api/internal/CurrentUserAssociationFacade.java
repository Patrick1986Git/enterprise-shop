package com.company.shop.module.user.api.internal;

import com.company.shop.module.user.entity.User;

/**
 * Resolves the managed current user only when another module must persist an
 * existing JPA association to that user.
 */
public interface CurrentUserAssociationFacade {

    User getCurrentUserForAssociation();
}
