package io.vidocq.foy.tck.arquillian;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.core.spi.LoadableExtension;

/**
 * Enregistre {@link VidocqDeployableContainer} comme {@link DeployableContainer}
 * découvrable par Arquillian via son SPI {@link LoadableExtension}.
 */
public class VidocqContainerExtension implements LoadableExtension {
    @Override
    public void register(ExtensionBuilder builder) {
        builder.service(DeployableContainer.class, VidocqDeployableContainer.class);
    }
}
