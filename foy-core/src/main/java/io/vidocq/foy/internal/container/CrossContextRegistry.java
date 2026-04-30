package io.vidocq.foy.internal.container;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registre statique des {@link VidocqServletContext} déployés — support de
 * {@link jakarta.servlet.ServletContext#getContext(String)} (§4.8) et des
 * cross-context dispatches via {@link jakarta.servlet.AsyncContext#dispatch(
 * jakarta.servlet.ServletContext, String)}.
 */
public final class CrossContextRegistry {

    private static final Map<String, VidocqServletContext> CONTEXTS = new ConcurrentHashMap<>();

    private CrossContextRegistry() {}

    public static void register(VidocqServletContext ctx) {
        CONTEXTS.put(normalize(ctx.getContextPath()), ctx);
    }

    public static void unregister(VidocqServletContext ctx) {
        CONTEXTS.remove(normalize(ctx.getContextPath()), ctx);
    }

    /**
     * Résout un {@code uripath} (commençant par {@code /}) en ServletContext.
     * Correspondance stricte sur le contextPath (pas de préfixe) — le TCK Servlet
     * 6.1 passe toujours le contextPath exact.
     */
    public static VidocqServletContext lookup(String uripath) {
        if (uripath == null) return null;
        return CONTEXTS.get(normalize(uripath));
    }

    private static String normalize(String path) {
        if (path == null || path.isEmpty()) return "/";
        if (!path.startsWith("/")) return "/" + path;
        if (path.length() > 1 && path.endsWith("/")) return path.substring(0, path.length() - 1);
        return path;
    }
}
