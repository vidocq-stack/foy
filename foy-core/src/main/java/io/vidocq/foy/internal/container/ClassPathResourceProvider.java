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
package io.vidocq.foy.internal.container;

import io.vidocq.foy.internal.container.VidocqServletContext.ResourceProvider;
import io.vidocq.foy.internal.webxml.Fragment;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Serves the {@code META-INF/resources/} directory of the application's jars and directories
 * as the root of the static resources (Servlet 6.1 §4.6, §10.5).
 *
 * <p><b>Lookup order.</b> The order between jars is unspecified by the specification; Foy uses
 * <em>application roots first, then the ordered web fragments' jars</em> (the
 * {@code orderedJars} passed to the constructor, in that order), <em>then every other root</em> of
 * the class loader that holds the resource, in class-loader order. The first root that holds a
 * path wins for {@link #toUrl} and {@link #openStream}; {@link #listPaths} merges all of them.</p>
 *
 * <p>Jars are read through an entry-name index built once per jar (lazily, thread-safe), so a
 * jar without directory entries is fine and a lookup never rescans it; the jar is reopened only
 * to stream a file. Other roots are found with
 * {@link ClassLoader#getResources(String) getResources("META-INF/resources")} and
 * {@code getResources("META-INF/resources/" + path)}. Roots nested in another archive
 * ({@code jar:file:/a.war!/lib/b.jar!/...}) are not supported here. The index is not refreshed
 * if a jar changes on disk.</p>
 *
 * <p><b>Path safety.</b> Paths holding a {@code ..} or {@code .} segment, an empty segment ({@code //}), a
 * backslash or a NUL character resolve to nothing, and a resolved directory path must stay
 * under its root, symbolic links included (a link leading out of the root resolves to nothing). Callers must <em>not</em> percent-decode a path before passing it here, or
 * an encoded {@code %2e%2e} or {@code %5c} would turn into a path this class then rejects or,
 * worse, a different resource than the one the client named.
 * {@code getResource} may reach the {@code WEB-INF/} and {@code META-INF/} subtrees of the
 * resource root; the default servlet must additionally check {@link #isServable}.</p>
 *
 * <p>The {@code jar:} URLs returned by {@link #toUrl} use the JDK's default URL connection
 * caching when opened with {@code URL.openStream()} (the jar stays open in the JDK cache);
 * {@link #openStream} does not use that cache and releases the jar when the stream is closed.</p>
 */
public final class ClassPathResourceProvider implements ResourceProvider {

    private static final String ROOT = "META-INF/resources/";
    private static final String ROOT_NAME = "META-INF/resources";

    private final ClassLoader loader;
    private final List<Loc.Root> ordered = new ArrayList<>();
    private final Set<String> orderedKeys = new HashSet<>();
    private final Map<Path, JarIndex> indexes = new ConcurrentHashMap<>();

    /**
     * @param loader      class loader whose other {@code META-INF/resources/} roots come last
     * @param orderedJars jars or directories (code-source or {@code jar:} URLs), by priority
     */
    public ClassPathResourceProvider(ClassLoader loader, List<URL> orderedJars) {
        this.loader = java.util.Objects.requireNonNull(loader, "loader");
        for (URL u : orderedJars) {
            String key = Fragment.sourceKey(u);
            if (!orderedKeys.add(key)) continue;
            Path p = pathOf(key);
            if (p != null) {
                ordered.add(Files.isDirectory(p) ? new Loc.Dir(p.resolve(ROOT)) : new Loc.Jar(index(p)));
            }
        }
    }

    private JarIndex index(Path jar) {
        return indexes.computeIfAbsent(jar.toAbsolutePath().normalize(), JarIndex::new);
    }

    /**
     * Whether the default servlet may serve {@code path}: a safe path outside the
     * {@code WEB-INF/} and {@code META-INF/} trees (compared case-insensitively).
     */
    public static boolean isServable(String path) {
        if (!safe(path)) return false;
        int slash = path.indexOf('/', 1);
        String first = (slash < 0 ? path.substring(1) : path.substring(1, slash)).toUpperCase(Locale.ROOT);
        return !first.equals("WEB-INF") && !first.equals("META-INF");
    }

    @Override public Set<String> listPaths(String path) {
        if (!safe(path)) return null;
        String dir = path.endsWith("/") ? path : path + "/";
        Set<String> out = new TreeSet<>();
        for (Loc loc : locate(dir.substring(1))) {
            if (loc.isDirectory()) for (String child : loc.children()) out.add(dir + child);
        }
        return out.isEmpty() ? null : new LinkedHashSet<>(out);
    }

    @Override public InputStream openStream(String path) {
        if (!safe(path) || path.endsWith("/")) return null;
        for (Loc loc : locate(path.substring(1))) {
            if (loc.isFile()) {
                try { return loc.open(); } catch (IOException e) { /* try the next root */ }
            }
        }
        return null;
    }

    @Override public URL toUrl(String path) {
        if (!safe(path)) return null;
        for (Loc loc : locate(path.substring(1))) {
            URL url = loc.url();
            if (url != null) return url;
        }
        return null;
    }

    private static boolean safe(String path) {
        if (path == null || !path.startsWith("/")) return false;
        if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) return false;
        String[] segments = path.split("/", -1);
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.equals("..") || segment.equals(".")) return false;
            if (segment.isEmpty() && i != segments.length - 1) return false;
        }
        return true;
    }

    /** Every location holding {@code rel} (no leading slash), in priority order. */
    private List<Loc> locate(String rel) {
        Map<String, Loc> out = new LinkedHashMap<>();
        for (Loc.Root root : ordered) add(out, root.at(rel));
        // Roots that declare META-INF/resources/ itself are read like the ordered ones: no
        // directory entry is needed below it.
        collect(ROOT_NAME, out, base -> base.resolve(rel));
        String name = ROOT + (rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel);
        collect(name, out, loc -> loc);
        return new ArrayList<>(out.values());
    }

    private static void add(Map<String, Loc> out, Loc loc) {
        if (loc == null || !loc.exists()) return;
        URL url = loc.url();
        if (url != null) out.putIfAbsent(url.toString(), loc);
    }

    /** Adds the class-loader resources {@code name} outside the ordered roots, mapped by {@code f}. */
    private void collect(String name, Map<String, Loc> out, java.util.function.UnaryOperator<Loc> f) {
        try {
            Enumeration<URL> urls = loader.getResources(name);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if (orderedKeys.contains(Fragment.sourceKey(rootOf(url, name)))) continue;
                Loc loc = Loc.of(url, this::index);
                if (loc != null) add(out, f.apply(loc));
            }
        } catch (IOException e) {
            // unreadable roots are skipped
        }
    }

    /** The root URL (jar or directory) a resource URL {@code name} was found under. */
    private static URL rootOf(URL url, String name) {
        String s = url.toString();
        int i = s.lastIndexOf(name);
        try {
            return new URI(i < 0 ? s : s.substring(0, i)).toURL();
        } catch (Exception e) {
            return url;
        }
    }

    private static Path pathOf(String key) {
        try {
            URI uri = new URI(key);
            return "file".equalsIgnoreCase(uri.getScheme()) ? Path.of(uri) : null;
        } catch (URISyntaxException | IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
            return null;
        }
    }

    /** Entry-name index of a jar, built on first use and kept only once the jar was read successfully. */
    private static final class JarIndex {
        private final Path jar;
        private volatile Data data;

        private record Data(Set<String> files, Map<String, List<String>> children) {}

        JarIndex(Path jar) { this.jar = jar; }

        private Data data() {
            Data d = data;
            if (d == null) {
                synchronized (this) {
                    d = data;
                    if (d == null) {
                        d = build();
                        if (d != null) data = d; // an unreadable jar is retried at the next lookup
                    }
                }
            }
            return d != null ? d : new Data(Set.of(), Map.of());
        }

        private Data build() {
            Set<String> files = new HashSet<>();
            Map<String, TreeSet<String>> children = new HashMap<>();
            try (JarFile f = new JarFile(jar.toFile())) {
                f.stream().forEach(e -> {
                    String name = e.getName();
                    boolean dir = name.endsWith("/");
                    String trimmed = dir ? name.substring(0, name.length() - 1) : name;
                    if (trimmed.isEmpty()) return;
                    if (!dir) files.add(trimmed);
                    String child = trimmed;
                    boolean childIsDir = dir;
                    while (true) {
                        int slash = child.lastIndexOf('/');
                        String parent = slash < 0 ? "" : child.substring(0, slash);
                        children.computeIfAbsent(parent, k -> new TreeSet<>())
                                .add(child.substring(slash + 1) + (childIsDir ? "/" : ""));
                        if (slash < 0) break;
                        child = parent;
                        childIsDir = true;
                    }
                });
            } catch (IOException e) {
                return null;
            }
            Map<String, List<String>> frozen = new HashMap<>();
            children.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
            return new Data(Set.copyOf(files), Map.copyOf(frozen));
        }

        boolean isFile(String entry) { return data().files().contains(entry); }
        boolean isDirectory(String entry) { return data().children().containsKey(entry); }
        List<String> children(String entry) {
            return data().children().getOrDefault(entry, List.of());
        }
    }

    /** A file or directory of a {@code META-INF/resources/} tree, on disk or inside a jar. */
    private sealed interface Loc {
        boolean exists();
        boolean isDirectory();
        boolean isFile();
        List<String> children();
        InputStream open() throws IOException;
        /** The URL of this location, {@code null} if it cannot be expressed. */
        URL url();
        /** The location {@code rel} (relative, no leading slash) below this directory. */
        Loc resolve(String rel);

        /** A root that resolves a relative path (no leading slash) to a location. */
        sealed interface Root {
            Loc at(String rel);
        }

        static Loc of(URL url, java.util.function.Function<Path, JarIndex> indexes) {
            String s = url.toString();
            try {
                if (s.startsWith("jar:")) {
                    int bang = s.indexOf("!/");
                    if (bang < 0 || s.indexOf("!/", bang + 2) >= 0) return null;
                    Path jar = pathOf(Fragment.sourceKey(new URI(s.substring(4, bang)).toURL()));
                    if (jar == null) return null;
                    String entry = new URI(s.substring(bang + 2)).getPath();
                    return new JarLoc(indexes.apply(jar),
                            entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry);
                }
                if (s.startsWith("file:")) {
                    return DirLoc.root(Path.of(url.toURI()));
                }
            } catch (URISyntaxException | java.net.MalformedURLException | IllegalArgumentException e) {
                // not a location we can read
            }
            return null;
        }

        final class Dir implements Root {
            private final Path base;
            private volatile DirLoc root;

            Dir(Path base) { this.base = base; }

            @Override public Loc at(String rel) {
                DirLoc r = root;
                if (r == null) {
                    r = DirLoc.root(base);
                    if (r.exists()) root = r; // the real path of a missing root is unknown yet
                }
                return r.resolve(rel);
            }
        }

        record Jar(JarIndex index) implements Root {
            @Override public Loc at(String rel) { return new JarLoc(index, ROOT_NAME).resolve(rel); }
        }
    }

    private record Missing() implements Loc {
        @Override public boolean exists() { return false; }
        @Override public boolean isDirectory() { return false; }
        @Override public boolean isFile() { return false; }
        @Override public List<String> children() { return List.of(); }
        @Override public InputStream open() throws IOException { throw new IOException("missing"); }
        @Override public URL url() { return null; }
        @Override public Loc resolve(String rel) { return this; }
    }

    /** {@code base} and {@code realBase} (its real path) are the directory the location must stay under. */
    private record DirLoc(Path base, Path realBase, Path path) implements Loc {
        static DirLoc root(Path dir) {
            try {
                Path n = dir.toAbsolutePath().normalize();
                Path real;
                try { real = n.toRealPath(); } catch (IOException e) { real = n; }
                return new DirLoc(n, real, n);
            } catch (java.nio.file.InvalidPathException | java.io.IOError e) {
                return new DirLoc(dir, dir, dir);
            }
        }

        @Override public Loc resolve(String rel) {
            if (rel.isEmpty()) return this;
            try {
                Path p = path.resolve(rel).normalize();
                if (!p.startsWith(base)) return new Missing();
                if (Files.exists(p) && !p.toRealPath().startsWith(realBase)) return new Missing();
                return new DirLoc(base, realBase, p);
            } catch (java.nio.file.InvalidPathException | IOException e) {
                return new Missing();
            }
        }
        @Override public boolean exists() { return Files.exists(path); }
        @Override public boolean isDirectory() { return Files.isDirectory(path); }
        @Override public boolean isFile() { return Files.isRegularFile(path); }
        @Override public List<String> children() {
            try (Stream<Path> s = Files.list(path)) {
                return s.filter(this::inside)
                        .map(p -> p.getFileName() + (Files.isDirectory(p) ? "/" : "")).sorted().toList();
            } catch (IOException e) {
                return List.of();
            }
        }
        private boolean inside(Path p) {
            try { return p.toRealPath().startsWith(realBase); } catch (IOException e) { return false; }
        }
        @Override public InputStream open() throws IOException { return Files.newInputStream(path); }
        @Override public URL url() {
            try {
                return path.toUri().toURL();
            } catch (java.net.MalformedURLException e) {
                return null;
            }
        }
    }

    /** {@code entry} is the jar entry name without trailing slash. */
    private record JarLoc(JarIndex index, String entry) implements Loc {
        @Override public Loc resolve(String rel) {
            String r = rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel;
            return r.isEmpty() ? this : new JarLoc(index, entry + "/" + r);
        }
        @Override public boolean exists() { return isFile() || isDirectory(); }
        @Override public boolean isFile() { return index.isFile(entry); }
        @Override public boolean isDirectory() { return index.isDirectory(entry); }
        @Override public List<String> children() { return index.children(entry); }

        @Override public InputStream open() throws IOException {
            JarFile f = new JarFile(index.jar.toFile());
            JarEntry e = f.getJarEntry(entry);
            if (e == null || e.isDirectory()) {
                f.close();
                throw new IOException("not a file: " + entry);
            }
            return new FilterInputStream(f.getInputStream(e)) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { f.close(); }
                }
            };
        }

        @Override public URL url() {
            try {
                String raw = new URI(null, null, entry, null).getRawPath();
                return new URI("jar:" + index.jar.toUri() + "!/" + raw + (isFile() ? "" : "/")).toURL();
            } catch (URISyntaxException | java.net.MalformedURLException e) {
                return null;
            }
        }
    }
}
