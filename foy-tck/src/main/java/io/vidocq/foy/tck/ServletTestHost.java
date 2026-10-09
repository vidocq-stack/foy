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
package io.vidocq.foy.tck;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;

import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One Chappe {@link Server} serving several deployments, routed by context-path prefix (the
 * longest prefix wins; {@code ""} is the root context). A request under no mounted context is
 * answered {@code 404} by the router. Unmounting a context never touches the others and does not
 * interrupt requests already running on its handler (it only stops new ones from being routed).
 */
public final class ServletTestHost implements AutoCloseable {

    private final Map<String, Handler> mounts = new ConcurrentHashMap<>();
    private final Server server;
    private final int port;

    /** Starts the server on a free local port. */
    public ServletTestHost() {
        RuntimeException last = null;
        Server started = null;
        int bound = -1;
        for (int attempt = 0; attempt < 5 && started == null; attempt++) {
            int candidate;
            try (ServerSocket s = new ServerSocket(0)) { candidate = s.getLocalPort(); }
            catch (Exception e) { throw new RuntimeException(e); }
            try {
                // Short keep-alive idle timeout: some TCK clients (6.1.0 TrailerTest) read the
                // response to EOF on a keep-alive connection and rely on the container closing
                // it; 60 s (chappe default) would add a minute per such test.
                Server s = Server.builder().host("127.0.0.1").port(candidate)
                        .idleTimeout(java.time.Duration.ofSeconds(5))
                        .handler(this::route).build();
                s.start();
                started = s;
                bound = candidate;
            } catch (RuntimeException e) { last = e; }
        }
        if (started == null) throw last;
        this.server = started;
        this.port = bound;
    }

    public int port() { return port; }

    /**
     * Mounts {@code handler} under {@code contextPath} ({@code "/"} or {@code ""} for the root).
     *
     * @throws IllegalStateException when the context path is already mounted
     */
    public void mount(String contextPath, Handler handler) {
        if (mounts.putIfAbsent(prefix(contextPath), handler) != null) {
            throw new IllegalStateException("context path already mounted: " + contextPath);
        }
    }

    /** Stops routing new requests to {@code contextPath}; requests already running are not interrupted. */
    public void unmount(String contextPath) { mounts.remove(prefix(contextPath)); }

    @Override public void close() { server.stop(); }

    private static String prefix(String contextPath) {
        if (contextPath == null) return "";
        String p = contextPath;
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private Response route(io.vidocq.chappe.api.Request request) throws Exception {
        String path = request.path();
        Handler best = null;
        int bestLength = -1;
        for (var e : mounts.entrySet()) {
            String prefix = e.getKey();
            boolean matches = path.startsWith(prefix) && (path.length() == prefix.length()
                    || path.charAt(prefix.length()) == '/' || path.charAt(prefix.length()) == ';'
                    || prefix.isEmpty());
            if (matches && prefix.length() > bestLength) {
                best = e.getValue();
                bestLength = prefix.length();
            }
        }
        return best == null ? Response.of(StatusCode.NOT_FOUND) : best.handle(request);
    }
}
