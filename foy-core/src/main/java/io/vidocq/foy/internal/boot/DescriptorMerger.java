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

import io.vidocq.foy.internal.boot.WebAppModel.FilterDecl;
import io.vidocq.foy.internal.boot.WebAppModel.FilterMappingDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ListenerDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.error.ErrorPageRegistry;
import io.vidocq.foy.internal.webxml.Fragment;
import io.vidocq.foy.internal.webxml.FragmentMerger;
import io.vidocq.foy.internal.webxml.WebAppDescriptor;
import jakarta.servlet.Filter;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;

import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Merges {@code web.xml} and annotation-derived declarations following Servlet 6.1 §8.2.3.
 *
 * <p>{@code web.xml} wins on conflicts; with {@code metadata-complete="true"} the annotated
 * components are ignored entirely. The {@code web.xml} descriptor only carries
 * {@code async-supported} as a plain boolean, so an annotated {@code asyncSupported = true}
 * is kept when the descriptor does not say {@code true}.</p>
 *
 * <p>A {@code web.xml} servlet, filter or listener whose class does not have the expected type
 * is skipped with one WARNING (with its mappings); the rest of the application deploys. A
 * missing class or a misused Servlet annotation fails the merge with a {@link ServletException}.</p>
 */
public final class DescriptorMerger {

    private static final System.Logger LOG = System.getLogger(DescriptorMerger.class.getName());

    private DescriptorMerger() {}

    /** Annotation-derived declarations; their names default to the binary class name (§8.1.1). */
    public record AnnotatedComponents(List<ServletDecl> servlets, List<FilterDecl> filters,
                                      List<FilterMappingDecl> filterMappings,
                                      List<ListenerDecl> listeners) {
        public AnnotatedComponents {
            servlets = List.copyOf(servlets);
            filters = List.copyOf(filters);
            filterMappings = List.copyOf(filterMappings);
            listeners = List.copyOf(listeners);
        }

        public static AnnotatedComponents none() {
            return new AnnotatedComponents(List.of(), List.of(), List.of(), List.of());
        }

        /**
         * Drops the components whose class comes from one of {@code jars}: its
         * {@code ProtectionDomain} code source location, normalised by {@link Fragment#sourceKey},
         * equals a normalised jar. A class without code source is never dropped. Filter mappings
         * of a dropped filter are dropped with it.
         */
        public AnnotatedComponents excludingSources(Set<URL> jars) {
            if (jars.isEmpty()) return this;
            Set<String> keys = new HashSet<>();
            for (URL u : jars) keys.add(Fragment.sourceKey(u));
            var keptFilters = new ArrayList<FilterDecl>();
            Set<String> droppedFilters = new HashSet<>();
            for (FilterDecl f : filters) {
                if (from(f.type(), keys)) droppedFilters.add(f.name());
                else keptFilters.add(f);
            }
            return new AnnotatedComponents(
                    servlets.stream().filter(s -> !from(s.type(), keys)).toList(),
                    keptFilters,
                    filterMappings.stream().filter(m -> !droppedFilters.contains(m.filterName())).toList(),
                    listeners.stream().filter(l -> !from(l.type(), keys)).toList());
        }

        private static boolean from(Class<?> type, Set<String> keys) {
            CodeSource cs = type.getProtectionDomain().getCodeSource();
            URL location = cs == null ? null : cs.getLocation();
            return location != null && keys.contains(Fragment.sourceKey(location));
        }
    }

    /**
     * Fills {@code target} from web.xml + annotations following Servlet 6.1 §8.2.3, without web
     * fragments.
     *
     * @throws ServletException when a descriptor class cannot be loaded, or misuses the Servlet
     *         annotations, or when an error-page exception type is not a {@link Throwable}
     */
    public static void merge(WebAppDescriptor webXml, AnnotatedComponents annotated,
                             ComponentFactory factory, WebAppModel.Builder target)
            throws ServletException {
        merge(webXml, List.of(), annotated, factory, target);
    }

    /**
     * Fills {@code target} from web.xml, the web fragments and the annotations (Servlet 6.1
     * §8.2.3): {@link FragmentMerger#merge} first, then the annotations of the classes of
     * {@code metadata-complete} fragments' jars are dropped, then the effective descriptor is
     * merged with the remaining annotations.
     *
     * <p>{@code fragments} are the ordered fragments ({@code FragmentOrderer.order}); the
     * annotations of jars excluded by an absolute ordering must already be removed from
     * {@code annotated} by the caller ({@link AnnotatedComponents#excludingSources}), since those
     * jars are not in {@code fragments}.</p>
     *
     * @throws ServletException on a fragment conflict, or as {@link #merge(WebAppDescriptor,
     *         AnnotatedComponents, ComponentFactory, WebAppModel.Builder)}
     */
    public static void merge(WebAppDescriptor webXml, List<Fragment> fragments,
                             AnnotatedComponents annotated, ComponentFactory factory,
                             WebAppModel.Builder target) throws ServletException {
        mergeMerged(FragmentMerger.merge(webXml, fragments), fragments, annotated, factory, target);
    }

    /**
     * As {@link #merge(WebAppDescriptor, List, AnnotatedComponents, ComponentFactory,
     * WebAppModel.Builder)} for a caller that already holds {@code effective}, the result of
     * {@code FragmentMerger.merge(webXml, fragments)}, so the fragments are not merged twice.
     */
    public static void mergeMerged(WebAppDescriptor effective, List<Fragment> fragments,
                                   AnnotatedComponents annotated, ComponentFactory factory,
                                   WebAppModel.Builder target) throws ServletException {
        Set<URL> complete = new HashSet<>();
        for (Fragment f : fragments) if (f.descriptor().metadataComplete()) complete.add(f.jar());
        mergeEffective(effective, annotated.excludingSources(complete), factory, target);
    }

    private static void mergeEffective(WebAppDescriptor webXml, AnnotatedComponents annotated,
                                       ComponentFactory factory, WebAppModel.Builder target)
            throws ServletException {
        AnnotatedComponents ann = webXml.metadataComplete() ? AnnotatedComponents.none() : annotated;

        webXml.contextParams().forEach(target::contextParam);
        if (webXml.displayName() != null) target.displayName(webXml.displayName());
        target.sessionTimeoutMinutes(webXml.sessionTimeoutMinutes());
        target.localeEncodingMappings(webXml.localeEncodingMappings());
        target.errorPages(errorPages(webXml, factory));
        target.welcomeFiles(webXml.welcomeFiles());
        target.mimeMappings(webXml.mimeMappings());
        target.requestCharacterEncoding(webXml.requestCharacterEncoding());
        target.responseCharacterEncoding(webXml.responseCharacterEncoding());
        target.defaultContextPath(webXml.defaultContextPath());
        target.denyUncoveredHttpMethods(webXml.denyUncoveredHttpMethods());
        target.cookieConfig(webXml.cookieConfig());
        target.trackingModes(webXml.trackingModes());
        target.securityConstraints(webXml.securityConstraints());
        target.loginConfig(webXml.loginConfig());
        target.securityRoles(webXml.securityRoles());

        mergeServlets(webXml, ann, factory, target);
        mergeFilters(webXml, ann, factory, target);
        mergeListeners(webXml, ann, factory, target);
    }

    private static void mergeServlets(WebAppDescriptor webXml, AnnotatedComponents ann,
                                      ComponentFactory factory, WebAppModel.Builder target)
            throws ServletException {
        Map<String, ServletDecl> annotatedByName = new LinkedHashMap<>();
        for (ServletDecl s : ann.servlets()) annotatedByName.put(s.name(), s);

        Set<String> done = new HashSet<>();
        for (var def : webXml.servlets()) {
            ServletDecl a = annotatedByName.get(def.name());
            // The web.xml declaration overrides the annotated one of the same name, even when skipped.
            done.add(def.name());
            Class<? extends Servlet> type = loadComponent(factory, def.className(), Servlet.class, "servlet",
                    def.name());
            if (type == null) continue;
            List<String> xmlPatterns = webXml.patternsFor(def.name());
            List<String> patterns = !xmlPatterns.isEmpty() || a == null ? xmlPatterns : a.urlPatterns();
            Map<String, String> params = new LinkedHashMap<>();
            if (a != null) params.putAll(a.initParams());
            params.putAll(def.initParams());
            int load = def.loadOnStartup() != Integer.MIN_VALUE || a == null
                    ? def.loadOnStartup() : a.loadOnStartup();
            // §8.2.3: a web.xml value overrides the annotation; absent means "take the annotation".
            boolean async = def.asyncSupported() != null ? def.asyncSupported()
                    : (a != null && a.asyncSupported());
            // §13.4.1: @ServletSecurity applies to the class, whatever declared the servlet
            // (unless metadata-complete turns annotation processing off, §8.1).
            var classDescriptor = webXml.metadataComplete() ? null
                    : descriptor(factory, type, "servlet", def.name());
            var security = classDescriptor == null ? null : classDescriptor.servletSecurity();
            // §8.2.3: a descriptor <multipart-config> overrides the class's @MultipartConfig.
            var multipart = def.multipartConfig() != null ? multipartElement(def.multipartConfig())
                    : classDescriptor == null ? null : classDescriptor.multipartConfig();
            target.servlet(new ServletDecl(def.name(), type, supplier(factory, type), patterns,
                    params, load, async, security, multipart, def.enabled()));
        }
        for (ServletDecl a : ann.servlets()) {
            if (done.contains(a.name())) continue;
            List<String> xmlPatterns = webXml.patternsFor(a.name());
            target.servlet(xmlPatterns.isEmpty() ? a : new ServletDecl(a.name(), a.type(), a.factory(),
                    xmlPatterns, a.initParams(), a.loadOnStartup(), a.asyncSupported(), a.servletSecurity(),
                    a.multipartConfig(), a.enabled()));
        }
    }

    private static MultipartConfigElement multipartElement(WebAppDescriptor.MultipartConfigDef d) {
        return new MultipartConfigElement(d.location() == null ? "" : d.location(), d.maxFileSize(),
                d.maxRequestSize(), d.fileSizeThreshold());
    }

    private static void mergeFilters(WebAppDescriptor webXml, AnnotatedComponents ann,
                                     ComponentFactory factory, WebAppModel.Builder target)
            throws ServletException {
        Map<String, FilterDecl> annotatedByName = new LinkedHashMap<>();
        for (FilterDecl f : ann.filters()) annotatedByName.put(f.name(), f);

        Set<String> done = new HashSet<>();
        Set<String> skipped = new HashSet<>();
        for (var def : webXml.filters()) {
            FilterDecl a = annotatedByName.get(def.name());
            done.add(def.name());
            Class<? extends Filter> type = loadComponent(factory, def.className(), Filter.class, "filter",
                    def.name());
            if (type == null) {
                skipped.add(def.name());
                continue;
            }
            Map<String, String> params = new LinkedHashMap<>();
            if (a != null) params.putAll(a.initParams());
            params.putAll(def.initParams());
            // §8.2.3: a web.xml value overrides the annotation; absent means "take the annotation".
            boolean async = def.asyncSupported() != null ? def.asyncSupported()
                    : (a != null && a.asyncSupported());
            target.filter(new FilterDecl(def.name(), type, supplier(factory, type), params, async));
        }
        for (FilterDecl a : ann.filters()) {
            if (!done.contains(a.name())) target.filter(a);
        }

        // web.xml mappings come first and replace the annotation mappings of the same filter.
        Set<String> mappedInXml = new HashSet<>();
        for (var m : webXml.filterMappings()) {
            mappedInXml.add(m.filterName());
            if (skipped.contains(m.filterName())) continue;
            target.filterMapping(new FilterMappingDecl(m.filterName(), m.urlPattern(),
                    m.servletName(), m.dispatcherTypes()));
        }
        for (FilterMappingDecl m : ann.filterMappings()) {
            if (!mappedInXml.contains(m.filterName()) && !skipped.contains(m.filterName())) {
                target.filterMapping(m);
            }
        }
    }

    private static void mergeListeners(WebAppDescriptor webXml, AnnotatedComponents ann,
                                       ComponentFactory factory, WebAppModel.Builder target)
            throws ServletException {
        Set<Class<?>> seen = new HashSet<>();
        for (String className : webXml.listenerClasses()) {
            Class<? extends EventListener> type = loadComponent(factory, className, EventListener.class,
                    "listener", className);
            if (type != null && seen.add(type)) target.listener(new ListenerDecl(type, supplier(factory, type)));
        }
        for (ListenerDecl l : ann.listeners()) {
            if (seen.add(l.type())) target.listener(l);
        }
    }

    private static ErrorPageRegistry errorPages(WebAppDescriptor webXml, ComponentFactory factory)
            throws ServletException {
        var registry = new ErrorPageRegistry();
        for (var page : webXml.errorPages()) {
            if (page.statusCode() != null) {
                registry.register(page.statusCode(), page.location());
            } else if (page.exceptionType() != null) {
                registry.register(load(factory, page.exceptionType(), Throwable.class,
                        "error-page exception", page.exceptionType()), page.location());
            }
        }
        return registry;
    }

    private static <T> Class<? extends T> load(ComponentFactory factory, String className,
                                               Class<T> expected, String kind, String name)
            throws ServletException {
        if (className == null) {
            throw new ServletException("web.xml " + kind + " '" + name + "' declares no class");
        }
        try {
            return factory.load(className).asSubclass(expected);
        } catch (ClassNotFoundException | ClassCastException e) {
            throw new ServletException("web.xml " + kind + " '" + name + "': cannot use class "
                    + className, e);
        }
    }

    /**
     * Like {@link #load}, but a class of the wrong type is skipped: one WARNING naming the
     * component and the class, then {@code null}.
     */
    private static <T> Class<? extends T> loadComponent(ComponentFactory factory, String className,
                                                        Class<T> expected, String kind, String name)
            throws ServletException {
        if (className == null) {
            throw new ServletException("web.xml " + kind + " '" + name + "' declares no class");
        }
        Class<?> loaded;
        try {
            loaded = factory.load(className);
        } catch (ClassNotFoundException e) {
            throw new ServletException("web.xml " + kind + " '" + name + "': cannot use class "
                    + className, e);
        }
        if (!expected.isAssignableFrom(loaded)) {
            LOG.log(System.Logger.Level.WARNING, "web.xml " + kind + " '" + name + "' skipped: class "
                    + className + " does not implement " + expected.getName());
            return null;
        }
        return loaded.asSubclass(expected);
    }

    /** The class's static metadata; annotation misuse becomes a {@link ServletException}. */
    private static io.vidocq.foy.spi.gen.WebComponentDescriptor descriptor(ComponentFactory factory, Class<?> type,
                                                                           String kind, String name)
            throws ServletException {
        try {
            return factory.descriptor(type);
        } catch (IllegalArgumentException e) {
            throw new ServletException("web.xml " + kind + " '" + name + "': " + e.getMessage(), e);
        }
    }

    private static <T> Supplier<T> supplier(ComponentFactory factory, Class<? extends T> type) {
        return () -> {
            try {
                return factory.newInstance(type);
            } catch (ServletException e) {
                // The factory message already reads "cannot instantiate <class>: <reason>".
                throw new IllegalStateException(e.getMessage(), e);
            }
        };
    }
}
