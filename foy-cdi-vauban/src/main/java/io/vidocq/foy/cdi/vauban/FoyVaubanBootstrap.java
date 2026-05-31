package io.vidocq.foy.cdi.vauban;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.spi.BeanManager;

/**
 * Utility bridge between Vauban CDI and Foy.
 *
 * <p>Gets the {@link BeanManager} of the current {@link VaubanContainer} for
 * pass it to {@code FoyChappeBoot} (or any other HTTP transport based on
 * foy-core).</p>
 *
 * <h3>Example of usage</h3>
 * <pre>{@code
 * try (var container = VaubanContainer.builder().scanLocal().build()) {
 *     BeanManager bm = FoyVaubanBootstrap.beanManager();
 *     FoyChappeBoot.builder().beanManager(bm).contextPath("/").build()
 *         .ifPresent(mounted -> mountPoint.mount(listener, mounted.mountPrefix(), mounted.handler()));
 * }
 * }</pre>
 *
 * <p>Deeper integration (via SPI {@code BeanProvider} of
 * foy-api) will be introduced in M2 to allow discovery by
 * {@code ServiceLoader} et l'auto-configuration.</p>
 */
public final class FoyVaubanBootstrap {

    private FoyVaubanBootstrap() {}

    /**
     * Returns the {@link BeanManager} of the current {@link VaubanContainer}.
     *
     * @throws IllegalStateException if no Vauban container is started
     */
    public static BeanManager beanManager() {
        return beanManager(VaubanContainer.current());
    }

    /** Returns the {@link BeanManager} of the given Vauban container. */
    public static BeanManager beanManager(VaubanContainer container) {
        if (container == null) {
            throw new IllegalStateException(
                    "No Vauban CDI container is running — call VaubanContainer.builder().build() first");
        }
        return container.getBeanManager();
    }
}
