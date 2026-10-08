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

import io.vidocq.foy.spi.cdi.CdiWebComponents;
import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.processor.VaubanProcessor;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.Validation;
import jakarta.inject.Singleton;
import jakarta.servlet.annotation.WebServlet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spike (Task 2.9): what vauban's annotation processor supports when it runs a build compatible
 * extension at build time. Each test compiles a tiny application in-process with
 * {@link VaubanProcessor} (the test extension injected through {@code overrideBceClasses}, as in
 * vauban's own {@code BceCompileTimeTest}), then boots a {@link VaubanContainer} from the compiled
 * output. The assertions encode the answer observed; {@code SPIKE-BCE.md} records it.
 */
@DisplayName("Spike: vauban build-time build compatible extension capabilities")
class BceCapabilitySpikeTest {

    private static final String APP_SERVLET = """
            package spike.app;

            @jakarta.servlet.annotation.WebServlet("/spike")
            public class SpikeServlet extends jakarta.servlet.http.HttpServlet {
            }
            """;

    @TempDir
    Path tempDir;

    // ---- Q1: @Enhancement reaches a class without a bean-defining annotation ----

    /** Adds {@code @Dependent} to every {@code @WebServlet} class. */
    public static class DependentServletBce implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withAnnotations = WebServlet.class)
        public void addScope(ClassConfig clazz) {
            clazz.addAnnotation(Dependent.class);
        }
    }

    @Test
    @DisplayName("Q1: @Enhancement(withAnnotations = WebServlet) reaches an unscoped class and @Dependent makes it a bean")
    void enhancementPromotesUnscopedWebServlet() throws Exception {
        var result = compile(List.of(DependentServletBce.class), APP_SERVLET);
        assertTrue(result.success(), result::messages);

        // Build time: the processor indexed the class (its trigger annotation comes from the
        // extension's withAnnotations) and the enhancement made it a bean.
        assertTrue(result.beansList().contains("spike.app.SpikeServlet"), result::describe);
        assertTrue(result.hasFile("spike/app/SpikeServlet_Factory.class"), result::describe);

        // Runtime: a container booted from the compiled output resolves the class as a bean.
        try (var loader = result.loader();
             var container = boot(loader)) {
            var servletClass = loader.loadClass("spike.app.SpikeServlet");
            var beans = container.getBeanManager().getBeans(servletClass);
            assertEquals(1, beans.size(), "SpikeServlet beans: " + beans);
            assertEquals(Dependent.class, beans.iterator().next().getScope());
        }
    }

    // ---- Q2: @Synthesis + withParam(String, Class<?>[]) at build time ----

    /**
     * Registers a {@link CdiWebComponents} synthetic bean carrying the component classes. It also
     * declares the {@code @WebServlet} enhancement, as Foy's extension will: the processor skips
     * every extension phase when it indexed no class (see Q3's second compilation).
     */
    public static class SyntheticComponentsBce implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withAnnotations = WebServlet.class)
        public void addScope(ClassConfig clazz) {
            clazz.addAnnotation(Dependent.class);
        }

        @Synthesis
        public void register(SyntheticComponents components) {
            components.addBean(CdiWebComponents.class)
                    .type(CdiWebComponents.class)
                    .scope(Singleton.class)
                    .withParam("classes", new Class<?>[] {String.class, Integer.class})
                    .withParam("classNames", "java.lang.String,java.lang.Integer")
                    .createWith(ComponentsCreator.class);
        }
    }

    /** Observed creator inputs, captured so the test can tell "param absent" from "bean absent". */
    public record Observed(Class<?>[] classes, String classNames) implements CdiWebComponents {
        @Override
        public List<Class<?>> componentClasses() {
            return classes == null ? List.of() : List.of(classes);
        }
    }

    public static class ComponentsCreator implements SyntheticBeanCreator<CdiWebComponents> {
        @Override
        public CdiWebComponents create(Instance<Object> lookup, Parameters params) {
            return new Observed(params.get("classes", Class[].class), params.get("classNames", String.class));
        }
    }

    @Test
    @DisplayName("Q2: build-time @Synthesis registers the bean, but a Class<?>[] param does not survive to runtime")
    void synthesisWithClassArrayParam() throws Exception {
        var result = compile(List.of(SyntheticComponentsBce.class), APP_SERVLET);
        assertTrue(result.success(), result::messages);

        // Build time: the synthetic bean is serialised, the String param with it, the Class<?>[]
        // param is not (SyntheticMetadataSerializer.encodeParam returns null for arrays).
        var metadata = result.readFile("META-INF/vauban-synthetic-metadata.properties");
        assertTrue(metadata.contains("bean.count=1"), metadata);
        assertTrue(metadata.contains(ComponentsCreator.class.getName()), metadata);
        assertTrue(metadata.contains(".param.classNames=S\\:java.lang.String,java.lang.Integer")
                || metadata.contains(".param.classNames=S:java.lang.String,java.lang.Integer"), metadata);
        assertFalse(metadata.contains(".param.classes="), metadata);

        // Runtime: the bean resolves and its creator runs, but Parameters has no "classes".
        try (var loader = result.loader();
             var container = boot(loader)) {
            var bm = container.getBeanManager();
            assertEquals(1, bm.getBeans(CdiWebComponents.class).size());
            var components = (Observed) bm.createInstance().select(CdiWebComponents.class).get();
            assertNull(components.classes(), "Class<?>[] param is dropped between build time and runtime");
            assertEquals("java.lang.String,java.lang.Integer", components.classNames(),
                    "a String param survives: a comma-joined FQCN list is a workable carrier");
        }
    }

    // ---- Q3: @Validation + Messages.error fails the compilation ----

    /** Rejects the deployment; declares the {@code @WebServlet} trigger so the app gets indexed. */
    public static class RejectingValidationBce implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withAnnotations = WebServlet.class)
        public void addScope(ClassConfig clazz) {
            clazz.addAnnotation(Dependent.class);
        }

        @Validation
        public void validate(Messages messages) {
            messages.error("spike: web component rejected");
        }
    }

    /** Same rejection, but without a trigger annotation: nothing in the app is indexed. */
    public static class TriggerlessValidationBce implements BuildCompatibleExtension {
        @Validation
        public void validate(Messages messages) {
            messages.error("spike: web component rejected");
        }
    }

    @Test
    @DisplayName("Q3: Messages.error in @Validation fails the compilation")
    void validationErrorFailsCompilation() throws Exception {
        var result = compile(List.of(RejectingValidationBce.class), APP_SERVLET);
        assertFalse(result.success(), "compilation should fail: " + result.messages());
        assertTrue(result.messages().contains("ERROR: [Vauban BCE] spike: web component rejected"),
                result::messages);

        // Pitfall: when the processor indexes no class (no CDI annotation and no extension trigger
        // annotation in the compilation), VaubanProcessor.process returns before running any
        // extension phase, so the same error is never raised and the compilation succeeds.
        var triggerless = compile(List.of(TriggerlessValidationBce.class), APP_SERVLET);
        assertTrue(triggerless.success(), triggerless::messages);
        assertFalse(triggerless.messages().contains("[Vauban BCE]"), triggerless::messages);
    }

    // ---- harness ----

    private static VaubanContainer boot(ClassLoader loader) {
        var previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            return VaubanContainer.builder().classLoader(loader).scanClasspath().build();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private Result compile(List<Class<?>> bceClasses, String... sources) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var root = Files.createTempDirectory(tempDir, "app");
        var src = Files.createDirectories(root.resolve("src"));
        var classes = Files.createDirectories(root.resolve("classes"));
        var generated = Files.createDirectories(root.resolve("generated"));
        var files = new ArrayList<File>();
        for (var source : sources) {
            var pkg = match(source, "package\\s+([\\w.]+)\\s*;");
            var name = match(source, "(?:class|interface|enum|record)\\s+(\\w+)");
            var file = src.resolve(pkg.replace('.', '/')).resolve(name + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
            files.add(file.toFile());
        }
        try (var fm = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
            fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(generated.toFile()));
            fm.setLocation(StandardLocation.CLASS_PATH, testClasspath());
            var processor = new VaubanProcessor();
            processor.overrideBceClasses = bceClasses;
            var task = compiler.getTask(null, fm, diagnostics,
                    List.of("--release", "25", "-proc:full"), null, fm.getJavaFileObjectsFromFiles(files));
            task.setProcessors(List.of(processor));
            boolean ok = task.call();
            var messages = new StringBuilder();
            for (var d : diagnostics.getDiagnostics()) {
                messages.append(d.getKind()).append(": ").append(d.getMessage(Locale.ROOT)).append('\n');
            }
            return new Result(ok, classes, messages.toString());
        }
    }

    private static String match(String source, String regex) {
        var m = java.util.regex.Pattern.compile(regex).matcher(source);
        if (!m.find()) throw new IllegalArgumentException("no match for " + regex);
        return m.group(1);
    }

    private static List<File> testClasspath() throws Exception {
        Set<File> cp = new LinkedHashSet<>();
        for (var e : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!e.isBlank()) cp.add(new File(e));
        }
        for (var marker : List.of(WebServlet.class, Dependent.class, CdiWebComponents.class,
                jakarta.inject.Inject.class, jakarta.interceptor.Interceptor.class)) {
            var location = marker.getProtectionDomain().getCodeSource();
            if (location != null && location.getLocation() != null) {
                cp.add(new File(location.getLocation().toURI()));
            }
        }
        ModuleLayer.boot().configuration().modules().forEach(rm -> rm.reference().location().ifPresent(uri -> {
            if ("file".equals(uri.getScheme())) cp.add(new File(uri));
        }));
        return List.copyOf(cp);
    }

    private record Result(boolean success, Path classes, String messages) {
        boolean hasFile(String path) {
            return Files.exists(classes.resolve(path));
        }

        String readFile(String path) throws IOException {
            return Files.readString(classes.resolve(path), StandardCharsets.UTF_8);
        }

        List<String> beansList() throws IOException {
            var path = classes.resolve("META-INF/vauban-beans.list");
            if (!Files.exists(path)) return List.of();
            return Files.readAllLines(path).stream().map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        }

        URLClassLoader loader() throws IOException {
            return new URLClassLoader(new URL[] {classes.toUri().toURL()},
                    BceCapabilitySpikeTest.class.getClassLoader());
        }

        String describe() {
            try (var walk = Files.walk(classes)) {
                return "messages:\n" + messages + "output: " + Arrays.toString(
                        walk.filter(Files::isRegularFile).map(classes::relativize).toArray()) + "\nbeans: " + beansList();
            } catch (IOException e) {
                return messages;
            }
        }
    }
}
