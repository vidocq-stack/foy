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
package io.vidocq.foy.cdi.vauban;

import io.vidocq.foy.cdi.vauban.TestApps.Deployed;
import io.vidocq.foy.cdi.vauban.TestApps.ProcessorPath;
import io.vidocq.foy.cdi.vauban.TestApps.Result;
import io.vidocq.vauban.core.container.VaubanContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.vidocq.foy.cdi.vauban.TestApps.boot;
import static io.vidocq.foy.cdi.vauban.TestApps.deploy;
import static io.vidocq.foy.cdi.vauban.TestApps.withTccl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CDI session state moves with the HTTP session across a swap of the application, as Vidocq's dev mode swaps it: the
 * application deployed on container A, its sessions exported at undeploy, the same application deployed on container
 * B, the sessions imported before B serves. A and B load the application through two class loaders, so every class
 * of the application is another {@code Class} in B. The import runs inside {@link VaubanCdiContextListeners}'s
 * {@code aroundSessionImport}, which binds B for Vauban's serial forms.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("CDI session state moves with the HTTP session from one container to the next")
class CdiSessionSwapTest {

    /** The logger of Foy's session export and import. */
    private static final String SESSION_MANAGER_LOGGER = "io.vidocq.foy.internal.session.SessionManager";

    private static final String CONTEXT_PATH = "/app";
    private static final String CART = "/cart";
    private static final String APPLE = "apple";
    private static final String ADD_APPLE = CART + "?add=" + APPLE;

    /** One instance per container: its id tells the containers apart. */
    private static final String SERVICE = """
            package swap.app;

            @jakarta.enterprise.context.ApplicationScoped
            public class Service {
                private final String id = java.util.UUID.randomUUID().toString();

                public String id() {
                    return id;
                }
            }
            """;

    private static final String PING = """
            package swap.app;

            public record Ping(String what) {}
            """;

    /** What the {@code Ping} observer of one container heard. */
    private static final String PINGS = """
            package swap.app;

            @jakarta.enterprise.context.ApplicationScoped
            public class Pings {
                private final java.util.List<String> heard = new java.util.concurrent.CopyOnWriteArrayList<>();

                void onPing(@jakarta.enterprise.event.Observes Ping ping) {
                    heard.add(ping.what());
                }

                public String heard() {
                    return String.join(",", heard);
                }
            }
            """;

    /**
     * The session state that moves: its items, the client proxies of a service and of the built-in HttpSession bean,
     * and an injected built-in Event.
     */
    private static final String CART_BEAN = """
            package swap.app;

            import jakarta.enterprise.event.Event;
            import jakarta.inject.Inject;
            import jakarta.servlet.http.HttpSession;
            import java.util.ArrayList;
            import java.util.List;

            @jakarta.enterprise.context.SessionScoped
            public class Cart implements java.io.Serializable {
                private static final long serialVersionUID = 1L;

                @Inject Service service;
                @Inject HttpSession session;
                @Inject Event<Ping> pings;
                private final List<String> items = new ArrayList<>();

                public void add(String item) {
                    items.add(item);
                }

                public String items() {
                    return String.join(",", items);
                }

                public Service service() {
                    return service;
                }

                /** The id of the session the HttpSession bean gives this cart. */
                public String sessionId() {
                    return session.getId();
                }

                public void ping(String what) {
                    pings.fire(new Ping(what));
                }
            }
            """;

    /** A session bean that cannot be written: the session that holds it cannot move. */
    private static final String NOTEPAD = """
            package swap.app;

            @jakarta.enterprise.context.SessionScoped
            public class Notepad {
                private final StringBuilder lines = new StringBuilder();

                public void write(String line) {
                    lines.append(line);
                }
            }
            """;

    private static final String CART_SERVLET = """
            package swap.app;

            import jakarta.inject.Inject;
            import jakarta.servlet.http.HttpServlet;
            import jakarta.servlet.http.HttpServletRequest;
            import jakarta.servlet.http.HttpServletResponse;

            @jakarta.servlet.annotation.WebServlet("/cart")
            public class CartServlet extends HttpServlet {
                @Inject Cart cart;
                @Inject Notepad notepad;
                @Inject Service service;
                @Inject Pings pings;

                @Override
                protected void doGet(HttpServletRequest q, HttpServletResponse r) throws java.io.IOException {
                    if (q.getParameter("add") != null) {
                        cart.add(q.getParameter("add"));
                    }
                    if (q.getParameter("ping") != null) {
                        cart.ping(q.getParameter("ping"));
                    }
                    if (q.getParameter("note") != null) {
                        notepad.write(q.getParameter("note"));
                    }
                    r.getWriter().write(String.join("|", cart.items(), cart.service().id(), service.id(),
                            String.valueOf(cart.service() == service), q.getSession().getId(), cart.sessionId(),
                            pings.heard()));
                }
            }
            """;

    private Result app;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    void compileTheApplication(@TempDir Path tempDir) throws Exception {
        app = TestApps.compile(tempDir, ProcessorPath.CLASS_PATH, List.of(), SERVICE, PING, PINGS, CART_BEAN, NOTEPAD,
                CART_SERVLET);
        assertTrue(app.success(), app::messages);
    }

    @AfterAll
    void closeTheClient() {
        client.close();
    }

    @Test
    @DisplayName("a @SessionScoped bean keeps its state, and its injected @ApplicationScoped service is the new container's")
    void aSessionBeanMovesAndRebindsToTheNewContainer() throws Exception {
        var alice = new Visitor();
        try (var swap = new Swap()) {
            alice.get(swap.uri(ADD_APPLE));
            View inA = alice.get(swap.uri("/cart?add=pear"));
            assertEquals(inA.service(), inA.cartService(), "in A, the cart's service is A's");

            assertEquals(1, swap.toB());
            View inB = alice.get(swap.uri(CART));

            assertEquals("apple,pear", inB.items(), "the cart's state moved");
            assertEquals(inA.session(), inB.session(), "the old JSESSIONID finds the moved session");
            assertFalse(alice.givenANewSession(), "no new session in B");
            assertNotEquals(inA.service(), inB.cartService(), "the cart's service is no longer A's");
            assertEquals(inB.service(), inB.cartService(), "the cart's service is B's");
            assertTrue(inB.sameServiceProxy(), "the cart holds B's own client proxy of the service");
        }
    }

    @Test
    @DisplayName("a bean that injects HttpSession moves, and its HttpSession is then the imported session")
    void theInjectedHttpSessionIsTheImportedSession() throws Exception {
        var alice = new Visitor();
        try (var swap = new Swap()) {
            View inA = alice.get(swap.uri(ADD_APPLE));
            assertEquals(inA.session(), inA.cartSession(), "in A, the HttpSession bean holds the session itself");

            assertEquals(1, swap.toB(), "the session that holds the HttpSession bean's instance moves");
            View inB = alice.get(swap.uri(CART));

            assertEquals(APPLE, inB.items());
            assertEquals(inA.session(), inB.cartSession(), "the HttpSession bean gives the imported session");
            assertEquals(inB.session(), inB.cartSession(), "the request's own session");
        }
    }

    @Test
    @DisplayName("an injected Event of a moved bean fires to the new container's observers")
    void anInjectedEventFiresToTheNewContainersObservers() throws Exception {
        var alice = new Visitor();
        try (var swap = new Swap()) {
            assertEquals("in-a", alice.get(swap.uri("/cart?ping=in-a")).heard());

            assertEquals(1, swap.toB());

            assertEquals("in-b", alice.get(swap.uri("/cart?ping=in-b")).heard(),
                    "B's observer heard the moved cart's event, and only that one");
        }
    }

    @Test
    @DisplayName("a session that holds a non-Serializable @SessionScoped bean stays behind whole, with Foy's WARNING; the other sessions move")
    void aNonSerializableSessionBeanKeepsItsSessionBehind() throws Exception {
        var alice = new Visitor();
        var bob = new Visitor();
        try (var log = LogRecords.of(SESSION_MANAGER_LOGGER);
             var swap = new Swap()) {
            alice.get(swap.uri(ADD_APPLE));
            View bobInA = bob.get(swap.uri("/cart?add=pear&note=draft"));

            assertEquals(1, swap.toB(), "alice's session moves, bob's stays behind");

            var warnings = log.warnings();
            assertEquals(1, warnings.size(), warnings::toString);
            var warning = warnings.getFirst();
            assertTrue(warning.contains(bobInA.session().substring(0, 8)) && warning.contains("swap.app.Notepad")
                    && warning.contains("is not Serializable"), warning);
            assertEquals(APPLE, alice.get(swap.uri(CART)).items(), "alice's cart moved");
            View bobInB = bob.get(swap.uri(CART));
            assertTrue(bob.givenANewSession(), "bob starts a new session");
            assertNotEquals(bobInA.session(), bobInB.session());
            assertEquals("", bobInB.items(), "bob's session was dropped whole, its serializable cart too");
        }
    }

    /** What {@code /cart} answers. */
    private record View(String items, String cartService, String service, boolean sameServiceProxy, String session,
            String cartSession, String heard) {

        static View of(String body) {
            var parts = body.split("\\|", -1);
            assertEquals(7, parts.length, body);
            return new View(parts[0], parts[1], parts[2], Boolean.parseBoolean(parts[3]), parts[4], parts[5],
                    parts[6]);
        }
    }

    /** A browser that keeps the session cookie it was given, from one server to the next. */
    private final class Visitor {
        private String cookie;
        private boolean newSession;

        View get(URI uri) throws IOException, InterruptedException {
            var request = HttpRequest.newBuilder(uri);
            if (cookie != null) {
                request.header("Cookie", cookie);
            }
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response::body);
            var setCookie = response.headers().firstValue("set-cookie");
            newSession = setCookie.isPresent();
            setCookie.ifPresent(header -> cookie = header.substring(0, header.indexOf(';')));
            return View.of(response.body());
        }

        /** Whether the last answer started a session. */
        boolean givenANewSession() {
            return newSession;
        }
    }

    /**
     * The application deployed at {@link #CONTEXT_PATH} on container A; {@link #toB()} swaps it for the same
     * application on container B, through another class loader, as Vidocq's dev mode does.
     */
    private final class Swap implements AutoCloseable {
        private URLClassLoader loader;
        private VaubanContainer container;
        private Deployed deployed;

        Swap() throws Exception {
            open(new byte[0]);
        }

        URI uri(String path) {
            return deployed.uri(CONTEXT_PATH + path);
        }

        /**
         * Exports A's sessions as its undeploy, closes A, and deploys B with the sessions imported before it serves.
         *
         * @return the number of sessions imported
         */
        int toB() throws Exception {
            byte[] export = deployed.mounted().closeExportingSessions();
            close();
            return open(export);
        }

        /** Deploys the application on a new container, importing {@code export} when it holds anything. */
        private int open(byte[] export) throws Exception {
            var imported = new AtomicInteger();
            loader = app.loader();
            container = withTccl(loader, () -> boot(loader));
            deployed = deploy(container, loader, CONTEXT_PATH, mounted -> {
                if (export.length > 0) {
                    imported.set(mounted.importSessions(export));
                }
            });
            return imported.get();
        }

        @Override
        public void close() throws IOException {
            try (var _ = loader; var _ = container; var _ = deployed) {
                // Closes the deployment, then the container, then the loader.
            }
        }
    }
}
