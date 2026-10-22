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
import io.vidocq.foy.spi.gen.WebComponentDescriptor;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;

import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
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
    private static final String SERVLET = "servlet";

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
         * The annotated servlets, filters and listeners among {@code classes}, in order, from their
         * static descriptors: names, URL patterns, init params, load-on-startup, async support,
         * security, multipart, filter dispatcher types and servlet names. A class whose descriptor
         * is of another kind, or that does not implement the interface of its kind, is left out. A
         * filter without dispatcher types maps {@code REQUEST}.
         *
         * @param descriptorOf the static metadata of a class (never runtime annotation reflection)
         * @param instances    the instance supplier of a component class, given the class and its
         *                     component interface ({@code Servlet}, {@code Filter} or
         *                     {@code EventListener}): factory, CDI reference, ...; its instances are
         *                     cast to that interface
         * @throws IllegalArgumentException when {@code descriptorOf} reports a class misusing the
         *         Servlet annotations; callers fail the deployment
         */
        public static AnnotatedComponents fromDescriptors(Iterable<Class<?>> classes,
                                                          Function<Class<?>, WebComponentDescriptor> descriptorOf,
                                                          BiFunction<Class<?>, Class<?>, ? extends Supplier<?>> instances) {
            List<ServletDecl> servlets = new ArrayList<>();
            List<FilterDecl> filters = new ArrayList<>();
            List<FilterMappingDecl> filterMappings = new ArrayList<>();
            List<ListenerDecl> listeners = new ArrayList<>();
            for (Class<?> cls : classes) {
                WebComponentDescriptor d = descriptorOf.apply(cls);
                switch (d.kind()) {
                    case SERVLET -> addServlet(cls, d, instances, servlets);
                    case FILTER -> addFilter(cls, d, instances, filters, filterMappings);
                    case LISTENER -> addListener(cls, instances, listeners);
                    default -> { /* not a web component */ }
                }
            }
            return new AnnotatedComponents(servlets, filters, filterMappings, listeners);
        }

        /** Adds {@code cls}, described by {@code d}, to {@code servlets} when it is a {@link Servlet}. */
        private static void addServlet(Class<?> cls, WebComponentDescriptor d,
                                       BiFunction<Class<?>, Class<?>, ? extends Supplier<?>> instances,
                                       List<ServletDecl> servlets) {
            if (!Servlet.class.isAssignableFrom(cls)) return;
            servlets.add(new ServletDecl(d.name(), cls.asSubclass(Servlet.class),
                    cast(instances.apply(cls, Servlet.class), Servlet.class), d.urlPatterns(), d.initParams(),
                    d.loadOnStartup(), d.asyncSupported(), d.servletSecurity(), d.multipartConfig(),
                    true));
        }

        /**
         * Adds {@code cls}, described by {@code d}, to {@code filters} with its mappings to
         * {@code filterMappings} when it is a {@link Filter}.
         */
        private static void addFilter(Class<?> cls, WebComponentDescriptor d,
                                      BiFunction<Class<?>, Class<?>, ? extends Supplier<?>> instances,
                                      List<FilterDecl> filters, List<FilterMappingDecl> filterMappings) {
            if (!Filter.class.isAssignableFrom(cls)) return;
            filters.add(new FilterDecl(d.name(), cls.asSubclass(Filter.class),
                    cast(instances.apply(cls, Filter.class), Filter.class), d.initParams(), d.asyncSupported()));
            Set<DispatcherType> types = d.dispatcherTypes().isEmpty()
                    ? EnumSet.of(DispatcherType.REQUEST) : EnumSet.copyOf(d.dispatcherTypes());
            for (String pattern : d.urlPatterns()) {
                filterMappings.add(new FilterMappingDecl(d.name(), pattern, null, types));
            }
            for (String servletName : d.servletNames()) {
                filterMappings.add(new FilterMappingDecl(d.name(), null, servletName, types));
            }
        }

        /** Adds {@code cls} to {@code listeners} when it is an {@link EventListener}. */
        private static void addListener(Class<?> cls,
                                        BiFunction<Class<?>, Class<?>, ? extends Supplier<?>> instances,
                                        List<ListenerDecl> listeners) {
            if (!EventListener.class.isAssignableFrom(cls)) return;
            listeners.add(new ListenerDecl(cls.asSubclass(EventListener.class),
                    cast(instances.apply(cls, EventListener.class), EventListener.class)));
        }

        private static <T> Supplier<T> cast(Supplier<?> supplier, Class<T> type) {
            return () -> type.cast(supplier.get());
        }

        /**
         * Drops the components whose class comes from one of {@code jars}: its
         * {@code ProtectionDomain} code source location, normalised by {@link Fragment#sourceKey},
         * equals a normalised jar. A class without code source is never dropped. Filter mappings
         * of a dropped filter are dropped with it.
         */
        public AnnotatedComponents excludingSources(Collection<URL> jars) {
            return excludingSources(jars, AnnotatedComponents::codeSource);
        }

        /**
         * Drops the components whose class comes from one of {@code jars}, the source of a class
         * being {@code sourceOf.apply(type)}: both sides are normalised by {@link Fragment#sourceKey}
         * before comparing. A class whose source is {@code null} is never dropped. Filter mappings
         * of a dropped filter are dropped with it.
         *
         * <p>For callers whose classes are not loaded from the jar they belong to, such as a
         * harness loading a war's classes from a shared class loader and attributing each one to
         * a synthetic {@code WEB-INF/lib/<jar>} URL.</p>
         */
        public AnnotatedComponents excludingSources(Collection<URL> jars, Function<Class<?>, URL> sourceOf) {
            if (jars.isEmpty()) return this;
            Set<String> keys = new HashSet<>();
            for (URL u : jars) keys.add(Fragment.sourceKey(u));
            Predicate<Class<?>> dropped = type -> {
                URL source = sourceOf.apply(type);
                return source != null && keys.contains(Fragment.sourceKey(source));
            };
            var keptFilters = new ArrayList<FilterDecl>();
            Set<String> droppedFilters = new HashSet<>();
            for (FilterDecl f : filters) {
                if (dropped.test(f.type())) droppedFilters.add(f.name());
                else keptFilters.add(f);
            }
            return new AnnotatedComponents(
                    servlets.stream().filter(s -> !dropped.test(s.type())).toList(),
                    keptFilters,
                    filterMappings.stream().filter(m -> !droppedFilters.contains(m.filterName())).toList(),
                    listeners.stream().filter(l -> !dropped.test(l.type())).toList());
        }

        /** The {@code ProtectionDomain} code source location of {@code type}, {@code null} without one. */
        private static URL codeSource(Class<?> type) {
            CodeSource cs = type.getProtectionDomain().getCodeSource();
            return cs == null ? null : cs.getLocation();
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
        mergeMerged(effective, fragments, annotated, AnnotatedComponents::codeSource, factory, target);
    }

    /**
     * As {@link #mergeMerged(WebAppDescriptor, List, AnnotatedComponents, ComponentFactory,
     * WebAppModel.Builder)}, the jar of an annotated class being {@code sourceOf.apply(type)}
     * instead of its code source (see {@link AnnotatedComponents#excludingSources(Collection, Function)}):
     * the annotations of the classes of {@code metadata-complete} fragments' jars are dropped by
     * that mapping.
     */
    public static void mergeMerged(WebAppDescriptor effective, List<Fragment> fragments,
                                   AnnotatedComponents annotated, Function<Class<?>, URL> sourceOf,
                                   ComponentFactory factory, WebAppModel.Builder target)
            throws ServletException {
        List<URL> complete = new ArrayList<>();
        for (Fragment f : fragments) if (f.descriptor().metadataComplete()) complete.add(f.jar());
        mergeEffective(effective, annotated.excludingSources(complete, sourceOf), factory, target);
    }

    private static void mergeEffective(WebAppDescriptor webXml, AnnotatedComponents annotated,
                                       ComponentFactory factory, WebAppModel.Builder target)
            throws ServletException {
        AnnotatedComponents ann = webXml.metadataComplete() ? AnnotatedComponents.none() : annotated;

        effectiveVersion(webXml.version(), target);
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
        target.jspConfigDescriptor(webXml.jspConfigDescriptor());

        // Declared roles (§13.3, §13.4): <security-role> and the merged servlets' @DeclareRoles.
        Set<String> roles = new LinkedHashSet<>(webXml.securityRoles());
        roles.addAll(mergeServlets(webXml, ann, factory, target));
        target.securityRoles(List.copyOf(roles));

        mergeFilters(webXml, ann, factory, target);
        mergeListeners(webXml, ann, factory, target);
    }

    /**
     * web.xml's {@code version} as the effective Servlet version ({@code ServletContext
     * .getEffectiveMajorVersion}/{@code getEffectiveMinorVersion}). Fragments never change it
     * (FragmentMerger keeps web.xml's); without web.xml, without a version, or with a malformed
     * one, the builder keeps the container version.
     */
    private static void effectiveVersion(String version, WebAppModel.Builder target) {
        if (version == null) return;
        String v = version.trim();
        int dot = v.indexOf('.');
        try {
            int major = Integer.parseInt(dot < 0 ? v : v.substring(0, dot));
            int minor = dot < 0 ? 0 : Integer.parseInt(v.substring(dot + 1));
            if (major < 0 || minor < 0) return;
            target.effectiveVersion(major, minor);
        } catch (NumberFormatException _) {
            // malformed version: the container version stays
        }
    }

    /** Merges the servlets into {@code target}; returns the roles their {@code @DeclareRoles} declare. */
    private static Set<String> mergeServlets(WebAppDescriptor webXml, AnnotatedComponents ann,
                                             ComponentFactory factory, WebAppModel.Builder target)
            throws ServletException {
        Set<String> declared = new LinkedHashSet<>();
        Map<String, ServletDecl> annotatedByName = new LinkedHashMap<>();
        for (ServletDecl s : ann.servlets()) annotatedByName.put(s.name(), s);

        Set<String> done = new HashSet<>();
        for (var def : webXml.servlets()) {
            // The web.xml declaration overrides the annotated one of the same name, even when skipped.
            done.add(def.name());
            mergeXmlServlet(webXml, def, annotatedByName.get(def.name()), factory, target, declared);
        }
        for (ServletDecl a : ann.servlets()) {
            if (!done.contains(a.name())) mergeAnnotatedServlet(webXml, a, factory, target, declared);
        }
        return declared;
    }

    /**
     * Merges the web.xml servlet {@code def} with the annotated servlet of the same name
     * ({@code a}, {@code null} without one) into {@code target}, adding the roles the class's
     * {@code @DeclareRoles} declares to {@code declared}. A servlet whose class has the wrong type
     * is skipped.
     */
    private static void mergeXmlServlet(WebAppDescriptor webXml, WebAppDescriptor.ServletDef def, ServletDecl a,
                                        ComponentFactory factory, WebAppModel.Builder target,
                                        Set<String> declared) throws ServletException {
        if (def.className() == null && def.jspFile() != null) {
            target.servlet(new ServletDecl(def.name(), null, null, webXml.patternsFor(def.name()),
                    def.initParams(), def.loadOnStartup(), Boolean.TRUE.equals(def.asyncSupported()),
                    null, null, def.enabled(), def.jspFile()));
            return;
        }
        Class<? extends Servlet> type = loadComponent(factory, def.className(), Servlet.class, SERVLET,
                def.name());
        if (type == null) return;
        List<String> xmlPatterns = webXml.patternsFor(def.name());
        List<String> patterns = !xmlPatterns.isEmpty() || a == null ? xmlPatterns : a.urlPatterns();
        Map<String, String> params = new LinkedHashMap<>();
        if (a != null) params.putAll(a.initParams());
        params.putAll(def.initParams());
        int load = def.loadOnStartup() != Integer.MIN_VALUE || a == null
                ? def.loadOnStartup() : a.loadOnStartup();
        boolean async = asyncSupported(def.asyncSupported(), a != null && a.asyncSupported());
        // §13.4.1: @ServletSecurity applies to the class, whatever declared the servlet
        // (unless metadata-complete turns annotation processing off, §8.1).
        var classDescriptor = webXml.metadataComplete() ? null
                : descriptor(factory, type, SERVLET, def.name());
        mergeServletRoles(def, classDescriptor, target, declared);
        var security = classDescriptor == null ? null : classDescriptor.servletSecurity();
        target.servlet(new ServletDecl(def.name(), type, supplier(factory, type), patterns,
                params, load, async, security, multipartConfig(def, classDescriptor), def.enabled()));
    }

    /**
     * The role settings of the web.xml servlet {@code def} into {@code target}: its
     * {@code security-role-ref}s and its run-as role, the roles its class's {@code @DeclareRoles}
     * declares into {@code declared}; {@code classDescriptor} is {@code null} under
     * metadata-complete.
     */
    private static void mergeServletRoles(WebAppDescriptor.ServletDef def, WebComponentDescriptor classDescriptor,
                                          WebAppModel.Builder target, Set<String> declared) {
        if (!def.securityRoleRefs().isEmpty()) target.securityRoleRefs(def.name(), def.securityRoleRefs());
        if (classDescriptor != null) declared.addAll(classDescriptor.declaredRoles());
        String runAs = runAs(def, classDescriptor);
        if (runAs != null) target.runAsRole(def.name(), runAs);
    }

    /** A descriptor {@code <run-as>} overrides the class's {@code @RunAs}; {@code null} without either. */
    private static String runAs(WebAppDescriptor.ServletDef def, WebComponentDescriptor classDescriptor) {
        if (def.runAs() != null) return def.runAs();
        return classDescriptor == null ? null : classDescriptor.runAs();
    }

    /** §8.2.3: a descriptor {@code <multipart-config>} overrides the class's {@code @MultipartConfig}. */
    private static MultipartConfigElement multipartConfig(WebAppDescriptor.ServletDef def,
                                                          WebComponentDescriptor classDescriptor) {
        if (def.multipartConfig() != null) return multipartElement(def.multipartConfig());
        return classDescriptor == null ? null : classDescriptor.multipartConfig();
    }

    /** §8.2.3: a web.xml value overrides the annotation; absent ({@code null}) means "take the annotation". */
    private static boolean asyncSupported(Boolean xml, boolean annotated) {
        return xml != null ? xml : annotated;
    }

    /** Merges the annotated servlet {@code a}, which web.xml does not declare, into {@code target}. */
    private static void mergeAnnotatedServlet(WebAppDescriptor webXml, ServletDecl a, ComponentFactory factory,
                                              WebAppModel.Builder target, Set<String> declared)
            throws ServletException {
        // ann is empty under metadata-complete, so these are annotated servlets being processed.
        var classDescriptor = descriptor(factory, a.type(), SERVLET, a.name());
        declared.addAll(classDescriptor.declaredRoles());
        if (classDescriptor.runAs() != null) target.runAsRole(a.name(), classDescriptor.runAs());
        List<String> xmlPatterns = webXml.patternsFor(a.name());
        target.servlet(xmlPatterns.isEmpty() ? a : new ServletDecl(a.name(), a.type(), a.factory(),
                xmlPatterns, a.initParams(), a.loadOnStartup(), a.asyncSupported(), a.servletSecurity(),
                a.multipartConfig(), a.enabled()));
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
            boolean async = asyncSupported(def.asyncSupported(), a != null && a.asyncSupported());
            target.filter(new FilterDecl(def.name(), type, supplier(factory, type), params, async));
        }
        for (FilterDecl a : ann.filters()) {
            if (!done.contains(a.name())) target.filter(a);
        }
        mergeFilterMappings(webXml, ann, skipped, target);
    }

    /**
     * Merges the filter mappings into {@code target}, but those of the {@code skipped} filters:
     * web.xml mappings come first and replace the annotation mappings of the same filter.
     */
    private static void mergeFilterMappings(WebAppDescriptor webXml, AnnotatedComponents ann, Set<String> skipped,
                                            WebAppModel.Builder target) {
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
