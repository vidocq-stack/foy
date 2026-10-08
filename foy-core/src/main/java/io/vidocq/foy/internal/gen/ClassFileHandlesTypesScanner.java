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
package io.vidocq.foy.internal.gen;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger.Level;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Builds, from class bytes, the entries the build-time class index would hold
 * ({@code META-INF/foy/class-index.list}): binary name, transitive supertypes and annotations.
 * It is the fallback of {@link IndexedHandlesTypesResolver} for the jars and directories that
 * ship no class index.
 *
 * <p>Classes are decoded with {@code java.lang.classfile}; nothing is loaded or initialised.
 * Annotations are read from {@code RuntimeVisibleAnnotations} only (an annotation type named by
 * {@code @HandlesTypes} must be runtime-retained to be matched), on the type and, as
 * {@code @HandlesTypes} specifies, on its fields and methods. Transitive supertypes are resolved
 * across all scanned roots; for a type outside them the bytes are read through the loader
 * ({@code getResourceAsStream}), then through the system loader and the boot layer's modules. A
 * supertype whose bytes cannot be read ends that branch (logged at DEBUG).</p>
 */
public final class ClassFileHandlesTypesScanner {

    private static final System.Logger LOG = System.getLogger(ClassFileHandlesTypesScanner.class.getName());
    private static final String INDEX_RESOURCE = "META-INF/foy/class-index.list";

    /**
     * One scanned class.
     *
     * @param name        binary name
     * @param supertypes  transitive superclasses and interfaces, binary names
     * @param annotations binary names of the runtime-visible annotations
     */
    public record Entry(String name, Set<String> supertypes, Set<String> annotations) {}

    private record Direct(String superclass, List<String> interfaces, Set<String> annotations) {}

    private static final Direct UNKNOWN = new Direct(null, List.of(), Set.of());

    private final ClassLoader loader;
    private final Map<String, Direct> scanned = new LinkedHashMap<>();
    private final Map<String, Direct> external = new HashMap<>();
    private final Map<String, Set<String>> closure = new HashMap<>();

    private ClassFileHandlesTypesScanner(ClassLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /**
     * Scans every {@code .class} under the directories and inside the jars of {@code roots}.
     * A root that ships a {@code META-INF/foy/class-index.list} is skipped (the index covers it),
     * as is a root that does not exist.
     *
     * @param loader reads the bytes of supertypes outside the roots
     */
    public static List<Entry> scan(Iterable<Path> roots, ClassLoader loader) {
        var scanner = new ClassFileHandlesTypesScanner(loader);
        for (Path root : roots) {
            scanner.readRoot(root);
        }
        return scanner.entries();
    }

    /**
     * Scans the classes named by {@code binaryNames}, read through {@code loader}.
     * For a deployment whose classes are already on the loader (no directory or jar to walk).
     */
    public static List<Entry> scanNamed(Collection<String> binaryNames, ClassLoader loader) {
        var scanner = new ClassFileHandlesTypesScanner(loader);
        for (String name : binaryNames) {
            Direct d = scanner.fromLoader(name);
            if (d != UNKNOWN) {
                scanner.scanned.putIfAbsent(name, d);
            }
        }
        return scanner.entries();
    }

    // ---------------------------------------------------------------- reading roots

    private void readRoot(Path root) {
        try {
            if (Files.isDirectory(root)) {
                readDirectory(root);
            } else if (Files.isRegularFile(root)) {
                readJar(root);
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Cannot scan {0} for @HandlesTypes: {1}", root, e.toString());
        }
    }

    private void readDirectory(Path dir) throws IOException {
        if (Files.exists(dir.resolve(INDEX_RESOURCE))) {
            return;
        }
        List<Path> classes;
        try (Stream<Path> files = Files.walk(dir)) {
            classes = files.filter(p -> p.toString().endsWith(".class")).sorted().toList();
        }
        for (Path file : classes) {
            String relative = dir.relativize(file).toString().replace('\\', '/');
            accept(relative, Files.readAllBytes(file));
        }
    }

    private void readJar(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            if (zip.getEntry(INDEX_RESOURCE) != null) {
                return;
            }
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    accept(entry.getName(), in.readAllBytes());
                }
            }
        }
    }

    private void accept(String resource, byte[] bytes) {
        if (resource.startsWith("META-INF/")
                || resource.endsWith("module-info.class") || resource.endsWith("package-info.class")) {
            return;
        }
        String name = resource.substring(0, resource.length() - ".class".length()).replace('/', '.');
        Direct direct = decode(bytes, name);
        if (direct != UNKNOWN) {
            scanned.putIfAbsent(name, direct);
        }
    }

    // ---------------------------------------------------------------- decoding

    private static Direct decode(byte[] bytes, String name) {
        try {
            ClassModel model = ClassFile.of().parse(bytes);
            String superclass = model.superclass().map(ClassFileHandlesTypesScanner::binary).orElse(null);
            List<String> interfaces = new ArrayList<>();
            for (ClassEntry i : model.interfaces()) {
                interfaces.add(binary(i));
            }
            Set<String> annotations = new LinkedHashSet<>();
            model.findAttribute(Attributes.runtimeVisibleAnnotations())
                    .ifPresent(a -> addAll(annotations, a.annotations()));
            model.fields().forEach(f -> f.findAttribute(Attributes.runtimeVisibleAnnotations())
                    .ifPresent(a -> addAll(annotations, a.annotations())));
            model.methods().forEach(m -> m.findAttribute(Attributes.runtimeVisibleAnnotations())
                    .ifPresent(a -> addAll(annotations, a.annotations())));
            return new Direct(superclass, List.copyOf(interfaces), Set.copyOf(annotations));
        } catch (RuntimeException e) {
            LOG.log(Level.DEBUG, "Cannot decode class {0}: {1}", name, e.toString());
            return UNKNOWN;
        }
    }

    private static String binary(ClassEntry entry) {
        return entry.asInternalName().replace('/', '.');
    }

    private static void addAll(Set<String> out, List<Annotation> annotations) {
        for (Annotation a : annotations) {
            String descriptor = a.className().stringValue(); // "Lp/Outer$Inner;"
            out.add(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
        }
    }

    // ---------------------------------------------------------------- supertypes

    private List<Entry> entries() {
        List<Entry> out = new ArrayList<>(scanned.size());
        for (var e : scanned.entrySet()) {
            Set<String> supers = new LinkedHashSet<>();
            collect(e.getValue(), supers, new HashSet<>());
            out.add(new Entry(e.getKey(), Set.copyOf(supers), e.getValue().annotations()));
        }
        return List.copyOf(out);
    }

    private void collect(Direct direct, Set<String> into, Set<String> visiting) {
        if (direct.superclass() != null) {
            addTransitive(direct.superclass(), into, visiting);
        }
        for (String i : direct.interfaces()) {
            addTransitive(i, into, visiting);
        }
    }

    private void addTransitive(String type, Set<String> into, Set<String> visiting) {
        Set<String> known = closure.get(type);
        if (known != null) {
            into.add(type);
            into.addAll(known);
            return;
        }
        if (!visiting.add(type)) {
            return; // inheritance cycle in malformed input
        }
        Set<String> own = new LinkedHashSet<>();
        collect(direct(type), own, visiting);
        visiting.remove(type);
        closure.put(type, own);
        into.add(type);
        into.addAll(own);
    }

    private Direct direct(String type) {
        Direct d = scanned.get(type);
        return d != null ? d : external.computeIfAbsent(type, this::fromLoader);
    }

    /** Bytes through the loader, then the system loader and the boot layer's modules. */
    private Direct fromLoader(String name) {
        String resource = name.replace('.', '/') + ".class";
        byte[] bytes = null;
        try {
            bytes = read(loader.getResourceAsStream(resource));
            if (bytes == null) {
                bytes = read(ClassLoader.getSystemResourceAsStream(resource));
            }
            if (bytes == null) {
                for (Module module : ModuleLayer.boot().modules()) {
                    bytes = read(module.getResourceAsStream(resource));
                    if (bytes != null) {
                        break;
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "Cannot read class bytes of {0}: {1}", name, e.toString());
        }
        if (bytes == null) {
            LOG.log(Level.DEBUG, "Supertype {0} is not readable: its branch is not climbed", name);
            return UNKNOWN;
        }
        return decode(bytes, name);
    }

    private static byte[] read(InputStream in) throws IOException {
        if (in == null) {
            return null;
        }
        try (in) {
            return in.readAllBytes();
        }
    }
}
