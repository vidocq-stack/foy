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
package io.vidocq.foy.internal.boot;

import io.vidocq.foy.internal.webxml.Fragment;
import io.vidocq.foy.internal.webxml.WebAppDescriptor;
import io.vidocq.foy.internal.webxml.WebXmlParser;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletException;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * What a class/module-path application is made of, discovered through its class loader: the web
 * fragments ({@code META-INF/web-fragment.xml} of each jar or directory root) and the
 * {@link ServletContainerInitializer}s ({@link ServiceLoader}), each attributed to the root it
 * comes from.
 *
 * <p>A <em>root</em> is identified by {@link Fragment#sourceKey}: the resource URL minus the
 * {@code META-INF/...} entry, i.e. the jar file for {@code jar:file:/x.jar!/META-INF/...} and
 * the directory for an exploded root. The same key is computed for a class's code source, so a
 * fragment, an initializer and an annotated class of the same jar compare equal.</p>
 */
public final class ApplicationSources {

    static final String FRAGMENT = "META-INF/web-fragment.xml";
    private static final List<String> WEB_XML = List.of("META-INF/web.xml", "WEB-INF/web.xml");

    private ApplicationSources() {}

    /** One discovered initializer and the root it was loaded from ({@code null} without code source). */
    public record Initializer(ServletContainerInitializer sci, URL jar) {}

    /**
     * Every {@code META-INF/web-fragment.xml} visible to {@code loader}, parsed, at most one per
     * root (a root listed twice is read once) and sorted by {@link Fragment#sourceKey} for a
     * deterministic discovery order. A fragment's id is its {@code <name>} when present, else the
     * root's file name ({@code a.jar}).
     *
     * @throws ServletException when a fragment cannot be read or is malformed; the message names
     *         its URL
     */
    public static List<Fragment> fragments(ClassLoader loader) throws ServletException {
        var byKey = new TreeMap<String, URL>();
        for (URL url : resources(loader, FRAGMENT)) byKey.putIfAbsent(rootKey(url, FRAGMENT), url);
        var out = new ArrayList<Fragment>(byKey.size());
        for (var e : byKey.entrySet()) {
            WebAppDescriptor descriptor;
            try (InputStream in = e.getValue().openStream()) {
                descriptor = WebXmlParser.parseFragment(in);
            } catch (IOException | RuntimeException ex) {
                throw new ServletException("Malformed web-fragment.xml in " + e.getValue() + ": "
                        + ex.getMessage(), ex);
            }
            String key = e.getKey();
            String id = descriptor.fragmentName() != null
                    ? descriptor.fragmentName() : key.substring(key.lastIndexOf('/') + 1);
            out.add(new Fragment(id, toUrl(key, e.getValue()), descriptor));
        }
        return List.copyOf(out);
    }

    /**
     * Every initializer {@link ServiceLoader} finds through {@code loader}, in discovery order;
     * equivalent to {@code initializers(loader, root -> true)}.
     */
    public static List<Initializer> initializers(ClassLoader loader) {
        return initializers(loader, root -> true);
    }

    /**
     * The initializers {@link ServiceLoader} finds through {@code loader} whose root (the
     * normalised code source of the provider type, {@code null} without code source) is accepted
     * by {@code retained}, in {@link ServiceLoader} discovery order — the class-loading
     * delegation order §8.2.4 requires. Only retained providers are instantiated (by
     * {@link ServiceLoader}, never by reflection): an excluded initializer's constructor never runs.
     *
     * @throws java.util.ServiceConfigurationError when a provider cannot be loaded, or a retained
     *         one cannot be instantiated
     */
    public static List<Initializer> initializers(ClassLoader loader, Predicate<URL> retained) {
        var out = new ArrayList<Initializer>();
        ServiceLoader.load(ServletContainerInitializer.class, loader).stream().forEach(p -> {
            URL jar = root(p.type());
            if (retained.test(jar)) out.add(new Initializer(p.get(), jar));
        });
        return List.copyOf(out);
    }

    /**
     * The roots of the application itself: those holding a {@code META-INF/web.xml} or
     * {@code WEB-INF/web.xml} and no web fragment (none of {@code fragmentJars}).
     */
    public static Set<URL> applicationRoots(ClassLoader loader, Set<URL> fragmentJars) throws ServletException {
        Set<String> fragmentKeys = keys(fragmentJars);
        var out = new LinkedHashSet<URL>();
        for (String resource : WEB_XML) {
            for (URL url : resources(loader, resource)) {
                String key = rootKey(url, resource);
                if (!fragmentKeys.contains(key)) out.add(toUrl(key, url));
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /** The normalised code-source roots of the classes of {@code annotated}'s components. */
    public static Set<URL> codeSources(DescriptorMerger.AnnotatedComponents annotated) {
        var out = new LinkedHashSet<URL>();
        annotated.servlets().forEach(s -> addRoot(out, s.type()));
        annotated.filters().forEach(f -> addRoot(out, f.type()));
        annotated.listeners().forEach(l -> addRoot(out, l.type()));
        return Collections.unmodifiableSet(out);
    }

    private static void addRoot(Set<URL> out, Class<?> type) {
        URL root = root(type);
        if (root != null) out.add(root);
    }

    /** The jars of the discovered fragments ({@code all}) that the ordering left out ({@code ordered}). */
    public static Set<URL> excludedJars(List<Fragment> all, List<Fragment> ordered) {
        Set<String> kept = new HashSet<>();
        for (Fragment f : ordered) kept.add(Fragment.sourceKey(f.jar()));
        var out = new LinkedHashSet<URL>();
        for (Fragment f : all) if (!kept.contains(Fragment.sourceKey(f.jar()))) out.add(f.jar());
        return Collections.unmodifiableSet(out);
    }

    /**
     * Servlet 6.1 §8.2.4 as a filter on an initializer's root: the initializers of the jars
     * excluded by an absolute ordering are not run. A root is accepted when it:
     * <ol>
     *   <li>holds a fragment kept by the ordering ({@code ordered}) — accepted;</li>
     *   <li>else holds a fragment ({@code allFragmentJars}) the ordering excluded — rejected;</li>
     *   <li>else is a root of the application itself ({@code applicationJars}) — accepted;</li>
     *   <li>else is unknown ({@code null}, no code source) — accepted, it cannot belong to an
     *       excluded jar;</li>
     *   <li>else is a library jar without fragment, an unnamed fragment for this purpose:
     *       rejected when {@code absoluteOrdering} is present without {@code <others/>} (its
     *       initializers are then not run), accepted otherwise.</li>
     * </ol>
     * A {@code metadata-complete} web.xml changes nothing here: its fragments are not merged but
     * their ordering still filters the initializers.
     *
     * @param absoluteOrdering web.xml's absolute ordering ({@link WebAppDescriptor#OTHERS} for
     *                         {@code <others/>}), {@code null} when absent
     */
    public static Predicate<URL> ordering(List<String> absoluteOrdering, List<Fragment> ordered,
                                          Set<URL> allFragmentJars, Set<URL> applicationJars) {
        Set<String> orderedKeys = new HashSet<>();
        for (Fragment f : ordered) orderedKeys.add(Fragment.sourceKey(f.jar()));
        Set<String> fragmentKeys = keys(allFragmentJars);
        Set<String> appKeys = keys(applicationJars);
        boolean unnamedExcluded = absoluteOrdering != null && !absoluteOrdering.contains(WebAppDescriptor.OTHERS);
        return jar -> {
            if (jar == null) return true;
            String key = Fragment.sourceKey(jar);
            if (orderedKeys.contains(key)) return true;
            if (fragmentKeys.contains(key)) return false;
            if (appKeys.contains(key)) return true;
            return !unnamedExcluded;
        };
    }

    /** {@code all} filtered by {@link #ordering}, in the order of {@code all}. */
    public static List<Initializer> retainOrdered(List<Initializer> all, List<String> absoluteOrdering,
                                                  List<Fragment> ordered, Set<URL> allFragmentJars,
                                                  Set<URL> applicationJars) {
        Predicate<URL> keep = ordering(absoluteOrdering, ordered, allFragmentJars, applicationJars);
        return all.stream().filter(i -> keep.test(i.jar())).toList();
    }

    /**
     * The jars and directories among {@code roots}, normalised, deduplicated and in order, as the
     * {@code @HandlesTypes} class-bytes scan expects them. Roots that are not {@code file:} locations
     * (remote or nested archives) are left out: they cannot be walked.
     */
    public static List<java.nio.file.Path> scanRoots(Collection<URL> roots) {
        var out = new java.util.LinkedHashSet<java.nio.file.Path>();
        for (URL root : roots) {
            try {
                URI uri = URI.create(Fragment.sourceKey(root));
                if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getAuthority() == null) {
                    out.add(java.nio.file.Path.of(uri));
                }
            } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
                // not a local location: not scanned
            }
        }
        return List.copyOf(out);
    }

    /** Normalised root of {@code type}'s code source; {@code null} without one. */
    private static URL root(Class<?> type) {
        CodeSource cs = type.getProtectionDomain().getCodeSource();
        URL location = cs == null ? null : cs.getLocation();
        return location == null ? null : toUrl(Fragment.sourceKey(location), location);
    }

    private static Set<String> keys(Set<URL> urls) {
        Set<String> out = new HashSet<>();
        for (URL u : urls) out.add(Fragment.sourceKey(u));
        return out;
    }

    private static List<URL> resources(ClassLoader loader, String name) throws ServletException {
        try {
            return Collections.list(loader.getResources(name));
        } catch (IOException e) {
            throw new ServletException("Cannot list " + name + " resources", e);
        }
    }

    /** {@link Fragment#sourceKey} of the root holding {@code url}, the resource {@code name}. */
    private static String rootKey(URL url, String name) {
        String s = url.toString();
        if (s.endsWith(name)) s = s.substring(0, s.length() - name.length());
        try {
            return Fragment.sourceKey(URI.create(s).toURL());
        } catch (MalformedURLException | IllegalArgumentException e) {
            return Fragment.sourceKey(url);
        }
    }

    private static URL toUrl(String key, URL fallback) {
        try {
            return URI.create(key).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            return fallback;
        }
    }
}
