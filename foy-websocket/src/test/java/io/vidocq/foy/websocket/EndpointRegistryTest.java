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
package io.vidocq.foy.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.server.ServerEndpointConfig;

import java.util.Map;

import org.junit.jupiter.api.Test;

class EndpointRegistryTest {

    private static ServerEndpointConfig config(String path) {
        return ServerEndpointConfig.Builder.create(Endpoint.class, path).build();
    }

    @Test
    void anExactPathBeatsATemplate() throws DeploymentException {
        EndpointRegistry registry = new EndpointRegistry();
        registry.add(config("/a/{x}"));
        registry.add(config("/a/b"));
        EndpointMatch match = registry.match("/a/b");
        assertEquals("/a/b", match.config().getPath());
        assertEquals(Map.of(), match.pathParameters());
        assertEquals("/a/{x}", registry.match("/a/c").config().getPath());
    }

    @Test
    void aLiteralSegmentBeatsAVariableAtTheFirstDifference() throws DeploymentException {
        EndpointRegistry registry = new EndpointRegistry();
        registry.add(config("/{x}/b"));
        registry.add(config("/a/{x}"));
        assertEquals("/a/{x}", registry.match("/a/b").config().getPath());
        assertEquals("/{x}/b", registry.match("/c/b").config().getPath());
    }

    @Test
    void theMatchCarriesThePathParameters() throws DeploymentException {
        EndpointRegistry registry = new EndpointRegistry();
        registry.add(config("/{path0}/{path1}"));
        assertEquals(Map.of("path0", "VAADIN", "path1", "push"), registry.match("/VAADIN/push").pathParameters());
    }

    @Test
    void noMatchReturnsNull() throws DeploymentException {
        EndpointRegistry registry = new EndpointRegistry();
        registry.add(config("/{x}"));
        assertNull(registry.match("/a/b"));
        assertNull(registry.match("/"));
    }

    @Test
    void anEquivalentTemplateIsADuplicate() throws DeploymentException {
        EndpointRegistry registry = new EndpointRegistry();
        registry.add(config("/a/{x}"));
        ServerEndpointConfig duplicate = config("/a/{y}");
        assertThrows(DeploymentException.class, () -> registry.add(duplicate));
    }

    /**
     * Atmosphere's {@code JSR356AsyncSupport} registers {@code /VAADIN/push}, {@code /VAADIN/push/},
     * then {@code /VAADIN/push/{path0}} and deeper: each is accepted and keeps its own requests.
     */
    @Test
    void aPathEndingInASlashIsALiteralBesideItsVariableSibling() throws DeploymentException {
        EndpointRegistry registry = new EndpointRegistry();
        registry.add(config("/a/b"));
        registry.add(config("/a/b/{x}"));
        registry.add(config("/a/b/"));
        assertEquals("/a/b/", registry.match("/a/b/").config().getPath());
        assertEquals("/a/b", registry.match("/a/b").config().getPath());
        assertEquals("/a/b/{x}", registry.match("/a/b/c").config().getPath());
    }

    @Test
    void theSameExactPathTwiceIsADuplicate() throws DeploymentException {
        EndpointRegistry registry = new EndpointRegistry();
        registry.add(config("/a"));
        ServerEndpointConfig duplicate = config("/a");
        assertThrows(DeploymentException.class, () -> registry.add(duplicate));
    }
}
