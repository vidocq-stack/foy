package io.vidocq.foy.internal.session;

import io.vidocq.foy.spi.session.SessionStore;

import io.vidocq.foy.internal.listener.ListenerRegistry;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Coordonne la création, la résolution et l'expiration des {@link HttpSession}.
 *
 * <p>Génération d'ID : 128 bits de {@link SecureRandom}, encodés en hex (32 chars).</p>
 * <p>Expiration : vérification paresseuse à chaque accès — si
 * {@code now - lastAccessedTime > maxInactiveInterval * 1000}, la session est
 * retirée du store et considérée inexistante.</p>
 */
public final class SessionManager {

    public static final String COOKIE_NAME = "JSESSIONID";

    private final SessionStore store;
    private final SecureRandom random = new SecureRandom();
    private final ServletContext servletContext;
    private final int defaultMaxInactiveSeconds;
    private ListenerRegistry listenerRegistry = new ListenerRegistry();

    public SessionManager(SessionStore store, ServletContext servletContext,
                          int defaultMaxInactiveSeconds) {
        this.store = store;
        this.servletContext = servletContext;
        this.defaultMaxInactiveSeconds = defaultMaxInactiveSeconds;
    }

    public void setListenerRegistry(ListenerRegistry registry) {
        this.listenerRegistry = registry;
    }

    public ListenerRegistry listenerRegistry() { return listenerRegistry; }

    /** Résolution d'une session existante par son ID, en vérifiant l'expiration. */
    public HttpSessionImpl find(String id) {
        if (id == null) return null;
        HttpSession s = store.get(id).orElse(null);
        if (!(s instanceof HttpSessionImpl impl) || impl.isInvalidated()) return null;
        long idleMs = System.currentTimeMillis() - impl.getLastAccessedTime();
        if (impl.getMaxInactiveInterval() > 0
                && idleMs > impl.getMaxInactiveInterval() * 1000L) {
            store.remove(id);
            return null;
        }
        impl.markAccessed();
        return impl;
    }

    /** Crée une nouvelle session et la stocke. */
    public HttpSessionImpl createNew() {
        String id = generateId();
        HttpSessionImpl s = new HttpSessionImpl(id, servletContext, this, defaultMaxInactiveSeconds);
        store.put(s);
        listenerRegistry.fireSessionCreated(s);
        return s;
    }

    /** Hook de callback depuis {@link HttpSessionImpl#invalidate}. */
    void onInvalidate(HttpSessionImpl session) {
        listenerRegistry.fireSessionDestroyed(session);
        store.remove(session.getId());
    }

    public SessionStore store() { return store; }

    public int defaultMaxInactiveSeconds() { return defaultMaxInactiveSeconds; }

    private String generateId() {
        byte[] buf = new byte[16];
        random.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
