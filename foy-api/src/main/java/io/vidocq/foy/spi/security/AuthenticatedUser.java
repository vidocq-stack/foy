package io.vidocq.foy.spi.security;

import java.security.Principal;
import java.util.Objects;
import java.util.Set;

/**
 * Utilisateur authentifié résolu par un {@link SecurityProvider}.
 */
public record AuthenticatedUser(String name, Set<String> roles) implements Principal {

    public AuthenticatedUser {
        Objects.requireNonNull(name, "name");
        roles = Set.copyOf(roles == null ? Set.of() : roles);
    }

    @Override public String getName() { return name; }

    public boolean hasRole(String role) { return roles.contains(role); }
}
