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
package io.vidocq.foy.chappe;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.spi.cdi.CdiContextListeners;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** The session export and import of {@code Mounted}, and the import scope the builder gives the deployment. */
class FoyChappeBootSessionSwapTest {

    /** Opens a session on each request and reports whether it is new. */
    public static class Sessions extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            boolean fresh = req.getSession().isNew();
            resp.getWriter().write("new=" + fresh);
        }
    }

    /** An adapter that records the order in which its import scope is entered and left. */
    private record Tracing(String name, List<String> trace) implements CdiContextListeners {
        @Override
        public List<EventListener> listeners(BeanManager beanManager) {
            return List.of();
        }

        @Override
        public <T> T aroundSessionImport(BeanManager beanManager, Supplier<T> importer) {
            trace.add(name + " in");
            T result = importer.get();
            trace.add(name + " out");
            return result;
        }
    }

    private static FoyChappeBoot.Builder app(BeanManager beanManager) {
        String xml = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <servlet><servlet-name>s</servlet-name><servlet-class>%s</servlet-class></servlet>
              <servlet-mapping><servlet-name>s</servlet-name><url-pattern>/s</url-pattern></servlet-mapping>
            </web-app>""".formatted(Sessions.class.getName());
        var builder = FoyChappeBoot.builder().contextPath("/").discoverPluggability(false)
                .classLoader(FoyChappeBootSessionSwapTest.class.getClassLoader())
                .webXml(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return beanManager == null ? builder : builder.beanManager(beanManager);
    }

    @SuppressWarnings("ReferenceEquality") // equals of a fake: identity is the contract
    private static BeanManager emptyBeanManager() {
        return (BeanManager) Proxy.newProxyInstance(BeanManager.class.getClassLoader(),
                new Class<?>[]{BeanManager.class}, (p, m, a) -> switch (m.getName()) {
                    case "getBeans" -> Set.of();
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    case "toString" -> "FakeBeanManager";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static HttpResponse<String> get(HttpClient client, int port, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/s")).GET();
        if (cookie != null) request.header("Cookie", cookie);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aSessionMovesBetweenTwoBuilds() throws Exception {
        var first = app(null).build().orElseThrow();
        byte[] export;
        String cookie;
        try (var client = HttpClient.newHttpClient();
             Server server = Server.builder().host("127.0.0.1").port(0).handler(first.handler()).build()) {
            server.start();
            HttpResponse<String> response = get(client, server.port(), null);
            assertEquals("new=true", response.body());
            String header = response.headers().firstValue("set-cookie").orElseThrow();
            cookie = header.substring(0, header.indexOf(';'));
            assertEquals(1, first.activeSessions());
            export = first.closeExportingSessions();
            assertEquals(0, first.activeSessions());
        }

        var second = app(null).build().orElseThrow();
        try (second; var client = HttpClient.newHttpClient()) {
            assertEquals(1, second.importSessions(export));
            try (Server server = Server.builder().host("127.0.0.1").port(0).handler(second.handler()).build()) {
                server.start();
                HttpResponse<String> response = get(client, server.port(), cookie);
                assertEquals("new=false", response.body());
                assertTrue(response.headers().firstValue("set-cookie").isEmpty());
            }
        }
    }

    @Test
    void anExportOfNoSessionImportsNone() throws Exception {
        var first = app(null).build().orElseThrow();
        byte[] export = first.closeExportingSessions();
        try (var second = app(null).build().orElseThrow()) {
            assertEquals(0, second.importSessions(export));
        }
    }

    /** The bean managers the recording adapter's import scope saw, as a value to compare. */
    private static List<BeanManager> imports() {
        return List.copyOf(RecordingCdiAdapter.IMPORTS);
    }

    /** An export of one session, written by a deployment that was given no bean manager. */
    private static byte[] exportOneSession() throws Exception {
        var first = app(null).build().orElseThrow();
        try (var client = HttpClient.newHttpClient();
             Server server = Server.builder().host("127.0.0.1").port(0).handler(first.handler()).build()) {
            server.start();
            get(client, server.port(), null);
        }
        return first.closeExportingSessions();
    }

    @Test
    void aDeploymentWithABeanManagerImportsInsideTheAdaptersScope() throws Exception {
        byte[] export = exportOneSession();
        BeanManager beanManager = emptyBeanManager();
        RecordingCdiAdapter.IMPORTS.clear();

        try (var second = app(beanManager).build().orElseThrow()) {
            assertEquals(List.of(), imports(), "the scope ran at build");
            assertEquals(1, second.importSessions(export));
        }

        assertEquals(List.of(beanManager), imports());
    }

    @Test
    void aDeploymentWithoutABeanManagerImportsWithNoScope() throws Exception {
        byte[] export = exportOneSession();
        RecordingCdiAdapter.IMPORTS.clear();

        try (var second = app(null).build().orElseThrow()) {
            assertEquals(1, second.importSessions(export));
        }

        assertEquals(List.of(), imports());
    }

    @Test
    void theScopeNestsTheAdaptersAroundTheImportInTheirOrder() {
        List<String> trace = new ArrayList<>();
        var scope = FoyChappeBoot.Builder.sessionImportScope(emptyBeanManager(),
                List.of(new Tracing("a", trace), new Tracing("b", trace)));

        String result = scope.around(() -> {
            trace.add("import");
            return "done";
        });

        assertEquals("done", result);
        assertEquals(List.of("a in", "b in", "import", "b out", "a out"), trace);
    }

    @Test
    void withoutAdapterTheScopeOnlyRunsTheImport() {
        var scope = FoyChappeBoot.Builder.sessionImportScope(emptyBeanManager(), List.of());

        assertEquals(7, scope.around(() -> 7).intValue());
    }

    @Test
    void theDefaultMethodOnlyRunsTheImporter() {
        CdiContextListeners adapter = beanManager -> List.of();

        assertEquals("ran", adapter.aroundSessionImport(emptyBeanManager(), () -> "ran"));
    }
}
