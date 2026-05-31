package io.vidocq.foy.spi.session;

import jakarta.servlet.http.HttpSession;

import java.util.Optional;

/**
 * {@link HttpSession} storage SPI.
 * <p>
 * A third-party implementation (Redis, JDBC, clustered storage, etc.) can
 * replace the default in-memory store by exposing this service via
 * {@code ServiceLoader}.
 * </p>
 */
public interface SessionStore {

    /** Retrieves a session by its ID, if it exists. */
    Optional<HttpSession> get(String id);

    /** Saves a new session. */
    void put(HttpSession session);

    /** Deletes a session (typically on invalidate or expiration). */
    void remove(String id);

    /** Number of sessions currently stored (diagnostic). */
    int size();
}
