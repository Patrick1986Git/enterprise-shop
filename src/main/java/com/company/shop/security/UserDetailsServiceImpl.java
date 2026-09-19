package com.company.shop.security;

import java.util.stream.Collectors;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.repository.UserRepository;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;
    private final EmailNormalizer emailNormalizer;

    public UserDetailsServiceImpl(UserRepository userRepository, EmailNormalizer emailNormalizer) {
        this.userRepository = userRepository;
        this.emailNormalizer = emailNormalizer;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email)
            throws UsernameNotFoundException {

        String normalizedEmail = emailNormalizer.normalize(email);
        User user = userRepository.findActiveByEmailWithRoles(normalizedEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        return new CredentialVersionUserDetails(
                user.getEmail(),
                user.getPassword(),
                user.isEnabled(),
                !user.isDeleted(),
                user.getRoles().stream()
                        .map(role -> new SimpleGrantedAuthority(role.getName()))
                        .collect(Collectors.toSet()),
                user.getCredentialVersion()
        );
    }
}
