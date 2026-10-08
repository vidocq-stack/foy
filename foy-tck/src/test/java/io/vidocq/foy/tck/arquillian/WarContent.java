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

import io.vidocq.foy.internal.gen.WebComponentRegistry;
import io.vidocq.foy.internal.webxml.Fragment;
import io.vidocq.foy.internal.webxml.WebAppDescriptor;
import io.vidocq.foy.internal.webxml.WebXmlParser;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.spec.WebArchive;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * What a TCK {@link WebArchive} is made of, read from its listing: the classes of
 * {@code WEB-INF/classes} and of every {@code WEB-INF/lib/*.jar}, each attributed to a stable
 * synthetic root URL ({@code file:/<archive>/WEB-INF/classes/}, {@code file:/<archive>/WEB-INF/lib/<jar>}),
 * {@code WEB-INF/web.xml}, the lib jars' {@code META-INF/web-fragment.xml} (one {@link Fragment}
 * per jar, whose {@code jar} is the jar's synthetic URL), the
 * {@code META-INF/services/jakarta.servlet.ServletContainerInitializer} files, and the lib jars'
 * {@code META-INF/resources/**}.
 *
 * <p>The classes themselves are loaded from the test class loader, which already holds them
 * (the TCK jar is on the test classpath); only the descriptors, the class lists, the resources
 * and the initializer names come from the archive.</p>
 */
final class WarContent {

    private static final String LIB = "/WEB-INF/lib/";
    private static final String CLASSES = "/WEB-INF/classes/";
    private static final String SCI_FILE = "META-INF/services/jakarta.servlet.ServletContainerInitializer";
    private static final String RESOURCES = "META-INF/resources/";

    private final URL classesRoot;
    private final WebAppDescriptor webXml;
    private final boolean hasWebXml;
    private final List<Fragment> fragments;
    private final Map<String, URL> sources;
    private final Map<URL, List<String>> initializerNames;
    private final Map<String, byte[]> jarResources;
    private List<Class<?>> loaded;

    private WarContent(URL classesRoot, WebAppDescriptor webXml, boolean hasWebXml, List<Fragment> fragments,
                       Map<String, URL> sources, Map<URL, List<String>> initializerNames,
                       Map<String, byte[]> jarResources) {
        this.classesRoot = classesRoot;
        this.webXml = webXml;
        this.hasWebXml = hasWebXml;
        this.fragments = List.copyOf(fragments);
        this.sources = Collections.unmodifiableMap(sources);
        this.initializerNames = Collections.unmodifiableMap(initializerNames);
        this.jarResources = Collections.unmodifiableMap(jarResources);
    }

    /**
     * Reads {@code war}.
     *
     * @throws DeploymentException when web.xml or a web fragment is malformed, or an archive part
     *         cannot be read
     */
    static WarContent read(WebArchive war) throws DeploymentException {
        String archive = war.getName() == null || war.getName().isEmpty() ? "war" : war.getName();
        URL classesRoot = root(archive, CLASSES);
        // WEB-INF/classes first: it wins over a lib jar holding the same class (delegation order).
        var sources = new LinkedHashMap<String, URL>();
        var initializers = new LinkedHashMap<URL, List<String>>();
        var libs = new TreeMap<String, Node>();
        for (Node node : war.getContent().values()) {
            String path = node.getPath().get();
            if (node.getAsset() == null) continue;
            if (path.startsWith(LIB)) {
                String jar = path.substring(LIB.length());
                if (jar.endsWith(".jar") && jar.indexOf('/') < 0) libs.put(jar, node);
            } else if (path.startsWith(CLASSES) && path.endsWith(".class")) {
                sources.putIfAbsent(className(path.substring(CLASSES.length())), classesRoot);
            } else if (path.equals(CLASSES + SCI_FILE)) {
                // Only the war's class-path roots carry services: WEB-INF/classes here, the lib jars below.
                try (InputStream in = node.getAsset().openStream()) {
                    initializers.computeIfAbsent(classesRoot, k -> new ArrayList<>()).addAll(serviceNames(in));
                } catch (IOException e) {
                    throw new DeploymentException("[VidocqTCK] cannot read " + path + ": " + e, e);
                }
            }
        }

        WebAppDescriptor webXml = WebAppDescriptor.empty();
        boolean hasWebXml = false;
        Node webXmlNode = war.get("/WEB-INF/web.xml");
        if (webXmlNode != null && webXmlNode.getAsset() != null) {
            try (InputStream in = webXmlNode.getAsset().openStream()) {
                webXml = WebXmlParser.parse(in);
                hasWebXml = true;
            } catch (IOException | RuntimeException e) {
                throw new DeploymentException("[VidocqTCK] malformed WEB-INF/web.xml: " + e.getMessage(), e);
            }
        }

        var fragments = new ArrayList<Fragment>();
        var jarResources = new LinkedHashMap<String, byte[]>();
        for (var lib : libs.entrySet()) {
            String jarName = lib.getKey();
            URL jarUrl = root(archive, LIB + jarName);
            byte[] fragmentXml = null;
            try (var zip = new ZipInputStream(lib.getValue().getAsset().openStream())) {
                for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                    if (e.isDirectory()) continue;
                    String name = e.getName().startsWith("/") ? e.getName().substring(1) : e.getName();
                    if (name.endsWith(".class")) {
                        sources.putIfAbsent(className(name), jarUrl);
                    } else if (name.equals("META-INF/web-fragment.xml")) {
                        fragmentXml = zip.readAllBytes();
                    } else if (name.equals(SCI_FILE)) {
                        initializers.computeIfAbsent(jarUrl, k -> new ArrayList<>()).addAll(serviceNames(zip));
                    } else if (name.startsWith(RESOURCES) && name.length() > RESOURCES.length()) {
                        jarResources.putIfAbsent("/" + name.substring(RESOURCES.length()), zip.readAllBytes());
                    }
                }
            } catch (IOException e) {
                throw new DeploymentException("[VidocqTCK] cannot read WEB-INF/lib/" + jarName + ": " + e, e);
            }
            if (fragmentXml == null) continue;
            WebAppDescriptor descriptor;
            try {
                descriptor = WebXmlParser.parseFragment(new ByteArrayInputStream(fragmentXml));
            } catch (IOException | RuntimeException e) {
                throw new DeploymentException("[VidocqTCK] malformed web-fragment.xml in WEB-INF/lib/" + jarName
                        + ": " + e.getMessage(), e);
            }
            String id = descriptor.fragmentName() != null ? descriptor.fragmentName() : jarName;
            fragments.add(new Fragment(id, jarUrl, descriptor));
        }
        return new WarContent(classesRoot, webXml, hasWebXml, fragments, sources, initializers, jarResources);
    }

    /** The synthetic root of {@code WEB-INF/classes}, the application root for §8.2.4. */
    URL classesRoot() { return classesRoot; }

    /** web.xml, or an empty descriptor when the war has none. */
    WebAppDescriptor webXml() { return webXml; }

    boolean hasWebXml() { return hasWebXml; }

    /** The lib jars' fragments, in jar-name order (deterministic discovery order). */
    List<Fragment> fragments() { return fragments; }

    List<String> fragmentIds() { return fragments.stream().map(Fragment::id).toList(); }

    /** Every class name of the war to its synthetic root: WEB-INF/classes first, then the lib jars. */
    Map<String, URL> sources() { return sources; }

    /** Initializer class names by root: WEB-INF/classes first, then the lib jars in jar-name order. */
    Map<URL, List<String>> initializerNames() { return initializerNames; }

    /** The lib jars' {@code META-INF/resources/**}, as context paths; the first jar wins. */
    Map<String, byte[]> jarResources() { return jarResources; }

    /** The war's classes the class loader can load, in {@link #sources} order. */
    List<Class<?>> loadable(ClassLoader cl) {
        if (loaded == null) {
            var out = new ArrayList<Class<?>>();
            for (String name : sources.keySet()) {
                try {
                    out.add(WebComponentRegistry.loadClass(name, cl));
                } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
                    // not on the test classpath (or not linkable): not a component of this deployment
                }
            }
            loaded = List.copyOf(out);
        }
        return loaded;
    }

    List<String> loadableNames(ClassLoader cl) {
        return loadable(cl).stream().map(Class::getName).toList();
    }

    private static String className(String entry) {
        return entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
    }

    private static List<String> serviceNames(InputStream in) throws IOException {
        var out = new ArrayList<String>();
        // Not closed: the caller owns the stream (a zip entry must not close the archive).
        var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        for (String line; (line = reader.readLine()) != null; ) {
            int hash = line.indexOf('#');
            String fqn = (hash >= 0 ? line.substring(0, hash) : line).trim();
            if (!fqn.isEmpty()) out.add(fqn);
        }
        return out;
    }

    /** {@code file:/<archive><path>}, built so that {@link Fragment#sourceKey} normalises it. */
    private static URL root(String archive, String path) {
        try {
            return new URI("file", null, "/" + archive + path, null).toURL();
        } catch (URISyntaxException | MalformedURLException e) {
            throw new IllegalArgumentException("cannot name " + archive + path, e);
        }
    }
}
