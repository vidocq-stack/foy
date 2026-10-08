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

import io.vidocq.foy.internal.boot.HandlesTypesResolver;
import io.vidocq.foy.internal.gen.ClassFileHandlesTypesScanner;
import io.vidocq.foy.internal.gen.IndexedHandlesTypesResolver;
import io.vidocq.foy.internal.gen.ClassFileDescriptorReader;
import io.vidocq.foy.internal.gen.WebComponentRegistry;
import io.vidocq.foy.spi.gen.WebComponent;
import io.vidocq.foy.spi.gen.WebComponentDescriptor;
import io.vidocq.foy.tck.ServletTestHarness;
import io.vidocq.foy.internal.webxml.WebAppDescriptor;
import io.vidocq.foy.internal.webxml.WebXmlParser;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
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

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link DeployableContainer} Arquillian who deploys a {@link WebArchive} on the
 * {@link ServletTestHarness} internal.
 *
 * <p>Strategy: extract the classes of {@code WEB-INF/classes/} from the archive (the current
 * class loader knows them since the TCK jar is on the test classpath), classify them from their
 * class bytes ({@code @WebServlet/@WebFilter/@WebListener}), and resolve their metadata and
 * instances through one {@link WebComponentRegistry} per deployment, whose tier statistics are
 * logged on undeploy. {@code @HandlesTypes} is resolved against the WAR's own classes, since TCK
 * WARs carry no build-time class index. Returns to {@link ProtocolMetaData} {@code Servlet 3.0}
 * with the harness URL for Arquillian to inject {@code @ArquillianResource URL url}.</p>
 */
public class VidocqDeployableContainer implements DeployableContainer<VidocqContainerConfiguration> {

    private VidocqContainerConfiguration config;
    private ServletTestHarness harness;
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
        // Harness déploiement-par-déploiement : démarré dans deploy(), arrêté dans undeploy().
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
        if (harness != null) { harness.close(); harness = null; }
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        Validate.notNull(archive, "archive");
        if (!(archive instanceof WebArchive war)) {
            throw new DeploymentException("only WebArchive supported, got " + archive.getClass());
        }

        // Nouveau deployment : garde les harnesses existants vivants en parallèle
        // (certains tests TCK comme DispatchTests déploient plusieurs WAR).
        var builder = ServletTestHarness.builder();
        // Fixe le contextPath au nom du WAR (sans extension) — le TCK client
        // envoie typiquement des URLs en /<war-name>/... et getContextPath()
        // doit remonter ce chemin.
        String archiveName = archive.getName();
        if (archiveName != null) {
            String ctxName = archiveName;
            if (ctxName.endsWith(".war")) ctxName = ctxName.substring(0, ctxName.length() - 4);
            if (!ctxName.isEmpty()) builder.contextPath("/" + ctxName);
        }
        var cl = Thread.currentThread().getContextClassLoader();
        var registry = WebComponentRegistry.forClassLoader(cl);
        builder.registry(registry);
        List<String> registered = new ArrayList<>();
        var warClassList = new ArrayList<String>();

        // 1) Classes @WebServlet/@WebFilter/@WebListener dans /WEB-INF/classes/
        //    + collecte des class-names du WAR pour simuler l'isolation classloader
        //    (certaines TCK classes NotFound sont dans le jar runtime mais pas dans le WAR).
        var warClassNames = new java.util.HashSet<String>();
        for (Node node : flatten(war).values()) {
            String path = node.getPath().get();
            if (!path.endsWith(".class")) continue;
            if (!path.startsWith("/WEB-INF/classes/")) continue;
            String className = path
                    .substring("/WEB-INF/classes/".length(), path.length() - ".class".length())
                    .replace('/', '.');
            warClassNames.add(className);
            Class<?> cls;
            try { cls = WebComponentRegistry.loadClass(className, cl); }
            catch (Throwable t) { continue; }
            warClassList.add(className);
            registerIfAnnotated(builder, cls, registry, registered);
        }
        builder.restrictToWarClasses(warClassNames);
        builder.handlesTypes(warHandlesTypes(registry, warClassList, cl));

        // 2) web.xml : enregistre les servlets/filters/listeners déclarés
        Node webXml = war.get("/WEB-INF/web.xml");
        if (webXml != null && webXml.getAsset() != null) {
            try (var in = webXml.getAsset().openStream()) {
                WebAppDescriptor desc = WebXmlParser.parse(in);
                registerFromWebXml(builder, desc, cl, registry, registered);
            } catch (Exception e) {
                System.err.println("[VidocqTCK] failed to parse web.xml: " + e);
            }
        }

        // 3) Découverte des ServletContainerInitializer (Servlet 6.1 §4.4) :
        //    - fichier META-INF/services/jakarta.servlet.ServletContainerInitializer dans le WAR
        //    - et (par extension) tout fichier du même nom déployé ailleurs sous /WEB-INF/classes/
        discoverAndRegisterSCIs(war, cl, registry, builder);

        // 4) ResourceProvider exposant les fichiers du WAR au ServletContext (§4.6).
        builder.resourceProvider(new WarResourceProvider(war));

        harness = builder.start();
        harnessesByArchive.put(archive.getName(), harness);
        registriesByArchive.put(archive.getName(), registry);

        System.err.println("[VidocqTCK] deploy archive=" + war.getName()
                + " host=" + config.getHost() + " port=" + harness.port()
                + " servlets=" + registered + " baseUrl=" + harness.baseUrl());

        ProtocolMetaData pmd = new ProtocolMetaData();
        var ctx = new HTTPContext(config.getHost(), harness.port());
        // Le "contextRoot" du servlet Arquillian est le path sous lequel les tests TCK font
        // leurs requêtes ; il doit être égal à notre contextPath pour que HttpRequestClient
        // cible la bonne URL. Pour un WAR root (/), on passe "/" car Arquillian derive
        // getPath() depuis l'URL injectée ; sinon on passe le contextPath du WAR.
        String tckContextRoot = harness.baseUrl().substring(
                ("http://" + config.getHost() + ":" + harness.port()).length());
        if (tckContextRoot.isEmpty()) tckContextRoot = "/";
        ctx.add(new org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet(
                registered.isEmpty() ? "_vidocq" : registered.get(0), tckContextRoot));
        pmd.addContext(ctx);
        return pmd;
    }

    private static void registerFromWebXml(ServletTestHarness.Builder builder,
                                           WebAppDescriptor desc, ClassLoader cl,
                                           WebComponentRegistry registry, List<String> registered) {
        builder.localeEncodingMappings(desc.localeEncodingMappings());
        builder.contextInitParams(desc.contextParams());
        if (desc.displayName() != null) builder.servletContextName(desc.displayName());
        for (var sd : desc.servlets()) if (sd.name() != null) builder.reservedServletName(sd.name());
        for (var fd : desc.filters()) if (fd.name() != null) builder.reservedFilterName(fd.name());
        // §4.4 ServletRegistration.addMapping : un url-pattern déjà mappé par le web.xml
        // est "réservé" — un addMapping dynamique qui tente de le re-mapper doit être
        // refusé et la méthode doit retourner ce pattern dans le set des conflits.
        for (var m : desc.servletMappings()) {
            if (m.urlPattern() != null) builder.reservedUrlPattern(m.urlPattern());
        }
        if (desc.sessionTimeoutMinutes() > 0) {
            builder.sessionTimeoutMinutes(desc.sessionTimeoutMinutes());
        }
        // Version déclarée dans web-app/version → exposée via getEffectiveMajorVersion.
        String v = desc.version();
        int dot = v.indexOf('.');
        try {
            int major = Integer.parseInt(dot < 0 ? v : v.substring(0, dot));
            int minor = dot < 0 ? 0 : Integer.parseInt(v.substring(dot + 1));
            builder.effectiveVersion(major, minor);
        } catch (NumberFormatException ignored) {}
        var instances = new java.util.HashMap<String, jakarta.servlet.Servlet>();
        var servletParams = new java.util.HashMap<String, java.util.Map<String, String>>();
        var asyncSupportedByName = new java.util.HashMap<String, Boolean>();
        for (WebAppDescriptor.ServletDef sd : desc.servlets()) {
            if (sd.className() == null) continue;
            try {
                Class<?> c = WebComponentRegistry.loadClass(sd.className(), cl);
                if (!jakarta.servlet.Servlet.class.isAssignableFrom(c)) continue;
                jakarta.servlet.Servlet s = (jakarta.servlet.Servlet) registry.lookup(c).newInstance();
                instances.put(sd.name(), s);
                servletParams.put(sd.name(),
                        sd.initParams() == null ? java.util.Map.of() : sd.initParams());
                asyncSupportedByName.put(sd.name(), Boolean.TRUE.equals(sd.asyncSupported()));
            } catch (ClassNotFoundException | RuntimeException ignored) {}
        }
        for (WebAppDescriptor.ServletMappingDef m : desc.servletMappings()) {
            jakarta.servlet.Servlet s = instances.get(m.servletName());
            if (s != null) {
                boolean async = asyncSupportedByName.getOrDefault(m.servletName(), Boolean.FALSE);
                builder.servlet(m.urlPattern(), s, m.servletName(),
                        servletParams.getOrDefault(m.servletName(), java.util.Map.of()), async);
                registered.add(m.servletName());
            }
        }
        var filterInstances = new java.util.HashMap<String, jakarta.servlet.Filter>();
        var filterParams = new java.util.HashMap<String, java.util.Map<String, String>>();
        for (WebAppDescriptor.FilterDef fd : desc.filters()) {
            try {
                Class<?> c = WebComponentRegistry.loadClass(fd.className(), cl);
                if (!jakarta.servlet.Filter.class.isAssignableFrom(c)) continue;
                jakarta.servlet.Filter f = (jakarta.servlet.Filter) registry.lookup(c).newInstance();
                filterInstances.put(fd.name(), f);
                filterParams.put(fd.name(),
                        fd.initParams() == null ? java.util.Map.of() : fd.initParams());
            } catch (ClassNotFoundException | RuntimeException ignored) {}
        }
        for (WebAppDescriptor.FilterMappingDef m : desc.filterMappings()) {
            jakarta.servlet.Filter f = filterInstances.get(m.filterName());
            if (f == null) continue;
            var params = filterParams.getOrDefault(m.filterName(), java.util.Map.of());
            var dispatchers = m.dispatcherTypes();
            if (m.urlPattern() != null) {
                builder.filter(m.urlPattern(), f, m.filterName(), params, dispatchers);
            } else if (m.servletName() != null) {
                // Résout le servlet-name en ses url-patterns via servletMappings
                for (String pattern : desc.patternsFor(m.servletName())) {
                    builder.filter(pattern, f, m.filterName(), params, dispatchers);
                }
            }
        }
        for (String lc : desc.listenerClasses()) {
            try {
                Class<?> c = WebComponentRegistry.loadClass(lc, cl);
                if (!java.util.EventListener.class.isAssignableFrom(c)) continue;
                builder.listener((java.util.EventListener) registry.lookup(c).newInstance());
            } catch (ClassNotFoundException | RuntimeException ignored) {}
        }
        // Error pages du web.xml — indispensable pour les TCK qui attendent
        // un dispatch sur <location> en cas d'exception ou de status code.
        for (WebAppDescriptor.ErrorPageDef ep : desc.errorPages()) {
            if (ep.location() == null) continue;
            if (ep.statusCode() != null) {
                builder.errorPage(ep.statusCode(), ep.location());
            } else if (ep.exceptionType() != null) {
                try {
                    Class<?> c = WebComponentRegistry.loadClass(ep.exceptionType(), cl);
                    if (Throwable.class.isAssignableFrom(c)) {
                        @SuppressWarnings("unchecked")
                        Class<? extends Throwable> exc = (Class<? extends Throwable>) c;
                        builder.errorPage(exc, ep.location());
                    }
                } catch (ClassNotFoundException ignored) {}
            }
        }
    }

    /** Scans the WAR for {@code META-INF/services/jakarta.servlet.ServletContainerInitializer}
     *  files and registers referenced SCIs on the builder. */
    private static void discoverAndRegisterSCIs(WebArchive war, ClassLoader cl, WebComponentRegistry registry,
                                                ServletTestHarness.Builder builder) {
        for (Node node : flatten(war).values()) {
            String path = node.getPath().get();
            if (!path.endsWith("/jakarta.servlet.ServletContainerInitializer")) continue;
            if (node.getAsset() == null) continue;
            try (var in = node.getAsset().openStream()) {
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String fqn = line.trim();
                        if (fqn.isEmpty() || fqn.startsWith("#")) continue;
                        try {
                            Class<?> c = WebComponentRegistry.loadClass(fqn, cl);
                            builder.servletContainerInitializer(
                                    (jakarta.servlet.ServletContainerInitializer) registry.lookup(c).newInstance());
                        } catch (Throwable t) {
                            System.err.println("[VidocqTCK] failed to load SCI " + fqn + ": " + t);
                        }
                    }
                }
            } catch (java.io.IOException ignored) {}
        }
    }

    /**
     * Registers {@code cls} when it is an annotated servlet, filter or listener. The class is
     * classified from its bytes first, so that plain WAR classes never enter the registry;
     * metadata and instances then come from {@link WebComponentRegistry#lookup(Class)}.
     */
    private static void registerIfAnnotated(ServletTestHarness.Builder builder, Class<?> cls,
                                            WebComponentRegistry registry, List<String> registered) {
        Optional<WebComponentDescriptor> read;
        try {
            read = ClassFileDescriptorReader.read(cls);
        } catch (IllegalArgumentException e) {
            System.err.println("[VidocqTCK] skipping " + cls.getName() + ": " + e.getMessage());
            return;
        }
        if (read.isEmpty()) return;
        WebComponentDescriptor.Kind kind = read.get().kind();
        boolean servlet = kind == WebComponentDescriptor.Kind.SERVLET && Servlet.class.isAssignableFrom(cls);
        boolean filter = kind == WebComponentDescriptor.Kind.FILTER && Filter.class.isAssignableFrom(cls);
        boolean listener = kind == WebComponentDescriptor.Kind.LISTENER && EventListener.class.isAssignableFrom(cls);
        if (!servlet && !filter && !listener) return;
        WebComponent component = registry.lookup(cls);
        WebComponentDescriptor d = component.descriptor();
        Object instance;
        try {
            instance = component.newInstance();
        } catch (RuntimeException e) {
            return;
        }
        if (servlet) {
            for (String p : d.urlPatterns()) {
                builder.servlet(p, (Servlet) instance, d.name(), d.initParams(), d.asyncSupported());
            }
            registered.add(cls.getSimpleName());
        } else if (filter) {
            Set<DispatcherType> types = d.dispatcherTypes().isEmpty()
                    ? EnumSet.of(DispatcherType.REQUEST) : EnumSet.copyOf(d.dispatcherTypes());
            for (String p : d.urlPatterns()) {
                builder.filter(p, (Filter) instance, d.name(), d.initParams(), types);
            }
        } else {
            builder.listener((EventListener) instance);
        }
    }

    /**
     * {@code @HandlesTypes} resolution over the WAR's own classes (§8.2.4), by the class-bytes scanner
     * ({@link ClassFileHandlesTypesScanner#scanNamed}): supertypes, and annotations on the class, its fields
     * and its methods. The handled types themselves are excluded; {@code null} when nothing matches.
     */
    private static HandlesTypesResolver warHandlesTypes(WebComponentRegistry registry, List<String> warClassNames,
                                                        ClassLoader cl) {
        return new IndexedHandlesTypesResolver(registry, cl,
                () -> ClassFileHandlesTypesScanner.scanNamed(warClassNames, cl));
    }

    private static java.util.Map<org.jboss.shrinkwrap.api.ArchivePath, Node> flatten(WebArchive war) {
        return war.getContent();
    }

    @Override
    public void undeploy(Archive<?> archive) {
        ServletTestHarness h = harnessesByArchive.remove(archive.getName());
        if (h != null) h.close();
        if (harness == h) harness = null;
        WebComponentRegistry registry = registriesByArchive.remove(archive.getName());
        if (registry != null) {
            System.err.println("[VidocqTCK] undeploy archive=" + archive.getName() + " tiers=" + registry.stats());
        }
    }

    @Override public void deploy(Descriptor descriptor) {}
    @Override public void undeploy(Descriptor descriptor) {}

    /** Exposes files from a {@link WebArchive} through
     *  {@link io.vidocq.foy.internal.container.VidocqServletContext.ResourceProvider}.
     *  Materializes assets in a mirrored temporary directory so {@code getResource()} can
     *  return a {@code file:} URL containing the original path (required by TCK
     *  ServletContextTests.getResource, which checks that the URL contains
     *  {@code /WEB-INF/web.xml}). */
    private static final class WarResourceProvider
            implements io.vidocq.foy.internal.container.VidocqServletContext.ResourceProvider {
        private final WebArchive war;
        private final java.nio.file.Path mirror;

        WarResourceProvider(WebArchive war) {
            this.war = war;
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
        }

        @Override public java.util.Set<String> listPaths(String path) {
            if (path == null || !path.startsWith("/")) return null;
            String prefix = path.endsWith("/") ? path : path + "/";
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            for (Node node : war.getContent().values()) {
                String p = node.getPath().get();
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
            if (node == null || node.getAsset() == null) return null;
            return node.getAsset().openStream();
        }

        @Override public java.net.URL toUrl(String path) {
            if (mirror == null || path == null || !path.startsWith("/")) return null;
            java.nio.file.Path p = mirror.resolve(path.substring(1));
            if (!java.nio.file.Files.exists(p)) return null;
            try { return p.toUri().toURL(); } catch (java.net.MalformedURLException e) { return null; }
        }
    }
}
