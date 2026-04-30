package io.vidocq.foy.internal.security;

import io.vidocq.foy.spi.security.AuthenticatedUser;
import io.vidocq.foy.spi.security.SecurityProvider;

import io.vidocq.foy.internal.bridge.HttpServletRequestImpl;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.annotation.HttpConstraint;
import jakarta.servlet.annotation.HttpMethodConstraint;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;

/**
 * Applique les contraintes {@code @ServletSecurity} avant l'invocation du servlet.
 *
 * <p>Support MVP :</p>
 * <ul>
 *   <li>{@link HttpConstraint}#rolesAllowed : si non vide, exige que l'utilisateur
 *       soit authentifié ET possède au moins un rôle listé.</li>
 *   <li>{@link ServletSecurity.EmptyRoleSemantic}#DENY sur rolesAllowed vide : refuse toujours.</li>
 *   <li>{@link HttpMethodConstraint} : override par méthode HTTP si présent.</li>
 * </ul>
 *
 * <p>Retourne {@code false} si la requête a été rejetée (401/403) — le caller n'invoque pas le servlet.</p>
 */
public final class SecurityConstraintEnforcer {

    private final BasicAuthenticator authenticator;

    public SecurityConstraintEnforcer(SecurityProvider provider) {
        this.authenticator = new BasicAuthenticator(provider);
    }

    public boolean enforce(Class<?> servletClass, HttpServletRequest req, HttpServletResponse res)
            throws IOException {
        ServletSecurity annotation = servletClass.getAnnotation(ServletSecurity.class);
        if (annotation == null) return true;
        ServletSecurityElement element = toElement(annotation);

        HttpConstraint effective = resolveEffective(annotation, req.getMethod());
        String[] roles = effective.rolesAllowed();
        var semantic = effective.value();

        if (roles.length == 0) {
            if (semantic == ServletSecurity.EmptyRoleSemantic.DENY) {
                res.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden");
                return false;
            }
            return true; // PERMIT
        }

        // rolesAllowed non vide → exige authentification.
        if (req instanceof HttpServletRequestImpl impl && impl.currentUser() == null) {
            tryBasicAuth(impl);
        }
        AuthenticatedUser user = extractUser(req);
        if (user == null) {
            res.setHeader("WWW-Authenticate", authenticator.challengeHeaderValue());
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
            return false;
        }
        for (String r : roles) {
            if (user.hasRole(r)) return true;
        }
        res.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden");
        return false;
    }

    private void tryBasicAuth(HttpServletRequestImpl req) {
        String header = req.getHeader("Authorization");
        if (header == null) return;
        authenticator.tryAuthenticate(header)
                .ifPresent(u -> req.bindAuthenticated(u, "BASIC"));
    }

    private static AuthenticatedUser extractUser(HttpServletRequest req) {
        if (req instanceof HttpServletRequestImpl impl) return impl.currentUser();
        var p = req.getUserPrincipal();
        return p instanceof AuthenticatedUser u ? u : null;
    }

    private static HttpConstraint resolveEffective(ServletSecurity annotation, String method) {
        for (HttpMethodConstraint m : annotation.httpMethodConstraints()) {
            if (m.value().equals(method)) {
                // Construire un HttpConstraint virtuel via proxy — pour MVP on lit directement les champs.
                return new MethodConstraintAsHttpConstraint(m);
            }
        }
        return annotation.value();
    }

    private static ServletSecurityElement toElement(ServletSecurity annotation) {
        return new ServletSecurityElement(annotation);
    }

    /** Adapter minimal : {@link HttpMethodConstraint} vers {@link HttpConstraint}. */
    private record MethodConstraintAsHttpConstraint(HttpMethodConstraint methodConstraint)
            implements HttpConstraint {
        @Override public Class<? extends java.lang.annotation.Annotation> annotationType() { return HttpConstraint.class; }
        @Override public ServletSecurity.EmptyRoleSemantic value() { return methodConstraint.emptyRoleSemantic(); }
        @Override public ServletSecurity.TransportGuarantee transportGuarantee() { return methodConstraint.transportGuarantee(); }
        @Override public String[] rolesAllowed() { return methodConstraint.rolesAllowed(); }
    }

    public static Set<String> rolesOf(Class<?> servletClass) {
        ServletSecurity ann = servletClass.getAnnotation(ServletSecurity.class);
        if (ann == null) return Set.of();
        return new java.util.HashSet<>(Arrays.asList(ann.value().rolesAllowed()));
    }
}
