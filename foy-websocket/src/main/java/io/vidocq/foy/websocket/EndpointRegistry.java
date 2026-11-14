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

import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerEndpointConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The server endpoints of one web application, found by context-relative path.
 *
 * <p>Registration happens at startup; lookups come from many request threads. The registry keeps an
 * immutable snapshot, ordered by precedence, in a {@code volatile} field: {@link #add} replaces it
 * under a lock and {@link #match} reads it without locking.
 */
public final class EndpointRegistry {

    private record Entry(PathTemplate template, ServerEndpointConfig config) {
    }

    private volatile List<Entry> entries = List.of();

    /** Registers an endpoint; refuses an invalid path and a path equivalent to one already registered. */
    public synchronized void add(ServerEndpointConfig config) throws DeploymentException {
        PathTemplate template = PathTemplate.parse(config.getPath());
        for (Entry existing : entries) {
            if (existing.template().shape().equals(template.shape())) {
                throw new DeploymentException("Path " + template + " duplicates " + existing.template());
            }
        }
        List<Entry> next = new ArrayList<>(entries);
        next.add(new Entry(template, config));
        next.sort((a, b) -> PathTemplate.PRECEDENCE.compare(a.template(), b.template()));
        entries = List.copyOf(next);
    }

    /** The endpoint for a context-relative path, or {@code null} when none matches. */
    public EndpointMatch match(String contextRelativePath) {
        for (Entry entry : entries) {
            Map<String, String> parameters = entry.template().match(contextRelativePath);
            if (parameters != null) {
                return new EndpointMatch(entry.config(), Map.copyOf(parameters));
            }
        }
        return null;
    }
}
