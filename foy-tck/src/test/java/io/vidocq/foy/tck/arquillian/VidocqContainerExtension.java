package io.vidocq.foy.tck.arquillian;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.core.spi.LoadableExtension;

/**
 * Save {@link VidocqDeployableContainer} as {@link DeployableContainer}
 * discoverable by Arquillian via its SPI {@link LoadableExtension}.
 */
public class VidocqContainerExtension implements LoadableExtension {
    @Override
    public void register(ExtensionBuilder builder) {
        builder.service(DeployableContainer.class, VidocqDeployableContainer.class);
    }
}
