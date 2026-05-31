package io.vidocq.foy.internal.bridge;

import io.vidocq.foy.internal.dispatcher.DispatchTarget;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Wrapper for an include: URI reflect methods the original resource
 * (Servlet spec 6.1 §9.3) while the include information is exposed
 * via the {@code jakarta.servlet.include.*} attributes.
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
