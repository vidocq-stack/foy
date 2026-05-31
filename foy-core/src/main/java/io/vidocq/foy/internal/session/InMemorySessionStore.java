package io.vidocq.foy.internal.session;

import io.vidocq.foy.spi.session.SessionStore;

import jakarta.servlet.http.HttpSession;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Memory storage (local process) of sessions. No clustering.
 */
public final class InMemorySessionStore implements SessionStore {

    private final ConcurrentMap<String, HttpSession> sessions = new ConcurrentHashMap<>();

    @Override
    public Optional<HttpSession> get(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    @Override
    public void put(HttpSession session) {
        sessions.put(session.getId(), session);
    }

    @Override
    public void remove(String id) {
        sessions.remove(id);
    }

    @Override
    public int size() {
        return sessions.size();
    }
}
