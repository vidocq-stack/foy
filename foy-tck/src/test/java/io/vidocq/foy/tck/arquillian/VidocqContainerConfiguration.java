/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.foy.tck.arquillian;

import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.ContainerConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration of Arquillian for the Vidocq-Servlet-Chappe container.
 *
 * <p>No properties are required at this time — the container allocates a port
 * dynamically free. Host may be overloaded via {@code arquillian.xml}.</p>
 *
 * <p>{@code tls=true} serves over TLS with the stores the {@code tck-official} profile generates in
 * the directory named by the {@code foy.tck.tls.dir} system property ({@link #TLS_DIR}).</p>
 */
public class VidocqContainerConfiguration implements ContainerConfiguration {

    /** The system property naming the directory of the build-generated TLS stores. */
    public static final String TLS_DIR = "foy.tck.tls.dir";

    private String host = "127.0.0.1";
    private boolean tls;

    @Override
    public void validate() throws ConfigurationException {
        if (host == null || host.isBlank()) {
            throw new ConfigurationException("host must not be blank");
        }
        if (tls && tlsDir() == null) {
            throw new ConfigurationException("tls=true needs -D" + TLS_DIR + " (run with -Ptck,tck-official)");
        }
    }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public boolean isTls() { return tls; }
    public void setTls(boolean tls) { this.tls = tls; }

    /** The directory of the TLS stores, or {@code null} when {@link #TLS_DIR} names no directory. */
    static Path tlsDir() {
        String dir = System.getProperty(TLS_DIR);
        if (dir == null || dir.isBlank()) return null;
        Path path = Path.of(dir);
        return Files.isDirectory(path) ? path : null;
    }
}
