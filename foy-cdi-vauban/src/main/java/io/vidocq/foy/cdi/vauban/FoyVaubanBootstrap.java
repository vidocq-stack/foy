package io.vidocq.foy.cdi.vauban;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.spi.BeanManager;

/**
 * Pont utilitaire entre Vauban CDI et Foy.
 *
 * <p>Récupère le {@link BeanManager} du {@link VaubanContainer} courant pour
 * le passer à {@code FoyChappeBoot} (ou tout autre transport HTTP basé sur
 * foy-core).</p>
 *
 * <h3>Exemple d'usage</h3>
 * <pre>{@code
 * try (var container = VaubanContainer.builder().scanLocal().build()) {
 *     BeanManager bm = FoyVaubanBootstrap.beanManager();
 *     FoyChappeBoot.builder().beanManager(bm).contextPath("/").build()
 *         .ifPresent(mounted -> mountPoint.mount(listener, mounted.mountPrefix(), mounted.handler()));
 * }
 * }</pre>
 *
 * <p>Une intégration plus profonde (via la SPI {@code BeanProvider} de
 * foy-api) sera introduite en M2 pour permettre la découverte par
 * {@code ServiceLoader} et l'auto-configuration.</p>
 */
public final class FoyVaubanBootstrap {

    private FoyVaubanBootstrap() {}

    /**
     * Retourne le {@link BeanManager} du {@link VaubanContainer} courant.
     *
     * @throws IllegalStateException si aucun container Vauban n'est démarré
     */
    public static BeanManager beanManager() {
        return beanManager(VaubanContainer.current());
    }

    /** Retourne le {@link BeanManager} du container Vauban donné. */
    public static BeanManager beanManager(VaubanContainer container) {
        if (container == null) {
            throw new IllegalStateException(
                    "No Vauban CDI container is running — call VaubanContainer.builder().build() first");
        }
        return container.getBeanManager();
    }
}
