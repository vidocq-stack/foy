package io.vidocq.foy.internal.security;

import io.vidocq.foy.spi.security.AuthenticatedUser;
import io.vidocq.foy.spi.security.SecurityProvider;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * Authentification HTTP Basic (RFC 7617).
 * <p>Parse le header {@code Authorization: Basic base64(user:pass)} et délègue
 * l'authentification au {@link SecurityProvider}.</p>
 */
public final class BasicAuthenticator {

    public static final String REALM_DEFAULT = "vidocq";

    private final SecurityProvider provider;
    private final String realm;

    public BasicAuthenticator(SecurityProvider provider, String realm) {
        this.provider = Objects.requireNonNull(provider);
        this.realm = Objects.requireNonNull(realm);
    }

    public BasicAuthenticator(SecurityProvider provider) { this(provider, REALM_DEFAULT); }

    public String realm() { return realm; }

    /** Tente une authentification à partir d'un header {@code Authorization}. */
    public Optional<AuthenticatedUser> tryAuthenticate(String authorizationHeader) {
        if (authorizationHeader == null) return Optional.empty();
        String prefix = "Basic ";
        if (!authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return Optional.empty();
        }
        String encoded = authorizationHeader.substring(prefix.length()).trim();
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException e) { return Optional.empty(); }
        String raw = new String(decoded, StandardCharsets.UTF_8);
        int colon = raw.indexOf(':');
        if (colon < 0) return Optional.empty();
        String user = raw.substring(0, colon);
        String pass = raw.substring(colon + 1);
        return provider.authenticate(user, pass);
    }

    public String challengeHeaderValue() {
        return "Basic realm=\"" + realm + "\", charset=\"UTF-8\"";
    }
}
