package io.vidocq.foy.spi.security;

import java.util.Optional;

/**
 * Delegation SPI for user authentication.
 *
 * <p>The default implementation on the foy-core side refuses any authentication;
 * an application that wishes to activate BASIC/FORM replaces this provider via the
 * ServletContext or CDI.</p>
 */
public interface SecurityProvider {

    Optional<AuthenticatedUser> authenticate(String username, String password);
}
