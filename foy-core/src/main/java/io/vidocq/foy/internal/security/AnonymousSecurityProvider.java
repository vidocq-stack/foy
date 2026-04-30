package io.vidocq.foy.internal.security;

import io.vidocq.foy.spi.security.AuthenticatedUser;
import io.vidocq.foy.spi.security.SecurityProvider;

import java.util.Optional;

/** {@link SecurityProvider} par défaut : refuse toute authentification. */
public final class AnonymousSecurityProvider implements SecurityProvider {

    @Override
    public Optional<AuthenticatedUser> authenticate(String username, String password) {
        return Optional.empty();
    }
}
