package com.company.shop.security;

import java.util.Collection;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

public final class CredentialVersionUserDetails extends User {

    private final long credentialVersion;

    public CredentialVersionUserDetails(String username, String password, boolean enabled, boolean accountNonLocked,
                                        Collection<? extends GrantedAuthority> authorities,
                                        long credentialVersion) {
        super(username, password, enabled, true, true, accountNonLocked, authorities);
        this.credentialVersion = credentialVersion;
    }

    public long getCredentialVersion() {
        return credentialVersion;
    }
}
