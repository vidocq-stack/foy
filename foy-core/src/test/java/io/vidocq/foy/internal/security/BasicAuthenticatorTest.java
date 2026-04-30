package io.vidocq.foy.internal.security;

import io.vidocq.foy.spi.security.AuthenticatedUser;
import io.vidocq.foy.spi.security.SecurityProvider;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BasicAuthenticatorTest {

    @Test
    void parsesCorrectCredentialsAndDelegates() {
        SecurityProvider provider = (u, p) ->
                "alice".equals(u) && "s3cret".equals(p)
                        ? Optional.of(new AuthenticatedUser("alice", Set.of("admin")))
                        : Optional.empty();
        var auth = new BasicAuthenticator(provider, "test");
        String header = "Basic " + Base64.getEncoder().encodeToString(
                "alice:s3cret".getBytes(StandardCharsets.UTF_8));
        var user = auth.tryAuthenticate(header);
        assertTrue(user.isPresent());
        assertEquals("alice", user.get().getName());
        assertTrue(user.get().hasRole("admin"));
    }

    @Test
    void rejectsBadCredentials() {
        SecurityProvider provider = (u, p) -> Optional.empty();
        var auth = new BasicAuthenticator(provider);
        String header = "Basic " + Base64.getEncoder().encodeToString("x:y".getBytes());
        assertTrue(auth.tryAuthenticate(header).isEmpty());
    }

    @Test
    void rejectsMalformedBase64() {
        var auth = new BasicAuthenticator((u, p) -> Optional.empty());
        assertTrue(auth.tryAuthenticate("Basic !!!notbase64!!!").isEmpty());
    }

    @Test
    void rejectsWhenNoColon() {
        var auth = new BasicAuthenticator((u, p) -> Optional.empty());
        String header = "Basic " + Base64.getEncoder().encodeToString("nocolon".getBytes());
        assertTrue(auth.tryAuthenticate(header).isEmpty());
    }

    @Test
    void ignoresNonBasicSchemes() {
        var auth = new BasicAuthenticator((u, p) -> Optional.empty());
        assertTrue(auth.tryAuthenticate("Bearer xyz").isEmpty());
        assertTrue(auth.tryAuthenticate(null).isEmpty());
    }

    @Test
    void challengeExposesRealmAndCharset() {
        var auth = new BasicAuthenticator((u, p) -> Optional.empty(), "my-realm");
        assertEquals("Basic realm=\"my-realm\", charset=\"UTF-8\"", auth.challengeHeaderValue());
    }

    @Test
    void schemePrefixIsCaseInsensitive() {
        SecurityProvider provider = (u, p) ->
                Optional.of(new AuthenticatedUser(u, Set.of()));
        var auth = new BasicAuthenticator(provider);
        String header = "basic " + Base64.getEncoder().encodeToString("x:y".getBytes());
        assertTrue(auth.tryAuthenticate(header).isPresent());
    }
}
