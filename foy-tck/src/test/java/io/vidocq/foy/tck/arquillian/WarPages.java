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
package io.vidocq.foy.tck.arquillian;

import io.vidocq.foy.spi.jsp.JspEngine;
import io.vidocq.ibarra.compiler.GenerationOptions;
import io.vidocq.ibarra.compiler.JspConfigurationParser;
import io.vidocq.ibarra.compiler.JspPropertyGroup;
import io.vidocq.ibarra.compiler.PagePrecompiler;
import io.vidocq.ibarra.foy.internal.IbarraJspEngine;
import org.jboss.shrinkwrap.api.spec.WebArchive;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.stream.Stream;

/**
 * The JSP pages of a TCK war, served by Ibarra through Foy's {@link JspEngine} SPI (BUG-20261011-04).
 *
 * <p>Ibarra serves pages compiled ahead of time, found in a module layer. The harness runs on the class
 * path, so the pages are compiled at deployment into a module, {@value #MODULE}, defined in a layer
 * whose class loaders all end at the test class loader: the generated classes see the Servlet, Pages
 * and Ibarra classes, and the war's own classes, that Foy itself uses.
 */
final class WarPages {

    static final String MODULE = "foy.tck.pages";

    /** The modules the pages compile against, taken from the test class path. */
    private static final Set<String> COMPILE_MODULES = Set.of("io.vidocq.ibarra.api", "io.vidocq.ibarra.core",
            "io.vidocq.peano.api", "io.vidocq.peano.core", "jakarta.servlet", "jakarta.servlet.jsp", "jakarta.el");

    /** The engine and the layer that holds the war's compiled pages. */
    record Pages(ModuleLayer layer, JspEngine engine) {}

    private WarPages() {}

    /**
     * Compiles the pages of {@code war} under {@code work}; empty when the war has no page and no tag
     * library descriptor, so the deployment keeps running without a JSP engine.
     */
    static Optional<Pages> compile(WebArchive war, ClassLoader loader, Path work) throws IOException {
        if (!hasPageSources(war)) return Optional.empty();
        Path web = Files.createDirectories(work.resolve("web"));
        Path output = Files.createDirectories(work.resolve("module"));
        Path warClasses = Files.createDirectories(work.resolve("war-classes"));
        extract(war, web, warClasses);

        List<Path> modulePath = compileModulePath();
        // The war's classes join the module for compilation only (a TLD listener names one); at run
        // time their packages stay outside it, so they come from the test class loader, as Foy's do.
        Set<String> warPackages = packagesOf(warClasses);
        copyTree(warClasses, output);
        Path descriptor = work.resolve("src/module-info.java");
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, "module " + MODULE + " { requires java.desktop; requires java.naming; "
                + "requires jakarta.el; requires jakarta.servlet; requires jakarta.servlet.jsp; "
                + "requires io.vidocq.ibarra.api; }");
        javac("--module-path", join(modulePath), "--patch-module", MODULE + "=" + output,
                "-d", output.toString(), descriptor.toString());

        Path webXml = web.resolve("WEB-INF/web.xml");
        List<JspPropertyGroup> groups = Files.exists(webXml)
                ? new JspConfigurationParser().parse("/WEB-INF/web.xml", Files.readAllBytes(webXml))
                : List.of();
        var result = new PagePrecompiler().precompileDeployment(web, output, modulePath, null,
                new GenerationOptions(groups, Map.of()));
        new TreeMap<>(result.failures()).forEach((page, failure) ->
                System.err.println("[VidocqTCK] JSP translation failed: " + page + ": " + failure));
        return Optional.of(new Pages(define(output, warPackages, modulePath, loader), new IbarraJspEngine()));
    }

    /** Whether the war holds a page, a tag file or a tag library descriptor, its lib jars' included. */
    private static boolean hasPageSources(WebArchive war) throws IOException {
        for (var entry : war.getContent().entrySet()) {
            String name = entry.getKey().get();
            if (isPageSource(name)) return true;
            var asset = entry.getValue().getAsset();
            if (asset == null || !name.startsWith("/WEB-INF/lib/") || !name.endsWith(".jar")) continue;
            try (var jar = new JarInputStream(asset.openStream())) {
                for (JarEntry item; (item = jar.getNextJarEntry()) != null; ) {
                    if (item.getName().startsWith("META-INF/") && item.getName().endsWith(".tld")) return true;
                }
            }
        }
        return false;
    }

    private static boolean isPageSource(String path) {
        return path.endsWith(".jsp") || path.endsWith(".jspx") || path.endsWith(".jspf") || path.endsWith(".tag")
                || path.endsWith(".tagx") || path.endsWith(".tld");
    }

    /**
     * The war's web content under {@code web}; its {@code WEB-INF/classes}, and the content of its
     * {@code WEB-INF/lib} jars, also under {@code classes}. The precompiler reads the tag library
     * descriptors of the module's {@code META-INF}: each jar's keeps a directory of its own there.
     */
    private static void extract(WebArchive war, Path web, Path classes) throws IOException {
        int library = 0;
        for (var entry : new TreeMap<>(war.getContent()).entrySet()) {
            var asset = entry.getValue().getAsset();
            if (asset == null) continue;
            String name = entry.getKey().get().replaceFirst("^/", "");
            write(web, name, asset::openStream);
            if (name.startsWith("WEB-INF/classes/")) {
                write(classes, name.substring("WEB-INF/classes/".length()), asset::openStream);
            } else if (name.startsWith("WEB-INF/lib/") && name.endsWith(".jar")) {
                String tlds = "META-INF/war-libraries/" + String.format(java.util.Locale.ROOT, "%04d", library++) + "/";
                try (var jar = new JarInputStream(asset.openStream())) {
                    for (JarEntry item; (item = jar.getNextJarEntry()) != null; ) {
                        String path = item.getName();
                        if (item.isDirectory() || path.equals("module-info.class") || path.startsWith("META-INF/versions/")) continue;
                        if (path.startsWith("META-INF/") && !path.endsWith(".tld")) continue;
                        byte[] bytes = jar.readAllBytes();
                        write(classes, path.startsWith("META-INF/") ? tlds + path : path,
                                () -> new java.io.ByteArrayInputStream(bytes));
                    }
                }
            }
        }
    }

    private interface Source { InputStream open() throws IOException; }

    private static void write(Path root, String relative, Source source) throws IOException {
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) throw new IOException("Unsafe archive path: " + relative);
        Files.createDirectories(file.getParent());
        try (var input = source.open()) {
            Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * {@code output} as module {@value #MODULE}, over {@code loader}. The modules it requires are resolved
     * in a parent layer from their real descriptors, but defined to a class loader that defines nothing and
     * delegates to {@code loader}: their classes are the class-path ones Foy and Ibarra use. Only the
     * generated packages belong to {@value #MODULE}; the war's come from {@code loader} too.
     */
    private static ModuleLayer define(Path output, Set<String> warPackages, List<Path> modulePath, ClassLoader loader)
            throws IOException {
        ModuleDescriptor compiled;
        try (var input = Files.newInputStream(output.resolve("module-info.class"))) {
            compiled = ModuleDescriptor.read(input);
        }
        ModuleLayer boot = ModuleLayer.boot();
        ModuleFinder apis = ModuleFinder.of(modulePath.toArray(Path[]::new));
        Set<String> required = new TreeSet<>();
        for (var read : compiled.requires()) if (apis.find(read.name()).isPresent()) required.add(read.name());
        var apiConfiguration = boot.configuration().resolve(apis, ModuleFinder.of(), required);
        ClassLoader relay = new ClassLoader("foy-tck-pages-apis", loader) {};
        ModuleLayer apiLayer = ModuleLayer.defineModules(apiConfiguration, List.of(boot), name -> relay).layer();

        Set<String> packages = new TreeSet<>(packagesOf(output));
        packages.removeAll(warPackages);
        var builder = ModuleDescriptor.newModule(MODULE).packages(packages);
        compiled.requires().stream()
                .filter(read -> !read.modifiers().contains(ModuleDescriptor.Requires.Modifier.MANDATED))
                .forEach(read -> builder.requires(read.name()));
        for (var provides : compiled.provides()) {
            List<String> generated = provides.providers().stream()
                    .filter(provider -> packages.contains(provider.substring(0, provider.lastIndexOf('.'))))
                    .toList();
            if (!generated.isEmpty()) builder.provides(provides.service(), generated);
        }
        ModuleReference reference = new DirectoryModule(builder.build(), output);
        ModuleFinder finder = new ModuleFinder() {
            @Override public Optional<ModuleReference> find(String name) {
                return name.equals(MODULE) ? Optional.of(reference) : Optional.empty();
            }
            @Override public Set<ModuleReference> findAll() { return Set.of(reference); }
        };
        var configuration = apiConfiguration.resolve(finder, ModuleFinder.of(), Set.of(MODULE));
        var controller = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(apiLayer), loader);
        Module pages = controller.layer().findModule(MODULE).orElseThrow();
        // The classes behind the relayed modules, and the war's, live in the unnamed module of loader.
        controller.addReads(pages, loader.getUnnamedModule());
        for (String pn : packages) controller.addExports(pages, pn, loader.getUnnamedModule());
        return controller.layer();
    }

    /** The packages of the class files under {@code root}. */
    private static Set<String> packagesOf(Path root) throws IOException {
        var packages = new TreeSet<String>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(file -> file.toString().endsWith(".class"))
                    .map(file -> root.relativize(file.getParent() == null ? root : file.getParent()))
                    .map(dir -> dir.toString().replace(File.separatorChar, '.'))
                    .filter(pn -> !pn.isEmpty())
                    .forEach(packages::add);
        }
        return packages;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path target = to.resolve(from.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** The explicit modules of the test class path that the pages compile against. */
    private static List<Path> compileModulePath() throws IOException {
        var candidates = new ArrayList<Path>();
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String item : classPath.split(File.pathSeparator)) {
            if (!item.endsWith(".jar")) continue;
            Path jar = Path.of(item);
            if (!Files.isRegularFile(jar)) continue;
            try (var file = new JarFile(jar.toFile())) {
                if (file.getJarEntry("module-info.class") != null) candidates.add(jar);
            }
        }
        return ModuleFinder.of(candidates.toArray(Path[]::new)).findAll().stream()
                .filter(reference -> COMPILE_MODULES.contains(reference.descriptor().name()))
                .map(reference -> Path.of(reference.location().orElseThrow()))
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private static String join(List<Path> paths) {
        return String.join(File.pathSeparator, paths.stream().map(Path::toString).toList());
    }

    private static void javac(String... arguments) {
        var errors = new ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, errors, arguments);
        if (status != 0) {
            throw new IllegalStateException("javac failed: " + errors.toString(StandardCharsets.UTF_8));
        }
    }

    /** A module read from a directory, under a descriptor built here rather than its {@code module-info.class}. */
    private static final class DirectoryModule extends ModuleReference {
        private final Path root;

        DirectoryModule(ModuleDescriptor descriptor, Path root) {
            super(descriptor, root.toUri());
            this.root = root;
        }

        @Override
        public ModuleReader open() {
            return new ModuleReader() {
                @Override public Optional<URI> find(String name) {
                    Path file = root.resolve(name).normalize();
                    return file.startsWith(root) && Files.exists(file) ? Optional.of(file.toUri()) : Optional.empty();
                }
                @Override public Stream<String> list() {
                    try {
                        return Files.walk(root).filter(Files::isRegularFile)
                                .map(file -> root.relativize(file).toString().replace(File.separatorChar, '/'))
                                .toList().stream();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                @Override public void close() {}
            };
        }
    }
}
