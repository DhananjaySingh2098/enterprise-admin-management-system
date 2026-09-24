package com.enterprise.admin.security;

import java.util.Collection;
import java.util.Set;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.enterprise.admin.entity.RoleName;

/** Principal reconstructed from a verified access token. Contains no credentials. */
public record AuthenticatedUser(Long id, Set<RoleName> roles) {

    public AuthenticatedUser {
        roles = Set.copyOf(roles);
    }

    public Collection<? extends GrantedAuthority> authorities() {
        return roles.stream().map(role -> new SimpleGrantedAuthority(role.authority())).toList();
    }
}
