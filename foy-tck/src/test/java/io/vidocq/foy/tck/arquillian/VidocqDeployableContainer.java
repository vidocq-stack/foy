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

import io.vidocq.foy.internal.boot.ApplicationSources;
import io.vidocq.foy.internal.boot.ComponentFactory;
import io.vidocq.foy.internal.boot.DescriptorMerger;
import io.vidocq.foy.internal.boot.DescriptorMerger.AnnotatedComponents;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.gen.ClassFileDescriptorReader;
import io.vidocq.foy.internal.gen.ClassFileHandlesTypesScanner;
import io.vidocq.foy.internal.gen.IndexedHandlesTypesResolver;
import io.vidocq.foy.internal.gen.RegistryComponentFactory;
import io.vidocq.foy.internal.gen.WebComponentRegistry;
import io.vidocq.foy.internal.webxml.Fragment;
import io.vidocq.foy.internal.webxml.FragmentMerger;
import io.vidocq.foy.internal.webxml.FragmentOrderer;
import io.vidocq.foy.internal.webxml.WebAppDescriptor;
import io.vidocq.foy.tck.ServletTestHarness;
import io.vidocq.foy.tck.ServletTestHost;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletException;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.deployment.Validate;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@link DeployableContainer} Arquillian who deploys a {@link WebArchive} on the
 * {@link ServletTestHarness} internal.
 *
 * <p>Strategy: list the archive ({@link WarContent}: {@code WEB-INF/classes}, every
 * {@code WEB-INF/lib} jar, web.xml, the jars' web fragments, initializers and
 * {@code META-INF/resources}), then deploy it through the product pipeline: fragments ordered by
 * {@link FragmentOrderer} (§8.2.2), merged with web.xml and the annotated classes by
 * {@link DescriptorMerger} (§8.2.3, {@code metadata-complete} included), the initializers the
 * ordering retains (§8.2.4), and {@code WebAppDeployer}. The classes are loaded from the test class
 * loader (the TCK jar is on the test classpath) and attributed to their war part through synthetic
 * root URLs. Metadata and instances go through one {@link WebComponentRegistry} per deployment,
 * whose tier statistics are logged on undeploy. {@code @HandlesTypes} is resolved against the war's
 * own classes, since TCK wars carry no build-time class index. Returns to {@link ProtocolMetaData}
 * {@code Servlet 3.0} with the harness URL for Arquillian to inject {@code @ArquillianResource URL url}.</p>
 */
public class VidocqDeployableContainer implements DeployableContainer<VidocqContainerConfiguration> {

    private VidocqContainerConfiguration config;
    /** The one server of this container, started by the first deployment, stopped by {@link #stop()}. */
    private ServletTestHost host;
    /** Multi-deployment support (Arquillian can deploy several WARs for a test). */
    private final java.util.LinkedHashMap<String, ServletTestHarness> harnessesByArchive = new java.util.LinkedHashMap<>();
    /** The component registry of each deployed archive, for the undeploy tier statistics. */
    private final java.util.Map<String, WebComponentRegistry> registriesByArchive = new java.util.HashMap<>();

    @Override
    public Class<VidocqContainerConfiguration> getConfigurationClass() {
        return VidocqContainerConfiguration.class;
    }

    @Override
    public void setup(VidocqContainerConfiguration cfg) {
        this.config = cfg;
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Servlet 3.0");
    }

    @Override
    public void start() throws LifecycleException {
        // The shared host starts with the first deployment (deploy()) and stops in stop().
    }

    @Override
    public void stop() throws LifecycleException {
        for (var h : harnessesByArchive.values()) {
            try { h.close(); } catch (RuntimeException ignored) {}
        }
        harnessesByArchive.clear();
        registriesByArchive.forEach((name, registry) ->
                System.err.println("[VidocqTCK] undeploy archive=" + name + " tiers=" + registry.stats()));
        registriesByArchive.clear();
        synchronized (this) {
            if (host != null) { host.close(); host = null; }
        }
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        Validate.notNull(archive, "archive");
        if (!(archive instanceof WebArchive war)) {
            throw new DeploymentException("only WebArchive supported, got " + archive.getClass());
        }

        // The TCK client sends /<war-name>/... and getContextPath() must report that path.
        String archiveName = archive.getName();
        String contextPath = "/";
        if (archiveName != null) {
            String ctxName = archiveName.endsWith(".war")
                    ? archiveName.substring(0, archiveName.length() - 4) : archiveName;
            if (!ctxName.isEmpty()) contextPath = "/" + ctxName;
        }
        var cl = Thread.currentThread().getContextClassLoader();
        var registry = WebComponentRegistry.forClassLoader(cl);
        WarContent content = WarContent.read(war);
        // Visible names simulate an isolated web application class loader: the war's classes,
        // those of WEB-INF/classes and of every WEB-INF/lib jar, are loaded from the test class
        // loader (the TCK jar is on the test classpath), but a by-name registration of a class
        // outside the war is refused.
        var factory = new RegistryComponentFactory(registry, cl, Set.copyOf(content.sources().keySet()));

        WebAppModel.Builder model = WebAppModel.builder(contextPath);
        WebAppDescriptor effective;
        List<ServletContainerInitializer> initializers;
        try {
            WebAppDescriptor webXml = content.webXml();
            List<Fragment> fragments = content.fragments();
            List<Fragment> ordered = FragmentOrderer.order(webXml.absoluteOrdering(), fragments);
            Function<Class<?>, URL> sourceOf = type -> content.sources().get(type.getName());
            AnnotatedComponents annotated = annotatedComponents(content.loadable(cl), factory)
                    .excludingSources(ApplicationSources.excludedJars(fragments, ordered), sourceOf);
            effective = FragmentMerger.merge(webXml, ordered);
            DescriptorMerger.mergeMerged(effective, ordered, annotated, sourceOf, factory, model);
            if (content.hasWebXml()) effectiveVersion(model, webXml.version());
            Set<URL> fragmentJars = new LinkedHashSet<>();
            for (Fragment f : fragments) fragmentJars.add(f.jar());
            initializers = initializers(content, ApplicationSources.ordering(webXml.absoluteOrdering(), ordered,
                    fragmentJars, Set.of(content.classesRoot())), factory);
        } catch (ServletException | RuntimeException e) {
            throw new DeploymentException("[VidocqTCK] cannot deploy " + archiveName + ": " + e.getMessage(), e);
        }
        initializers.forEach(model::initializer);

        Set<String> reservedServlets = new LinkedHashSet<>();
        Set<String> reservedFilters = new LinkedHashSet<>();
        Set<String> reservedPatterns = new LinkedHashSet<>();
        for (var sd : effective.servlets()) if (sd.name() != null) reservedServlets.add(sd.name());
        for (var fd : effective.filters()) if (fd.name() != null) reservedFilters.add(fd.name());
        // §4.4 ServletRegistration.addMapping: a pattern the descriptors map is reserved; a
        // dynamic addMapping of it is refused and reported in the conflict set.
        for (var m : effective.servletMappings()) if (m.urlPattern() != null) reservedPatterns.add(m.urlPattern());

        ServletTestHarness harness;
        try {
            host = host();
            harness = ServletTestHarness.builder()
                .host(host)
                .model(model)
                .registry(registry)
                .componentFactory(factory)
                .reserved(reservedServlets, reservedFilters, reservedPatterns)
                // @HandlesTypes over the war's own classes (TCK wars carry no class index).
                .handlesTypes(IndexedHandlesTypesResolver.scanOnly(registry, cl,
                        () -> ClassFileHandlesTypesScanner.scanNamed(content.loadableNames(cl), cl)))
                // The war root, then the META-INF/resources of its lib jars (§4.6).
                .resourceProvider(new WarResourceProvider(war, content.jarResources()))
                .start();
        } catch (RuntimeException e) {
            throw new DeploymentException("[VidocqTCK] cannot deploy " + archiveName + ": " + e.getMessage(), e);
        }
        harnessesByArchive.put(archive.getName(), harness);
        registriesByArchive.put(archive.getName(), registry);

        List<String> registered = harness.model().servlets().stream().map(ServletDecl::name).toList();
        System.err.println("[VidocqTCK] deploy archive=" + war.getName()
                + " host=" + config.getHost() + " port=" + harness.port()
                + " fragments=" + content.fragmentIds()
                + " servlets=" + registered + " baseUrl=" + harness.baseUrl());

        ProtocolMetaData pmd = new ProtocolMetaData();
        var ctx = new HTTPContext(config.getHost(), harness.port());
        // The Arquillian servlet's "contextRoot" is the path the TCK requests go under: our
        // context path, or "/" for a root war (Arquillian derives getPath() from the URL).
        String tckContextRoot = harness.baseUrl().substring(
                ("http://" + config.getHost() + ":" + harness.port()).length());
        if (tckContextRoot.isEmpty()) tckContextRoot = "/";
        ctx.add(new org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet(
                registered.isEmpty() ? "_vidocq" : registered.get(0), tckContextRoot));
        pmd.addContext(ctx);
        return pmd;
    }

    /** web-app/version as the effective Servlet version ({@code getEffectiveMajorVersion}). */
    private static void effectiveVersion(WebAppModel.Builder model, String version) {
        if (version == null) return;
        int dot = version.indexOf('.');
        try {
            int major = Integer.parseInt(dot < 0 ? version : version.substring(0, dot));
            int minor = dot < 0 ? 0 : Integer.parseInt(version.substring(dot + 1));
            model.effectiveVersion(major, minor);
        } catch (NumberFormatException ignored) {
            // malformed version: the container default stays
        }
    }

    /**
     * The annotated servlets, filters and listeners among the war's classes, through the product's
     * {@link AnnotatedComponents#fromDescriptors}; instances come from the factory. Classes are
     * classified from their bytes first, so plain war classes never enter the registry.
     *
     * @throws IllegalArgumentException when a class misuses the Servlet annotations (the
     *         deployment fails, as in the product)
     */
    private static AnnotatedComponents annotatedComponents(List<Class<?>> classes, ComponentFactory factory) {
        List<Class<?>> annotated = new ArrayList<>();
        for (Class<?> cls : classes) {
            if (ClassFileDescriptorReader.read(cls).isPresent()) annotated.add(cls);
        }
        return AnnotatedComponents.fromDescriptors(annotated, factory::descriptor,
                (cls, base) -> supplier(factory, cls));
    }

    private static <T> Supplier<T> supplier(ComponentFactory factory, Class<T> type) {
        return () -> {
            try {
                return factory.newInstance(type);
            } catch (ServletException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        };
    }

    /**
     * The {@link ServletContainerInitializer}s of {@code WEB-INF/classes}, then of each lib jar
     * in discovery order, whose root {@code retained} accepts (§8.2.4: a jar excluded by the
     * absolute ordering contributes none); only those are loaded and instantiated, through the
     * factory.
     *
     * @throws ServletException when a retained initializer cannot be loaded or created (the
     *         deployment fails, like a descriptor class that cannot be loaded)
     */
    private static List<ServletContainerInitializer> initializers(WarContent content, Predicate<URL> retained,
                                                                  ComponentFactory factory) throws ServletException {
        var out = new ArrayList<ServletContainerInitializer>();
        for (var e : content.initializerNames().entrySet()) {
            if (!retained.test(e.getKey())) continue;
            for (String fqn : e.getValue()) {
                try {
                    Class<?> c = factory.load(fqn);
                    out.add(factory.newInstance(c.asSubclass(ServletContainerInitializer.class)));
                } catch (ClassNotFoundException | ClassCastException | LinkageError t) {
                    throw new ServletException("cannot load ServletContainerInitializer " + fqn + ": " + t, t);
                }
            }
        }
        return out;
    }

    private synchronized ServletTestHost host() {
        if (host == null) host = new ServletTestHost();
        return host;
    }

    @Override
    public void undeploy(Archive<?> archive) {
        ServletTestHarness h = harnessesByArchive.remove(archive.getName());
        if (h != null) h.close();
        WebComponentRegistry registry = registriesByArchive.remove(archive.getName());
        if (registry != null) {
            System.err.println("[VidocqTCK] undeploy archive=" + archive.getName() + " tiers=" + registry.stats());
        }
    }

    @Override public void deploy(Descriptor descriptor) {}
    @Override public void undeploy(Descriptor descriptor) {}

    /** Exposes files from a {@link WebArchive}, then the {@code META-INF/resources} of its lib
     *  jars, through
     *  {@link io.vidocq.foy.internal.container.VidocqServletContext.ResourceProvider}.
     *  Materializes assets in a mirrored temporary directory so {@code getResource()} can
     *  return a {@code file:} URL containing the original path (required by TCK
     *  ServletContextTests.getResource, which checks that the URL contains
     *  {@code /WEB-INF/web.xml}). */
    private static final class WarResourceProvider
            implements io.vidocq.foy.internal.container.VidocqServletContext.ResourceProvider {
        private final WebArchive war;
        private final Map<String, byte[]> jarResources;
        private final java.nio.file.Path mirror;

        WarResourceProvider(WebArchive war, Map<String, byte[]> jarResources) {
            this.war = war;
            this.jarResources = jarResources;
            java.nio.file.Path base;
            try {
                base = java.nio.file.Files.createTempDirectory("vidocq-war-");
                base.toFile().deleteOnExit();
            } catch (java.io.IOException e) {
                base = null;
            }
            this.mirror = base;
            if (mirror != null) materialize();
        }

        private void materialize() {
            for (Node node : war.getContent().values()) {
                String p = node.getPath().get();
                if (p == null || p.isEmpty()) continue;
                try {
                    String rel = p.startsWith("/") ? p.substring(1) : p;
                    java.nio.file.Path dst = mirror.resolve(rel);
                    if (node.getAsset() == null) {
                        java.nio.file.Files.createDirectories(dst);
                    } else {
                        java.nio.file.Files.createDirectories(dst.getParent());
                        try (var in = node.getAsset().openStream()) {
                            java.nio.file.Files.copy(in, dst,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } catch (java.io.IOException ignored) {}
            }
            // Lib jar resources come after the war root: a war file of the same path wins.
            for (var e : jarResources.entrySet()) {
                try {
                    java.nio.file.Path dst = mirror.resolve(e.getKey().substring(1));
                    if (java.nio.file.Files.exists(dst)) continue;
                    java.nio.file.Files.createDirectories(dst.getParent());
                    java.nio.file.Files.write(dst, e.getValue());
                } catch (java.io.IOException | RuntimeException ignored) {}
            }
        }

        @Override public java.util.Set<String> listPaths(String path) {
            if (path == null || !path.startsWith("/")) return null;
            String prefix = path.endsWith("/") ? path : path + "/";
            java.util.List<String> paths = new ArrayList<>();
            for (Node node : war.getContent().values()) paths.add(node.getPath().get());
            paths.addAll(jarResources.keySet());
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            for (String p : paths) {
                if (p == null || !p.startsWith(prefix) || p.equals(prefix)) continue;
                String rest = p.substring(prefix.length());
                int slash = rest.indexOf('/');
                if (slash >= 0) {
                    out.add(prefix + rest.substring(0, slash + 1));
                } else if (!rest.isEmpty()) {
                    out.add(prefix + rest);
                }
            }
            return out;
        }

        @Override public java.io.InputStream openStream(String path) {
            if (path == null) return null;
            Node node = war.get(path);
            if (node != null && node.getAsset() != null) return node.getAsset().openStream();
            if (node != null) return null;
            byte[] bytes = jarResources.get(path);
            return bytes == null ? null : new java.io.ByteArrayInputStream(bytes);
        }

        @Override public java.net.URL toUrl(String path) {
            if (mirror == null || path == null || !path.startsWith("/")) return null;
            java.nio.file.Path p = mirror.resolve(path.substring(1));
            if (!java.nio.file.Files.exists(p)) return null;
            try { return p.toUri().toURL(); } catch (java.net.MalformedURLException e) { return null; }
        }
    }
}
