package io.vidocq.foy.internal.dispatcher;

import jakarta.servlet.Servlet;

/**
 * Cible résolue d'un dispatch servlet : la servlet et les coordonnées
 * d'URL qu'elle verra ({@code servletPath}, {@code pathInfo}, {@code queryString}).
 */
public record DispatchTarget(Servlet servlet,
                             String servletName,
                             String path,
                             String servletPath,
                             String pathInfo,
                             String queryString) {
    /** Clone with a new queryString (utilisé pour les async dispatches). */
    public DispatchTarget withQueryString(String qs) {
        return new DispatchTarget(servlet, servletName, path, servletPath, pathInfo, qs);
    }
}
