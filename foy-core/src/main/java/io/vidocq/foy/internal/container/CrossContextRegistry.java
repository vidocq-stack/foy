package io.vidocq.foy.internal.container;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static registry of deployed {@link VidocqServletContext} — support for
 * {@link jakarta.servlet.ServletContext#getContext(String)} (§4.8) and
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
     * Resolves a {@code uripath} (starting with {@code /}) to a ServletContext.
     * Strict match on contextPath (no prefix) — the TCK Servlet
     * 6.1 always passes the exact contextPath.
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
