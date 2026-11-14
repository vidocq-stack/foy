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
package io.vidocq.foy.websocket.it;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.foy.chappe.FoyChappeBoot;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Foy application on a Chappe server bound to a free port, for the end-to-end tests. The
 * application is a {@code web.xml} declaring one listener, which runs the
 * {@link ServletContextListener} the test gives; {@code foy-websocket}'s initializer is found on
 * the class path, as in a real deployment. {@link #close()} undeploys, then stops the server.
 */
final class WebSocketTestServer implements AutoCloseable {

    /** The listener of the test being booted, read by {@link Bootstrap} while the deployment runs. */
    private static final ScopedValue<ServletContextListener> LISTENER = ScopedValue.newInstance();

    /** The listener the descriptor declares: it runs the listener of the test that boots the server. */
    public static final class Bootstrap implements ServletContextListener {
        private ServletContextListener delegate;

        @Override
        public void contextInitialized(ServletContextEvent event) {
            delegate = LISTENER.orElseThrow(() -> new IllegalStateException("not booted by WebSocketTestServer"));
            delegate.contextInitialized(event);
        }

        @Override
        public void contextDestroyed(ServletContextEvent event) {
            if (delegate != null) {
                delegate.contextDestroyed(event);
            }
        }
    }

    private record User(String name, String password, String[] roles) {
    }

    /** What to deploy: the listener, then the context path, init parameters, descriptor elements and users. */
    static final class Builder {
        private final ServletContextListener listener;
        private String contextPath = "/";
        private final Map<String, String> initParameters = new LinkedHashMap<>();
        private String descriptorElements = "";
        private final List<User> users = new ArrayList<>();
        private Duration idleTimeout;
        private Duration readTimeout;

        private Builder(ServletContextListener listener) {
            this.listener = listener;
        }

        Builder contextPath(String path) {
            contextPath = path;
            return this;
        }

        Builder initParameter(String name, String value) {
            initParameters.put(name, value);
            return this;
        }

        /** Elements added to the {@code web-app} of the descriptor (security constraints, login config). */
        Builder descriptor(String elements) {
            descriptorElements = elements;
            return this;
        }

        /** Chappe's idle timeout of a connection; the server's default when not set. */
        Builder idleTimeout(Duration timeout) {
            idleTimeout = timeout;
            return this;
        }

        /** Chappe's read timeout, which also bounds a closing handshake; the server's default when not set. */
        Builder readTimeout(Duration timeout) {
            readTimeout = timeout;
            return this;
        }

        Builder user(String name, String password, String... roles) {
            users.add(new User(name, password, roles));
            return this;
        }

        /**
         * Deploys the application and starts the server.
         *
         * @throws ServletException when the deployment fails
         */
        WebSocketTestServer start() throws ServletException {
            String xml = """
                    <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
                      <listener><listener-class>%s</listener-class></listener>
                      %s
                    </web-app>""".formatted(Bootstrap.class.getName(), descriptorElements);
            FoyChappeBoot.Builder boot = FoyChappeBoot.builder().contextPath(contextPath)
                    .classLoader(WebSocketTestServer.class.getClassLoader())
                    .webXml(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            initParameters.forEach(boot::initParameter);
            users.forEach(u -> boot.user(u.name(), u.password().toCharArray(), u.roles()));
            FoyChappeBoot.Mounted mounted = ScopedValue.where(LISTENER, listener)
                    .call(() -> boot.build().orElseThrow());
            Handler handler = mounted.mountPrefix().isEmpty() ? mounted.handler()
                    : Router.builder().mount(mounted.mountPrefix(), mounted.handler(), false).build();
            Server.Builder serverBuilder = Server.builder().host("127.0.0.1").port(0).handler(handler);
            if (idleTimeout != null) {
                serverBuilder.idleTimeout(idleTimeout);
            }
            if (readTimeout != null) {
                serverBuilder.readTimeout(readTimeout);
            }
            Server server = serverBuilder.build();
            server.start();
            return new WebSocketTestServer(mounted, server);
        }
    }

    private final FoyChappeBoot.Mounted mounted;
    private final Server server;
    private boolean closed;

    private WebSocketTestServer(FoyChappeBoot.Mounted mounted, Server server) {
        this.mounted = mounted;
        this.server = server;
    }

    static Builder builder(ServletContextListener listener) {
        return new Builder(listener);
    }

    /** Deploys an application at the root context running {@code listener}, and starts the server. */
    static WebSocketTestServer start(ServletContextListener listener) throws ServletException {
        return builder(listener).start();
    }

    int port() {
        return server.port();
    }

    ServletContext context() {
        return mounted.servletContext();
    }

    /** {@code scheme://127.0.0.1:port} followed by {@code path}. */
    URI uri(String scheme, String path) {
        return URI.create(scheme + "://127.0.0.1:" + port() + path);
    }

    /** Undeploys the application (its WebSockets close with 1001), then stops the server; once. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            mounted.close();
        } finally {
            server.stop();
        }
    }
}
