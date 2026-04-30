package io.vidocq.foy.internal.dispatcher;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Association d'un {@link Filter} à un url-pattern et un sous-ensemble
 * de {@link DispatcherType} (spec Servlet 6.1 section 6.2).
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

    /** Convenience : filter mappé sur REQUEST uniquement. */
    public static FilterMapping onRequest(UrlPatternMatcher matcher, Filter filter, String name) {
        return new FilterMapping(matcher, filter, name, EnumSet.of(DispatcherType.REQUEST));
    }

    public boolean applies(String path, DispatcherType type) {
        return dispatcherTypes.contains(type) && matcher.matches(path);
    }
}
