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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <p>Ordered roots are read directly (a jar entry list or a directory listing), so a jar without
 * directory entries is fine. Other roots are found with
 * {@link ClassLoader#getResources(String) getResources("META-INF/resources/" + path)}: a jar that
 * lacks the directory entry is then only reachable for plain files. Roots nested in another
 * archive ({@code jar:file:/a.war!/lib/b.jar!/...}) are not supported here.</p>
 *
 * <p>Paths holding a {@code ..} segment, a backslash or a NUL character resolve to nothing.
 * {@code getResource} may reach the {@code WEB-INF/} and {@code META-INF/} subtrees of the
 * resource root; the default servlet must additionally check {@link #isServable}.</p>
 */
public final class ClassPathResourceProvider implements ResourceProvider {

    private static final String ROOT = "META-INF/resources/";

    private final ClassLoader loader;
    private final List<Loc.Root> ordered = new ArrayList<>();
    private final Set<String> orderedKeys = new HashSet<>();

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
            if (p != null) ordered.add(Files.isDirectory(p) ? new Loc.Dir(p.resolve(ROOT)) : new Loc.Jar(p));
        }
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
        List<Loc> found = locate(path.substring(1));
        return found.isEmpty() ? null : found.get(0).url();
    }

    private static boolean safe(String path) {
        if (path == null || !path.startsWith("/")) return false;
        if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) return false;
        for (String segment : path.split("/", -1)) if (segment.equals("..")) return false;
        return true;
    }

    /** Every location holding {@code rel} (no leading slash), in priority order. */
    private List<Loc> locate(String rel) {
        Map<String, Loc> out = new LinkedHashMap<>();
        for (Loc.Root root : ordered) {
            Loc loc = root.at(rel);
            if (loc.exists()) out.putIfAbsent(loc.url().toString(), loc);
        }
        try {
            // Roots that declare META-INF/resources/ itself are read like the ordered ones: no
            // directory entry is needed below it.
            Enumeration<URL> roots = loader.getResources(ROOT.substring(0, ROOT.length() - 1));
            while (roots.hasMoreElements()) {
                URL rootUrl = roots.nextElement();
                if (orderedKeys.contains(Fragment.sourceKey(rootOf(rootUrl, ROOT.substring(0, ROOT.length() - 1))))) {
                    continue;
                }
                Loc base = Loc.of(rootUrl);
                Loc loc = base == null ? null : base.resolve(rel);
                if (loc != null && loc.exists()) out.putIfAbsent(loc.url().toString(), loc);
            }
        } catch (IOException e) {
            // unreadable roots are skipped
        }
        String name = ROOT + (rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel);
        try {
            Enumeration<URL> urls = loader.getResources(name);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if (orderedKeys.contains(Fragment.sourceKey(rootOf(url, name)))) continue;
                Loc loc = Loc.of(url);
                if (loc != null && loc.exists()) out.putIfAbsent(loc.url().toString(), loc);
            }
        } catch (IOException e) {
            // unreadable roots are skipped
        }
        return new ArrayList<>(out.values());
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

    /** A file or directory of a {@code META-INF/resources/} tree, on disk or inside a jar. */
    private sealed interface Loc {
        boolean exists();
        boolean isDirectory();
        boolean isFile();
        List<String> children();
        InputStream open() throws IOException;
        URL url();
        /** The location {@code rel} (relative, no leading slash) below this directory. */
        Loc resolve(String rel);

        /** A root that resolves a relative path (no leading slash) to a location. */
        sealed interface Root {
            Loc at(String rel);
        }

        static Loc of(URL url) {
            String s = url.toString();
            try {
                if (s.startsWith("jar:")) {
                    int bang = s.indexOf("!/");
                    if (bang < 0 || s.indexOf("!/", bang + 2) >= 0) return null;
                    Path jar = pathOf(Fragment.sourceKey(new URI(s.substring(4, bang)).toURL()));
                    if (jar == null) return null;
                    String entry = new URI(s.substring(bang + 2)).getPath();
                    return new JarLoc(jar, entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry);
                }
                if (s.startsWith("file:")) return new DirLoc(Path.of(url.toURI()));
            } catch (URISyntaxException | java.net.MalformedURLException | IllegalArgumentException e) {
                // not a location we can read
            }
            return null;
        }

        record Dir(Path base) implements Root {
            @Override public Loc at(String rel) { return new DirLoc(base).resolve(rel); }
        }

        record Jar(Path jar) implements Root {
            @Override public Loc at(String rel) {
                return new JarLoc(jar, ROOT.substring(0, ROOT.length() - 1)).resolve(rel);
            }
        }
    }

    private record DirLoc(Path path) implements Loc {
        @Override public Loc resolve(String rel) { return rel.isEmpty() ? this : new DirLoc(path.resolve(rel)); }
        @Override public boolean exists() { return Files.exists(path); }
        @Override public boolean isDirectory() { return Files.isDirectory(path); }
        @Override public boolean isFile() { return Files.isRegularFile(path); }
        @Override public List<String> children() {
            try (Stream<Path> s = Files.list(path)) {
                return s.map(p -> p.getFileName() + (Files.isDirectory(p) ? "/" : "")).toList();
            } catch (IOException e) {
                return List.of();
            }
        }
        @Override public InputStream open() throws IOException { return Files.newInputStream(path); }
        @Override public URL url() {
            try {
                return path.toUri().toURL();
            } catch (java.net.MalformedURLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** {@code entry} is the jar entry name without trailing slash. */
    private record JarLoc(Path jar, String entry) implements Loc {
        @Override public Loc resolve(String rel) {
            String r = rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel;
            return r.isEmpty() ? this : new JarLoc(jar, entry + "/" + r);
        }

        @Override public boolean exists() { return isFile() || isDirectory(); }

        @Override public boolean isFile() {
            try (JarFile f = new JarFile(jar.toFile())) {
                JarEntry e = f.getJarEntry(entry);
                return e != null && !e.isDirectory();
            } catch (IOException e) {
                return false;
            }
        }

        @Override public boolean isDirectory() {
            try (JarFile f = new JarFile(jar.toFile())) {
                if (f.getJarEntry(entry + "/") != null) return true;
                String prefix = entry + "/";
                return f.stream().anyMatch(e -> e.getName().startsWith(prefix));
            } catch (IOException e) {
                return false;
            }
        }

        @Override public List<String> children() {
            Set<String> out = new TreeSet<>();
            String prefix = entry + "/";
            try (JarFile f = new JarFile(jar.toFile())) {
                f.stream().map(JarEntry::getName).filter(n -> n.startsWith(prefix) && n.length() > prefix.length())
                        .forEach(n -> {
                            String rest = n.substring(prefix.length());
                            int slash = rest.indexOf('/');
                            out.add(slash < 0 ? rest : rest.substring(0, slash + 1));
                        });
            } catch (IOException e) {
                return List.of();
            }
            return new ArrayList<>(out);
        }

        @Override public InputStream open() throws IOException {
            JarFile f = new JarFile(jar.toFile());
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
                return new URI("jar:" + jar.toUri() + "!/" + raw + (isDirectory() && !isFile() ? "/" : "")).toURL();
            } catch (URISyntaxException | java.net.MalformedURLException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
