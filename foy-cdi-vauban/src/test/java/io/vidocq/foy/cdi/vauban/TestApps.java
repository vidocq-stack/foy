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
import io.vidocq.vauban.core.container.VaubanContainer;

import javax.annotation.processing.Processor;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
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

/**
 * The harness of the tests that compile a tiny application in-process with {@code javax.tools},
 * vauban-processor loaded from a processor path that also holds this module's classes, then boot a
 * {@link VaubanContainer} over the output and serve it through {@link FoyChappeBoot}.
 */
final class TestApps {

    private TestApps() {}

    /** How vauban-processor reaches javac: a {@code -processorpath} or a {@code --processor-module-path}. */
    enum ProcessorPath { CLASS_PATH, MODULE_PATH }

    /** One application loader over several compiled archives. */
    static URLClassLoader loaderOver(Path... archives) throws IOException {
        var urls = new URL[archives.length];
        for (int i = 0; i < archives.length; i++) urls[i] = archives[i].toUri().toURL();
        return new URLClassLoader(urls, TestApps.class.getClassLoader());
    }

    /** A container over everything {@code loader} indexes, this module's own beans included. */
    static VaubanContainer boot(ClassLoader loader) {
        return VaubanContainer.builder().classLoader(loader).scanClasspath().build();
    }

    static <T> T withTccl(ClassLoader loader, Callable<T> action) throws Exception {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return action.call();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * The application of {@code loader} deployed at {@code contextPath} on {@code container}, served by
     * its own server on a free port.
     */
    static Deployed deploy(VaubanContainer container, ClassLoader loader, String contextPath) throws Exception {
        var mounted = withTccl(loader, () -> FoyChappeBoot.builder().beanManager(container.getBeanManager())
                .classLoader(loader).contextPath(contextPath).build().orElseThrow());
        Server server = Server.builder().host("127.0.0.1").port(0).handler(mounted.handler()).build();
        try {
            server.start();
        } catch (RuntimeException e) {
            mounted.close();
            throw e;
        }
        return new Deployed(mounted, server);
    }

    /** A deployed application and its server; closing it undeploys the application, then stops the server. */
    record Deployed(FoyChappeBoot.Mounted mounted, Server server) implements AutoCloseable {

        /** The address of {@code path} (context path included) on this server. */
        URI uri(String path) {
            return URI.create("http://127.0.0.1:" + server.port() + path);
        }

        @Override
        public void close() {
            try (server; mounted) { // closes mounted, then server
                // Only the closing order matters here.
            }
        }
    }

    /** Compiles {@code sources} with {@code libraries} (earlier outputs) on the class path. */
    static Result compile(Path tempDir, ProcessorPath processorPath, List<Path> libraries, String... sources)
            throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var root = Files.createTempDirectory(tempDir, "app");
        var src = Files.createDirectories(root.resolve("src"));
        var classes = Files.createDirectories(root.resolve("classes"));
        var generated = Files.createDirectories(root.resolve("generated"));
        var files = new ArrayList<File>();
        for (var source : sources) {
            var pkg = match(source, "package\\s+([\\w.]+)\\s*;");
            var name = match(source, "public\\s+(?:final\\s+)?@?(?:class|interface|enum|record)\\s+(\\w+)");
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
            options.add(join(path.stream().filter(TestApps::isModule).toList()));
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

    static Path classesOf(Class<?> type) {
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
            for (var e : System.getProperty(property, "").split(File.pathSeparator, -1)) {
                if (!e.isBlank()) path.add(new File(e));
            }
        }
        ModuleLayer.boot().configuration().modules().forEach(rm -> rm.reference().location().ifPresent(uri -> {
            if ("file".equals(uri.getScheme())) path.add(new File(uri));
        }));
        path.add(classesOf(FoyWebExtension.class).toFile());
        return List.copyOf(path);
    }

    /** What a compilation produced: whether it succeeded, its class output and its diagnostics. */
    record Result(boolean success, Path classes, String messages) {
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
