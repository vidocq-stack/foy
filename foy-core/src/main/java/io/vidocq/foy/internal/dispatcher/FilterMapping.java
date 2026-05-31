package io.vidocq.foy.internal.dispatcher;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Association of a {@link Filter} with a url-pattern and a subset
 * of {@link DispatcherType} (Servlet 6.1 spec section 6.2).
 */
public record FilterMapping(UrlPatternMatcher matcher,
                            Filter filter,
                            String filterName,
                            Set<DispatcherType> dispatcherTypes) {

    public FilterMapping {
        Objects.requireNonNull(matcher, "matcher");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(filterName, "filterName");
        Objects.requireNonNull(dispatcherTypes, "dispatcherTypes");
        if (dispatcherTypes.isEmpty()) {
            dispatcherTypes = EnumSet.of(DispatcherType.REQUEST);
        } else {
            dispatcherTypes = EnumSet.copyOf(dispatcherTypes);
        }
    }

    /** Convenience: filter mapped to REQUEST only. */
    public static FilterMapping onRequest(UrlPatternMatcher matcher, Filter filter, String name) {
        return new FilterMapping(matcher, filter, name, EnumSet.of(DispatcherType.REQUEST));
    }

    public boolean applies(String path, DispatcherType type) {
        return dispatcherTypes.contains(type) && matcher.matches(path);
    }
}
