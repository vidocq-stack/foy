package io.vidocq.foy.tck.arquillian;

import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.ContainerConfiguration;

/**
 * Configuration d'Arquillian pour le container Vidocq-Servlet-Chappe.
 *
 * <p>Aucune propriété n'est requise pour l'instant — le container alloue un port
 * libre dynamiquement. L'hôte peut être surchargé via {@code arquillian.xml}.</p>
 */
public class VidocqContainerConfiguration implements ContainerConfiguration {

    private String host = "127.0.0.1";

    @Override
    public void validate() throws ConfigurationException {
        if (host == null || host.isBlank()) {
            throw new ConfigurationException("host must not be blank");
        }
    }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
}
