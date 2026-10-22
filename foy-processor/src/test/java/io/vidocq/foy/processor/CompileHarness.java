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
package io.vidocq.foy.processor;

import javax.tools.*;
import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** In-process compilation of test sources with {@link FoyWebComponentProcessor}. */
final class CompileHarness {
    record Result(URLClassLoader loader, List<Diagnostic<? extends JavaFileObject>> diagnostics,
                  boolean success, Path output) {
        String messages() {
            var sb = new StringBuilder();
            for (var d : diagnostics) sb.append(d.getKind()).append(": ").append(d.getMessage(Locale.ROOT)).append('\n');
            return sb.toString();
        }

        String generatedSource(String fqcn) throws java.io.IOException {
            return Files.readString(output.resolve("generated").resolve(fqcn.replace('.', '/') + ".java"));
        }

        String resource(String path) throws java.io.IOException {
            return Files.readString(output.resolve("classes").resolve(path));
        }
    }

    /** sources: FQCN to source text. Compiles on the classpath with FoyWebComponentProcessor. */
    static Result compile(Path out, Map<String, String> sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var diags = new DiagnosticCollector<JavaFileObject>();
        Path src = Files.createDirectories(out.resolve("src"));
        Path classes = Files.createDirectories(out.resolve("classes"));
        Path generated = Files.createDirectories(out.resolve("generated"));
        List<File> files = new ArrayList<>();
        for (var e : sources.entrySet()) {
            Path p = src.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
            files.add(p.toFile());
        }
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diags, Locale.ROOT, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
            fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(generated.toFile()));
            fm.setLocation(StandardLocation.CLASS_PATH, testClasspath());
            var task = compiler.getTask(null, fm, diags, List.of("-proc:full"), null,
                    fm.getJavaFileObjectsFromFiles(files));
            task.setProcessors(List.of(new FoyWebComponentProcessor()));
            boolean ok = task.call();
            var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},
                    CompileHarness.class.getClassLoader());
            return new Result(loader, diags.getDiagnostics(), ok, out);
        }
    }

    /**
     * Compiles {@code sources} with {@code -proc:none} into {@code out/dependency}, then runs
     * {@code -proc:only} with {@link FoyWebComponentProcessor} over the given binary class names, the way a
     * dependency jar's classes are processed.
     */
    static Result processClasses(Path out, Map<String, String> sources, List<String> classNames) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path src = Files.createDirectories(out.resolve("src"));
        Path dependency = Files.createDirectories(out.resolve("dependency"));
        Path classes = Files.createDirectories(out.resolve("classes"));
        Path generated = Files.createDirectories(out.resolve("generated"));
        List<File> files = new ArrayList<>();
        for (var e : sources.entrySet()) {
            Path p = src.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
            files.add(p.toFile());
        }
        var diags = new DiagnosticCollector<JavaFileObject>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diags, Locale.ROOT, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(dependency.toFile()));
            fm.setLocation(StandardLocation.CLASS_PATH, testClasspath());
            boolean compiled = compiler.getTask(null, fm, diags, List.of("-proc:none"), null,
                    fm.getJavaFileObjectsFromFiles(files)).call();
            if (!compiled) {
                return new Result(null, diags.getDiagnostics(), false, out);
            }
        }
        var diags2 = new DiagnosticCollector<JavaFileObject>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diags2, Locale.ROOT, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
            fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(generated.toFile()));
            List<File> cp = new ArrayList<>();
            cp.add(dependency.toFile());
            cp.addAll(testClasspath());
            fm.setLocation(StandardLocation.CLASS_PATH, cp);
            var task = compiler.getTask(null, fm, diags2, List.of("-proc:only"), classNames, null);
            task.setProcessors(List.of(new FoyWebComponentProcessor()));
            boolean ok = task.call();
            return new Result(null, diags2.getDiagnostics(), ok, out);
        }
    }

    /**
     * Compiles every generated source under {@code out/generated} with {@code -proc:none}, against the
     * dependency classes plus the test classpath, as a caller must after a {@code -proc:only} run.
     */
    static Result compileGenerated(Path out) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path generated = out.resolve("generated");
        Path target = Files.createDirectories(out.resolve("generated-classes"));
        List<File> files;
        try (var walk = Files.walk(generated)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).map(Path::toFile).toList();
        }
        var diags = new DiagnosticCollector<JavaFileObject>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diags, Locale.ROOT, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(target.toFile()));
            List<File> cp = new ArrayList<>();
            cp.add(out.resolve("dependency").toFile());
            cp.addAll(testClasspath());
            fm.setLocation(StandardLocation.CLASS_PATH, cp);
            boolean ok = compiler.getTask(null, fm, diags, List.of("-proc:none"), null,
                    fm.getJavaFileObjectsFromFiles(files)).call();
            return new Result(null, diags.getDiagnostics(), ok, out);
        }
    }

    private static List<File> testClasspath() throws Exception {
        Set<File> cp = new LinkedHashSet<>();
        for (String e : System.getProperty("java.class.path", "").split(File.pathSeparator, -1)) {
            if (!e.isBlank()) cp.add(new File(e));
        }
        for (ClassLoader cl = CompileHarness.class.getClassLoader(); cl != null; cl = cl.getParent()) {
            if (cl instanceof URLClassLoader u) {
                for (var url : u.getURLs()) {
                    if ("file".equals(url.getProtocol())) cp.add(new File(url.toURI()));
                }
            }
        }
        ModuleLayer layer = CompileHarness.class.getModule().getLayer();
        if (layer != null) {
            layer.configuration().modules().forEach(rm -> rm.reference().location().ifPresent(uri -> {
                if ("file".equals(uri.getScheme())) cp.add(new File(uri));
            }));
        }
        return List.copyOf(cp);
    }

    private CompileHarness() {}
}
