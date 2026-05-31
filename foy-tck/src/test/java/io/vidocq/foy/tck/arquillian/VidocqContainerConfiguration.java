package io.vidocq.foy.tck.arquillian;

import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.ContainerConfiguration;

/**
 * Configuration of Arquillian for the Vidocq-Servlet-Chappe container.
 *
 * <p>No properties are required at this time — the container allocates a port
 * dynamically free. Host may be overloaded via {@code arquillian.xml}.</p>
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
