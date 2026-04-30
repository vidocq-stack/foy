package io.vidocq.foy.spi.session;

import jakarta.servlet.http.HttpSession;

import java.util.Optional;

/**
 * SPI de stockage des {@link HttpSession}.
 * <p>
 * Une implémentation tierce (Redis, JDBC, cluster) peut remplacer le store
 * en mémoire par défaut en fournissant ce service via {@code ServiceLoader}.
 * </p>
 */
public interface SessionStore {

    /** Récupère une session par son ID, si elle existe. */
    Optional<HttpSession> get(String id);

    /** Enregistre une nouvelle session. */
    void put(HttpSession session);

    /** Supprime une session (typiquement sur invalidate ou expiration). */
    void remove(String id);

    /** Nombre de sessions actuellement stockées (diagnostic). */
    int size();
}
