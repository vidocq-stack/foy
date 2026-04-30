package io.vidocq.foy.internal.http;

import jakarta.servlet.http.Cookie;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodage/décodage des cookies HTTP (RFC 6265).
 * <p>
 * Décodage : parse un header {@code Cookie} entrant en liste de {@link Cookie}.
 * Encodage : sérialise un {@link Cookie} en ligne {@code Set-Cookie} complète
 * avec ses attributs (Path, Domain, Max-Age, Secure, HttpOnly, SameSite).
 * </p>
 */
public final class CookieCodec {

    private CookieCodec() {}

    /** Parse le header {@code Cookie} : {@code name1=v1; name2=v2}. */
    public static List<Cookie> parseCookieHeader(String value) {
        List<Cookie> out = new ArrayList<>();
        if (value == null || value.isEmpty()) return out;
        for (String pair : value.split(";")) {
            String trimmed = pair.trim();
            int eq = trimmed.indexOf('=');
            if (eq < 0) continue;
            String name = trimmed.substring(0, eq).trim();
            String val = trimmed.substring(eq + 1).trim();
            if (name.isEmpty()) continue;
            // RFC 6265 §5.4 : un header Cookie client ne contient que des cookies nus
            // (name=value). Certains clients (ou le TCK) peuvent tout de même y glisser
            // des cookie-attributes (Domain, Path, Expires, Max-Age, ...) copiés depuis
            // un Set-Cookie précédent — on les ignore pour ne pas les exposer comme
            // "vrais" cookies.
            if (isReservedAttribute(name)) continue;
            // RFC 6265 : la valeur peut être encadrée de guillemets ; on préserve ceux-ci
            // (comportement attendu par TCK CookieTests.getValueQuotedTest — la valeur
            // ne doit pas être déguillementée lors de la lecture côté serveur).
            try {
                out.add(new Cookie(name, val));
            } catch (IllegalArgumentException ignored) {
                // Nom/valeur non conformes aux règles Cookie — on skip silencieusement.
            }
        }
        return out;
    }

    private static boolean isReservedAttribute(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.equals("domain") || n.equals("path") || n.equals("expires")
                || n.equals("max-age") || n.equals("secure") || n.equals("httponly")
                || n.equals("samesite") || n.equals("partitioned") || n.equals("comment")
                || n.startsWith("$");
    }

    /** Sérialise un {@link Cookie} complet en ligne {@code Set-Cookie}. */
    public static String serializeSetCookie(Cookie c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.getName()).append('=').append(c.getValue() == null ? "" : c.getValue());
        if (c.getPath() != null) sb.append("; Path=").append(c.getPath());
        if (c.getDomain() != null) sb.append("; Domain=").append(c.getDomain());
        int age = c.getMaxAge();
        if (age == 0) {
            // Servlet 6.1 §7 / RFC 6265 : Max-Age=0 → le cookie expire immédiatement.
            // On émet un Expires dans le passé (compat clients qui ne suivent pas Max-Age).
            sb.append("; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
        } else if (age > 0) {
            sb.append("; Max-Age=").append(age);
        }
        if (c.getSecure()) sb.append("; Secure");
        if (c.isHttpOnly()) sb.append("; HttpOnly");
        String sameSite = c.getAttribute("SameSite");
        if (sameSite != null) sb.append("; SameSite=").append(sameSite);
        // Servlet 6.1 §7.1 : l'attribut "Partitioned" (Chrome partitioning) est émis comme flag.
        String partitioned = c.getAttribute("Partitioned");
        // Le flag Partitioned est actif si l'attribut est défini (valeur non-null), même vide.
        if (partitioned != null && (partitioned.isEmpty() || "true".equalsIgnoreCase(partitioned))) {
            sb.append("; Partitioned");
        }
        // Autres attributs custom passés par setAttribute() sortent tels quels (clé=valeur).
        for (var e : c.getAttributes().entrySet()) {
            String k = e.getKey();
            if (k == null) continue;
            String kl = k.toLowerCase(java.util.Locale.ROOT);
            if (kl.equals("samesite") || kl.equals("partitioned")
                    || kl.equals("path") || kl.equals("domain")
                    || kl.equals("max-age") || kl.equals("secure")
                    || kl.equals("httponly") || kl.equals("comment")) continue;
            sb.append("; ").append(k);
            if (e.getValue() != null && !e.getValue().isEmpty()) sb.append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
