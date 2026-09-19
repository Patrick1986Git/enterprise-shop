/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.user.entity;

import java.util.HashSet;
import java.util.Set;

import org.hibernate.annotations.SQLRestriction;

import com.company.shop.common.model.SoftDeleteEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

/**
 * Domain entity representing a system user account.
 * <p>
 * Implements soft-deletion via {@link SQLRestriction} and supports 
 * role-based access control through a many-to-many relationship with {@link Role}.
 * </p>
 *
 * @since 1.0.0
 */
@Entity
@Table(name = "users")
@SQLRestriction("deleted = false")
public class User extends SoftDeleteEntity {

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password", nullable = false, length = 255)
    private String password;

    @Column(name = "credential_version", nullable = false)
    private long credentialVersion;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    /**
     * Set of security roles assigned to the user.
     * <p>
     * Switched to {@link FetchType#LAZY} to optimize performance and prevent 
     * unintended data loading during batch processing or simple profile retrievals.
     * </p>
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new HashSet<>();

    /**
     * Internal constructor for JPA proxying.
     */
    protected User() {
        // JPA
    }

    /**
     * Specialized constructor for new user registration.
     *
     * @param email           unique email address used as identifier.
     * @param encodedPassword secure, hashed password string.
     * @param firstName       user's given name.
     * @param lastName        user's family name.
     */
    public User(String email, String encodedPassword, String firstName, String lastName) {
        this.email = email;
        this.password = encodedPassword;
        this.firstName = firstName;
        this.lastName = lastName;
        this.enabled = true;
    }

    public String getEmail() {
        return email;
    }

    public String getPassword() {
        return password;
    }

    public long getCredentialVersion() {
        return credentialVersion;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getFirstName() {
        return firstName;
    }
    
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }
    
    public Set<Role> getRoles() {
        return roles;
    }
    
    // ===== BUSINESS METHODS =====

    /**
     * Grants a specific security role to this user.
     *
     * @param role the role to be added.
     */
    public void addRole(Role role) {
        this.roles.add(role);
    }

    /**
     * Deactivates the user account without removing the record from the database.
     */
    public void disable() {
        if (this.enabled) {
            this.enabled = false;
            incrementCredentialVersion();
        }
    }

    /**
     * Restores interactive access without making tokens issued before suspension valid again.
     */
    public void enable() {
        this.enabled = true;
    }

    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
        incrementCredentialVersion();
    }

    private void incrementCredentialVersion() {
        this.credentialVersion = Math.incrementExact(this.credentialVersion);
    }
}
