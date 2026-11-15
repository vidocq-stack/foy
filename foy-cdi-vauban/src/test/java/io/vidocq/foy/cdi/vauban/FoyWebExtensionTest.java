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

import io.vidocq.foy.cdi.vauban.TestApps.ProcessorPath;
import io.vidocq.foy.cdi.vauban.TestApps.Result;
import io.vidocq.foy.spi.cdi.CdiWebComponents;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.vidocq.foy.cdi.vauban.TestApps.boot;
import static io.vidocq.foy.cdi.vauban.TestApps.classesOf;
import static io.vidocq.foy.cdi.vauban.TestApps.deploy;
import static io.vidocq.foy.cdi.vauban.TestApps.loaderOver;
import static io.vidocq.foy.cdi.vauban.TestApps.withTccl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FoyWebExtension} as vauban's annotation processor runs it: each test compiles a tiny
 * application in-process with {@code javax.tools}, vauban-processor loaded from a processor path
 * that also holds this module's classes, and the extension found by vauban itself through
 * {@code ServiceLoader} (service file or module {@code provides}), as in a Maven build with
 * foy-cdi-vauban in {@code <annotationProcessorPaths>}.
 * Runtime assertions come from a {@link VaubanContainer} booted from the compiled output.
 */
@DisplayName("FoyWebExtension: build compatible extension run by vauban-processor")
class FoyWebExtensionTest {

    @TempDir
    Path tempDir;

    // ---- discovery of the extension itself ----

    @Test
    @DisplayName("the extension is declared as a BuildCompatibleExtension provider in its module descriptor")
    void moduleDescriptorProvidesTheExtension() {
        var descriptor = FoyWebExtension.class.getModule().getDescriptor();
        if (descriptor == null) {
            // Classpath run: the descriptor is still on disk next to the classes.
            assertTrue(Files.exists(classesOf(FoyWebExtension.class).resolve("module-info.class")));
            return;
        }
        var provides = descriptor.provides().stream()
                .filter(p -> p.service().equals(BuildCompatibleExtension.class.getName()))
                .flatMap(p -> p.providers().stream())
                .toList();
        assertEquals(List.of(FoyWebExtension.class.getName()), provides);
    }

    @Test
    @DisplayName("vauban-processor on a -processorpath finds the extension through META-INF/services")
    void processorPathDiscoversTheExtension() throws Exception {
        var result = compile(ProcessorPath.CLASS_PATH, """
                package sl.app;

                @jakarta.servlet.annotation.WebServlet("/sl")
                public class SlServlet extends jakarta.servlet.http.HttpServlet {
                }
                """);
        assertTrue(result.success(), result::messages);
        assertFalse(result.messages().contains("will not run"), result::messages);
        // Only FoyWebExtension can make this unscoped class a bean.
        assertTrue(result.beansList().contains("sl.app.SlServlet"), result::messages);
    }

    @Test
    @DisplayName("vauban-processor on a --processor-module-path finds the extension through its module's provides")
    void processorModulePathDiscoversTheExtension() throws Exception {
        var result = compile(ProcessorPath.MODULE_PATH, """
                package slm.app;

                @jakarta.servlet.annotation.WebServlet("/slm")
                public class SlmServlet extends jakarta.servlet.http.HttpServlet {
                }
                """);
        assertTrue(result.success(), result::messages);
        assertTrue(result.beansList().contains("slm.app.SlmServlet"), result::messages);
    }

    // ---- @Validation ----

    @Test
    @DisplayName("two servlets with the same name fail the compilation, naming the name and both classes")
    void duplicateServletNameFailsCompilation() throws Exception {
        var result = compile(ProcessorPath.CLASS_PATH, """
                package dup.app;

                @jakarta.servlet.annotation.WebServlet(name = "dup", urlPatterns = "/a")
                public class A extends jakarta.servlet.http.HttpServlet {
                }
                """, """
                package dup.app;

                @jakarta.servlet.annotation.WebServlet(name = "dup", urlPatterns = "/b")
                public class B extends jakarta.servlet.http.HttpServlet {
                }
                """);
        assertFalse(result.success(), result::messages);
        var error = result.errorLine("[Vauban BCE]");
        assertTrue(error.contains("servlet name 'dup'"), result::messages);
        assertTrue(error.contains("dup.app.A") && error.contains("dup.app.B"), result::messages);
    }

    @Test
    @DisplayName("a filter without filterName is named after its binary class name, so it clashes with that explicit name")
    void defaultFilterNameIsTheBinaryClassName() throws Exception {
        var result = compile(ProcessorPath.CLASS_PATH, """
                package dflt.app;

                @jakarta.servlet.annotation.WebFilter("/*")
                public class F implements jakarta.servlet.Filter {
                    public void doFilter(jakarta.servlet.ServletRequest q, jakarta.servlet.ServletResponse r,
                            jakarta.servlet.FilterChain c) {}
                }
                """, """
                package dflt.app;

                @jakarta.servlet.annotation.WebFilter(filterName = "dflt.app.F", urlPatterns = "/x")
                public class G implements jakarta.servlet.Filter {
                    public void doFilter(jakarta.servlet.ServletRequest q, jakarta.servlet.ServletResponse r,
                            jakarta.servlet.FilterChain c) {}
                }
                """);
        assertFalse(result.success(), result::messages);
        var error = result.errorLine("[Vauban BCE]");
        assertTrue(error.contains("filter name 'dflt.app.F'"), result::messages);
        assertTrue(error.contains("dflt.app.G"), result::messages);
    }

    @Test
    @DisplayName("a servlet and a filter may share a name: they live in separate namespaces")
    void servletAndFilterNamesDoNotClash() throws Exception {
        var result = compile(ProcessorPath.CLASS_PATH, """
                package ns.app;

                @jakarta.servlet.annotation.WebServlet(name = "same", urlPatterns = "/s")
                public class S extends jakarta.servlet.http.HttpServlet {
                }
                """, """
                package ns.app;

                @jakarta.servlet.annotation.WebFilter(filterName = "same", urlPatterns = "/*")
                public class F implements jakarta.servlet.Filter {
                    public void doFilter(jakarta.servlet.ServletRequest q, jakarta.servlet.ServletResponse r,
                            jakarta.servlet.FilterChain c) {}
                }
                """);
        assertTrue(result.success(), result::messages);
    }

    // ---- @Enhancement + @Registration + @Synthesis ----

    @Test
    @DisplayName("unscoped web components become @Dependent, scoped ones keep their scope, and only web components are indexed")
    void scopesAndIndex() throws Exception {
        var result = compile(ProcessorPath.CLASS_PATH, """
                package scope.app;

                @jakarta.servlet.annotation.WebServlet("/plain")
                public class Plain extends jakarta.servlet.http.HttpServlet {
                }
                """, """
                package scope.app;

                @jakarta.inject.Singleton
                @jakarta.servlet.annotation.WebServlet("/scoped")
                public class Scoped extends jakarta.servlet.http.HttpServlet {
                }
                """, """
                package scope.app;

                @jakarta.servlet.annotation.WebListener
                public class Listener implements jakarta.servlet.ServletContextListener {
                }
                """, """
                package scope.app;

                /** A CDI bean that is a Servlet but not a web component. */
                @jakarta.enterprise.context.Dependent
                public class NotWeb extends jakarta.servlet.http.HttpServlet {
                }
                """);
        assertTrue(result.success(), result::messages);

        try (var loader = result.loader();
             var container = withTccl(loader, () -> boot(loader))) {
            var bm = container.getBeanManager();
            assertEquals(Dependent.class, scopeOf(bm, loader.loadClass("scope.app.Plain")));
            assertEquals(Dependent.class, scopeOf(bm, loader.loadClass("scope.app.Listener")));
            assertEquals(Singleton.class, scopeOf(bm, loader.loadClass("scope.app.Scoped")));

            var components = withTccl(loader, () -> bm.createInstance().select(CdiWebComponents.class).get());
            var names = components.componentClasses().stream().map(Class::getName).toList();
            assertEquals(List.of("scope.app.Listener", "scope.app.Plain", "scope.app.Scoped"), names, "sorted by binary name");
            for (var cls : components.componentClasses()) {
                assertEquals(loader, cls.getClassLoader(), "loaded from the application loader: " + cls);
            }
            var classes = components.componentClasses();
            assertThrows(UnsupportedOperationException.class, () -> classes.add(Object.class));
        }
    }

    @Test
    @DisplayName("end to end: an unscoped @WebServlet with an @Inject field is served by FoyChappeBoot with vauban's BeanManager")
    void endToEndThroughFoyChappeBoot() throws Exception {
        var result = compile(ProcessorPath.CLASS_PATH, """
                package e2e.app;

                @jakarta.enterprise.context.ApplicationScoped
                public class Greeter {
                    public String greet() {
                        return "hello from an injected greeter";
                    }
                }
                """, """
                package e2e.app;

                @jakarta.servlet.annotation.WebServlet("/hello")
                public class HelloServlet extends jakarta.servlet.http.HttpServlet {
                    @jakarta.inject.Inject
                    Greeter greeter;

                    @Override
                    protected void doGet(jakarta.servlet.http.HttpServletRequest q,
                            jakarta.servlet.http.HttpServletResponse r) throws java.io.IOException {
                        r.getWriter().write(greeter.greet());
                    }
                }
                """);
        assertTrue(result.success(), result::messages);

        try (var loader = result.loader();
             var container = withTccl(loader, () -> boot(loader))) {
            var bm = container.getBeanManager();
            var components = withTccl(loader, () -> bm.createInstance().select(CdiWebComponents.class).get());
            assertEquals(List.of(loader.loadClass("e2e.app.HelloServlet")), components.componentClasses());

            try (var app = deploy(container, loader, "/")) {
                var response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(app.uri("/hello")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response::body);
                assertEquals("hello from an injected greeter", response.body());
            }
        }
    }

    @Test
    @DisplayName("end to end: two archives built with the extension give two indexes, merged by Foy")
    void twoArchivesAreServedTogether() throws Exception {
        var library = compile(ProcessorPath.CLASS_PATH, """
                package two.lib;

                @jakarta.servlet.annotation.WebServlet("/lib")
                public class LibServlet extends jakarta.servlet.http.HttpServlet {
                    @Override
                    protected void doGet(jakarta.servlet.http.HttpServletRequest q,
                            jakarta.servlet.http.HttpServletResponse r) throws java.io.IOException {
                        r.getWriter().write("lib");
                    }
                }
                """);
        assertTrue(library.success(), library::messages);
        var app = compile(ProcessorPath.CLASS_PATH, List.of(library.classes()), """
                package two.app;

                @jakarta.servlet.annotation.WebServlet("/app")
                public class AppServlet extends jakarta.servlet.http.HttpServlet {
                    @Override
                    protected void doGet(jakarta.servlet.http.HttpServletRequest q,
                            jakarta.servlet.http.HttpServletResponse r) throws java.io.IOException {
                        r.getWriter().write("app");
                    }
                }
                """);
        assertTrue(app.success(), app::messages);

        try (var loader = loaderOver(app.classes(), library.classes());
             var container = withTccl(loader, () -> boot(loader))) {
            var bm = container.getBeanManager();
            assertEquals(2, bm.getBeans(CdiWebComponents.class).size(), "one index per archive");

            try (var served = deploy(container, loader, "/")) {
                var client = HttpClient.newHttpClient();
                for (var path : List.of("app", "lib")) {
                    var response = client.send(
                            HttpRequest.newBuilder(served.uri("/" + path)).build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, response.statusCode(), path + ": " + response.body());
                    assertEquals(path, response.body());
                }
            }
        }
    }

    @Test
    @DisplayName("@RequestScoped: an application compiled with vauban's processor gets one instance per request, concurrent requests apart (vauban#147)")
    void requestScopedThroughFoyChappeBoot() throws Exception {
        var result = compile(ProcessorPath.CLASS_PATH, """
                package request.app;

                @jakarta.enterprise.context.RequestScoped
                public class Token {
                    private final String id = java.util.UUID.randomUUID().toString();

                    public String id() {
                        return id;
                    }
                }
                """, """
                package request.app;

                @jakarta.servlet.annotation.WebServlet("/token")
                public class TokenServlet extends jakarta.servlet.http.HttpServlet {
                    @jakarta.inject.Inject
                    Token first;

                    @jakarta.inject.Inject
                    Token second;

                    @Override
                    protected void doGet(jakarta.servlet.http.HttpServletRequest q,
                            jakarta.servlet.http.HttpServletResponse r) throws java.io.IOException {
                        String before = first.id();
                        try {
                            Thread.sleep(50); // let the concurrent requests overlap
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        r.getWriter().write(before + "|" + second.id());
                    }
                }
                """);
        assertTrue(result.success(), result::messages);

        try (var loader = result.loader();
             var container = withTccl(loader, () -> boot(loader));
             var app = deploy(container, loader, "/")) {
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder(app.uri("/token")).build();
            var futures = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
            for (int i = 0; i < 8; i++) {
                futures.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
            }
            var ids = new java.util.HashSet<String>();
            for (var future : futures) {
                var response = future.get();
                assertEquals(200, response.statusCode(), response::body);
                var parts = response.body().split("\\|", -1);
                assertEquals(parts[0], parts[1], "one instance within a request: " + response.body());
                ids.add(parts[0]);
            }
            assertEquals(8, ids.size(), "one instance per request, concurrent requests apart: " + ids);
        }
    }

    @Test
    @DisplayName("@SessionScoped: an application compiled with vauban's processor keeps one instance per HTTP session (foy#21)")
    void sessionScopedThroughFoyChappeBoot() throws Exception {
        var result = compile(ProcessorPath.CLASS_PATH, """
                package session.app;

                @jakarta.enterprise.context.SessionScoped
                public class Counter implements java.io.Serializable {
                    private int count;

                    public int next() {
                        return ++count;
                    }
                }
                """, """
                package session.app;

                @jakarta.servlet.annotation.WebServlet("/count")
                public class CountServlet extends jakarta.servlet.http.HttpServlet {
                    @jakarta.inject.Inject
                    Counter counter;

                    @Override
                    protected void doGet(jakarta.servlet.http.HttpServletRequest q,
                            jakarta.servlet.http.HttpServletResponse r) throws java.io.IOException {
                        r.getWriter().write(String.valueOf(counter.next()));
                    }
                }
                """);
        assertTrue(result.success(), result::messages);

        try (var loader = result.loader();
             var container = withTccl(loader, () -> boot(loader));
             var app = deploy(container, loader, "/")) {
            var uri = app.uri("/count");
            var alice = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build();
            var bob = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build();
            assertEquals("1", alice.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).body());
            assertEquals("2", alice.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).body(),
                    "the same instance for the same session");
            assertEquals("1", bob.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).body(),
                    "another session, another instance");
        }
    }

    private static Class<?> scopeOf(jakarta.enterprise.inject.spi.BeanManager bm, Class<?> type) {
        var beans = bm.getBeans(type);
        assertEquals(1, beans.size(), type + " beans: " + beans);
        return beans.iterator().next().getScope();
    }

    private Result compile(ProcessorPath processorPath, String... sources) throws Exception {
        return compile(processorPath, List.of(), sources);
    }

    private Result compile(ProcessorPath processorPath, List<Path> libraries, String... sources) throws Exception {
        return TestApps.compile(tempDir, processorPath, libraries, sources);
    }
}
