package io.vidocq.foy.internal.bridge;

import io.vidocq.foy.internal.dispatcher.DispatchTarget;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Wrapper pour un include : les méthodes URI reflètent la ressource originale
 * (spec Servlet 6.1 §9.3) tandis que les informations du include sont exposées
 * via les attributs {@code jakarta.servlet.include.*}.
 */
public final class IncludedRequest extends HttpServletRequestWrapper {

    private final DispatchTarget target;

    public IncludedRequest(HttpServletRequest original, DispatchTarget target) {
        super(original);
        this.target = target;
    }

    DispatchTarget target() { return target; }

    @Override public DispatcherType getDispatcherType() { return DispatcherType.INCLUDE; }
}
