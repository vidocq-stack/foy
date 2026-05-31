package io.vidocq.foy.internal.dispatcher;

import jakarta.servlet.Servlet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Associates {@link UrlPatternMatcher} with {@link Servlet} and resolves the servlet
 * the most specific for a given path.
 */
public final class ServletDispatcher {

    /** Pattern-to-servlet association. {@code asyncSupported} reflects
     *  {@code <async-supported>} from web.xml (or {@code @WebServlet(asyncSupported=...)});
     *  default is {@code true} for constructors without this argument. */
    public record Mapping(UrlPatternMatcher matcher, Servlet servlet, String servletName,
                          boolean asyncSupported) {
        public Mapping {
            Objects.requireNonNull(matcher);
            Objects.requireNonNull(servlet);
            Objects.requireNonNull(servletName);
        }
        public Mapping(UrlPatternMatcher matcher, Servlet servlet, String servletName) {
            this(matcher, servlet, servletName, true);
        }
    }

    private final List<Mapping> mappings;

    public ServletDispatcher(List<Mapping> mappings) {
        List<Mapping> sorted = new ArrayList<>(mappings);
        sorted.sort(Comparator.comparingInt(m -> m.matcher().precedence()));
        this.mappings = List.copyOf(sorted);
    }

    /** Finds the servlet that should respond for the given path. */
    public Optional<Mapping> find(String path) {
        for (Mapping m : mappings) {
            if (m.matcher().matches(path)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    public List<Mapping> mappings() {
        return mappings;
    }
}
