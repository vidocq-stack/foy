package io.vidocq.foy.internal.error;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Register of error pages of an application (Servlet 6.1 §9.9 spec).
 *
 * <p>Two types of mapping:</p>
 * <ul>
 *   <li>by HTTP code — {@link #register(int, String)}</li>
 *   <li>by exception type — {@link #register(Class, String)} (most specific type wins)</li>
 * </ul>
 */
public final class ErrorPageRegistry {

    private final Map<Integer, String> byStatus = new LinkedHashMap<>();
    private final LinkedHashMap<Class<? extends Throwable>, String> byException = new LinkedHashMap<>();

    public ErrorPageRegistry register(int statusCode, String location) {
        Objects.requireNonNull(location, "location");
        if (statusCode < 400 || statusCode > 599) {
            throw new IllegalArgumentException("status-code error page must be 4xx/5xx: " + statusCode);
        }
        byStatus.put(statusCode, location);
        return this;
    }

    public ErrorPageRegistry register(Class<? extends Throwable> type, String location) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(location, "location");
        byException.put(type, location);
        return this;
    }

    public Optional<String> findByStatus(int statusCode) {
        return Optional.ofNullable(byStatus.get(statusCode));
    }

    /**
     * Find the most specific page matching {@code throwable}.
     * Goes up the chain of inheritance and {@link Throwable#getCause() cause}.
     */
    public Optional<String> findByException(Throwable throwable) {
        if (throwable == null) return Optional.empty();
        Throwable current = throwable;
        while (current != null) {
            Class<?> c = current.getClass();
            while (c != null && Throwable.class.isAssignableFrom(c)) {
                String page = byException.get(c);
                if (page != null) return Optional.of(page);
                c = c.getSuperclass();
            }
            current = current.getCause();
            if (current == throwable) break;
        }
        return Optional.empty();
    }

    public int size() {
        return byStatus.size() + byException.size();
    }
}
