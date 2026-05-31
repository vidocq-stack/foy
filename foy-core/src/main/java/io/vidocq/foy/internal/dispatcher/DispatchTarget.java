package io.vidocq.foy.internal.dispatcher;

import jakarta.servlet.Servlet;

/**
 * Resolved target of a servlet dispatch: the servlet and contact details
 * URL it will see ({@code servletPath}, {@code pathInfo}, {@code queryString}).
 */
public record DispatchTarget(Servlet servlet,
                             String servletName,
                             String path,
                             String servletPath,
                             String pathInfo,
                             String queryString) {
    /** Clone with a new queryString (used for async dispatches). */
    public DispatchTarget withQueryString(String qs) {
        return new DispatchTarget(servlet, servletName, path, servletPath, pathInfo, qs);
    }
}
