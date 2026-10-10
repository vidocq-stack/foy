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

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.chappe.FoyChappeBoot;
import io.vidocq.foy.spi.cdi.CdiWebComponents;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.Processor;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.module.ModuleFinder;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
            assertThrows(UnsupportedOperationException.class, () -> components.componentClasses().add(Object.class));
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

            var mounted = withTccl(loader, () -> FoyChappeBoot.builder()
                    .beanManager(bm).classLoader(loader).contextPath("/").build().orElseThrow());
            int port;
            try (var s = new ServerSocket(0)) {
                port = s.getLocalPort();
            }
            Server server = Server.builder().host("127.0.0.1").port(port).handler(mounted.handler()).build();
            server.start();
            try {
                var response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/hello")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response::body);
                assertEquals("hello from an injected greeter", response.body());
            } finally {
                server.stop();
                mounted.close();
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

            var mounted = withTccl(loader, () -> FoyChappeBoot.builder()
                    .beanManager(bm).classLoader(loader).contextPath("/").build().orElseThrow());
            int port;
            try (var s = new ServerSocket(0)) {
                port = s.getLocalPort();
            }
            Server server = Server.builder().host("127.0.0.1").port(port).handler(mounted.handler()).build();
            server.start();
            try {
                var client = HttpClient.newHttpClient();
                for (var path : List.of("app", "lib")) {
                    var response = client.send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/" + path)).build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, response.statusCode(), path + ": " + response.body());
                    assertEquals(path, response.body());
                }
            } finally {
                server.stop();
                mounted.close();
            }
        }
    }

    @Test
    @Disabled("Vauban drops MetaAnnotations.addContext contexts when every bean source is pre-processed "
            + "(BUG-20261010-08; upstream issue drafted in docs/superpowers/plans/2026-10-10-cdi-session-context.md, Appendix A)")
    @DisplayName("end to end, processed build: a @SessionScoped bean is one instance per session")
    void sessionScopedBeanThroughAProcessedBuild() throws Exception {
        var result = compile(ProcessorPath.CLASS_PATH, """
                package psess.app;

                @jakarta.enterprise.context.SessionScoped
                public class Cart implements java.io.Serializable {
                    private final String id = java.util.UUID.randomUUID().toString();

                    public String id() {
                        return id;
                    }
                }
                """, """
                package psess.app;

                @jakarta.servlet.annotation.WebServlet("/cart")
                public class CartServlet extends jakarta.servlet.http.HttpServlet {
                    @jakarta.inject.Inject
                    Cart cart;

                    @Override
                    protected void doGet(jakarta.servlet.http.HttpServletRequest q,
                            jakarta.servlet.http.HttpServletResponse r) throws java.io.IOException {
                        r.getWriter().write(cart.id());
                    }
                }
                """);
        assertTrue(result.success(), result::messages);

        try (var loader = result.loader();
             var container = withTccl(loader, () -> boot(loader))) {
            var mounted = withTccl(loader, () -> FoyChappeBoot.builder()
                    .beanManager(container.getBeanManager()).classLoader(loader).contextPath("/").build().orElseThrow());
            int port;
            try (var s = new ServerSocket(0)) {
                port = s.getLocalPort();
            }
            Server server = Server.builder().host("127.0.0.1").port(port).handler(mounted.handler()).build();
            server.start();
            try {
                var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
                var uri = URI.create("http://127.0.0.1:" + port + "/cart");
                var first = client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
                var second = client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, first.statusCode(), first::body);
                assertEquals(first.body(), second.body());
            } finally {
                server.stop();
                mounted.close();
            }
        }
    }

    // ---- harness ----

    private enum ProcessorPath { CLASS_PATH, MODULE_PATH }

    /** One application loader over several compiled archives. */
    private static URLClassLoader loaderOver(Path... archives) throws IOException {
        var urls = new URL[archives.length];
        for (int i = 0; i < archives.length; i++) urls[i] = archives[i].toUri().toURL();
        return new URLClassLoader(urls, FoyWebExtensionTest.class.getClassLoader());
    }

    private static Class<?> scopeOf(jakarta.enterprise.inject.spi.BeanManager bm, Class<?> type) {
        var beans = bm.getBeans(type);
        assertEquals(1, beans.size(), type + " beans: " + beans);
        return beans.iterator().next().getScope();
    }

    private static VaubanContainer boot(ClassLoader loader) {
        return VaubanContainer.builder().classLoader(loader).scanClasspath().build();
    }

    private static <T> T withTccl(ClassLoader loader, Callable<T> action) throws Exception {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return action.call();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private Result compile(ProcessorPath processorPath, String... sources) throws Exception {
        return compile(processorPath, List.of(), sources);
    }

    /** Compiles {@code sources} with {@code libraries} (earlier outputs) on the class path. */
    private Result compile(ProcessorPath processorPath, List<Path> libraries, String... sources) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var root = Files.createTempDirectory(tempDir, "app");
        var src = Files.createDirectories(root.resolve("src"));
        var classes = Files.createDirectories(root.resolve("classes"));
        var generated = Files.createDirectories(root.resolve("generated"));
        var files = new ArrayList<File>();
        for (var source : sources) {
            var pkg = match(source, "package\\s+([\\w.]+)\\s*;");
            var name = match(source, "public\\s+@?(?:class|interface|enum|record)\\s+(\\w+)");
            var file = src.resolve(pkg.replace('.', '/')).resolve(name + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
            files.add(file.toFile());
        }
        var path = testPath();
        var options = new ArrayList<>(List.of("--release", "25", "-proc:full"));
        if (processorPath == ProcessorPath.MODULE_PATH) {
            // javac resolves the processor modules itself; vauban-processor `uses` the BCE service.
            options.add("--processor-module-path");
            options.add(join(path.stream().filter(FoyWebExtensionTest::isModule).toList()));
        }
        try (var processorLoader = processorPath == ProcessorPath.CLASS_PATH ? isolatedLoader(path) : null;
             var fm = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
            fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(generated.toFile()));
            var classPath = new ArrayList<>(path);
            for (var library : libraries) classPath.add(library.toFile());
            fm.setLocation(StandardLocation.CLASS_PATH, classPath);
            var task = compiler.getTask(null, fm, diagnostics, options, null, fm.getJavaFileObjectsFromFiles(files));
            if (processorLoader != null) task.setProcessors(processorsOf(processorLoader));
            boolean ok = task.call();
            var messages = new StringBuilder();
            for (var d : diagnostics.getDiagnostics()) {
                messages.append(d.getKind()).append(": ").append(d.getMessage(Locale.ROOT)).append('\n');
            }
            return new Result(ok, classes, messages.toString());
        }
    }

    /**
     * The class-path flavour of a processor path: one loader over every entry that sees nothing of
     * the test's own loaders but the JDK, so vauban-processor, vauban-core, the Jakarta APIs and
     * FoyWebExtension all live in its unnamed module and FoyWebExtension can only be found through
     * its {@code META-INF/services} file, as with {@code -processorpath} in a Maven build.
     *
     * <p>Neither javac's in-process processor loader (parent: the application loader) nor a plain
     * platform-loader parent isolates enough: the built-in loaders resolve any package of a
     * boot-layer module, so in a module-path test run vauban-core and foy-cdi-vauban would come
     * from their named modules (qualified exports fail, and {@code ServiceLoader} skips class-path
     * providers that resolve to a named module).</p>
     */
    private static URLClassLoader isolatedLoader(List<File> path) throws IOException {
        var urls = new ArrayList<URL>();
        for (var entry : path) urls.add(entry.toURI().toURL());
        return new URLClassLoader(urls.toArray(URL[]::new), new JdkOnlyLoader());
    }

    /** Delegates the JDK's own packages to the platform loader, and nothing else. */
    private static final class JdkOnlyLoader extends ClassLoader {
        private static final Set<String> JDK_PACKAGES = ModuleFinder.ofSystem().findAll().stream()
                .flatMap(m -> m.descriptor().packages().stream()).collect(Collectors.toSet());

        JdkOnlyLoader() {
            super(null);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            int dot = name.lastIndexOf('.');
            if (dot > 0 && JDK_PACKAGES.contains(name.substring(0, dot))) {
                return ClassLoader.getPlatformClassLoader().loadClass(name);
            }
            throw new ClassNotFoundException(name);
        }
    }

    /**
     * The processors javac would find on that path, from the {@code Processor} service files (read
     * by hand: the test module declares no {@code uses}). Vauban then loads its build compatible
     * extensions with its own {@code ServiceLoader} over the same loader: that is the path under test.
     */
    private static List<Processor> processorsOf(ClassLoader loader) throws Exception {
        var processors = new ArrayList<Processor>();
        var resources = loader.getResources("META-INF/services/" + Processor.class.getName());
        while (resources.hasMoreElements()) {
            try (var in = resources.nextElement().openStream()) {
                for (var line : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                    var name = line.replaceFirst("#.*", "").strip();
                    if (name.isEmpty()) continue;
                    processors.add((Processor) loader.loadClass(name).getConstructor().newInstance());
                }
            }
        }
        return processors;
    }

    private static boolean isModule(File entry) {
        if (entry.isDirectory()) return new File(entry, "module-info.class").isFile();
        return entry.getName().endsWith(".jar");
    }

    private static String join(List<File> entries) {
        return entries.stream().map(File::getPath).collect(Collectors.joining(File.pathSeparator));
    }

    private static String match(String source, String regex) {
        var m = Pattern.compile(regex).matcher(source);
        if (!m.find()) throw new IllegalArgumentException("no match for " + regex);
        return m.group(1);
    }

    private static Path classesOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The test's own runtime path (class path and module path), which holds foy-cdi-vauban's
     * classes with its service file, vauban-processor and their dependencies.
     */
    private static List<File> testPath() {
        Set<File> path = new LinkedHashSet<>();
        for (var property : List.of("jdk.module.path", "java.class.path")) {
            for (var e : System.getProperty(property, "").split(File.pathSeparator)) {
                if (!e.isBlank()) path.add(new File(e));
            }
        }
        ModuleLayer.boot().configuration().modules().forEach(rm -> rm.reference().location().ifPresent(uri -> {
            if ("file".equals(uri.getScheme())) path.add(new File(uri));
        }));
        path.add(classesOf(FoyWebExtension.class).toFile());
        return List.copyOf(path);
    }

    private record Result(boolean success, Path classes, String messages) {
        List<String> beansList() throws IOException {
            var path = classes.resolve("META-INF/vauban-beans.list");
            if (!Files.exists(path)) return List.of();
            return Files.readAllLines(path).stream().map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        }

        String errorLine(String marker) {
            return messages.lines().filter(l -> l.startsWith("ERROR") && l.contains(marker))
                    .findFirst().orElseThrow(() -> new AssertionError("no ERROR with " + marker + ":\n" + messages));
        }

        URLClassLoader loader() throws IOException {
            return loaderOver(classes);
        }
    }
}
