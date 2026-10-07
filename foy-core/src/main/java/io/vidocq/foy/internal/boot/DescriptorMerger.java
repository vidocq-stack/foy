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
import io.vidocq.foy.internal.webxml.WebAppDescriptor;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;

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
 */
public final class DescriptorMerger {

    private DescriptorMerger() {}

    /** Annotation-derived declarations (from CDI discovery now, generated components in Phase 2). */
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
    }

    /**
     * Fills {@code target} from web.xml + annotations following Servlet 6.1 §8.2.3.
     *
     * @throws ServletException when a descriptor class cannot be loaded or has the wrong type
     */
    public static void merge(WebAppDescriptor webXml, AnnotatedComponents annotated,
                             ComponentFactory factory, WebAppModel.Builder target)
            throws ServletException {
        AnnotatedComponents ann = webXml.metadataComplete() ? AnnotatedComponents.none() : annotated;

        webXml.contextParams().forEach(target::contextParam);
        if (webXml.displayName() != null) target.displayName(webXml.displayName());
        target.sessionTimeoutMinutes(webXml.sessionTimeoutMinutes());
        target.localeEncodingMappings(webXml.localeEncodingMappings());
        target.errorPages(errorPages(webXml, factory));

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
            Class<? extends Servlet> type = load(factory, def.className(), Servlet.class, "servlet", def.name());
            List<String> xmlPatterns = webXml.patternsFor(def.name());
            List<String> patterns = !xmlPatterns.isEmpty() || a == null ? xmlPatterns : a.urlPatterns();
            Map<String, String> params = new LinkedHashMap<>();
            if (a != null) params.putAll(a.initParams());
            params.putAll(def.initParams());
            int load = def.loadOnStartup() != Integer.MIN_VALUE || a == null
                    ? def.loadOnStartup() : a.loadOnStartup();
            boolean async = def.asyncSupported() || (a != null && a.asyncSupported());
            target.servlet(new ServletDecl(def.name(), type, supplier(factory, type), patterns,
                    params, load, async));
            done.add(def.name());
        }
        for (ServletDecl a : ann.servlets()) {
            if (done.contains(a.name())) continue;
            List<String> xmlPatterns = webXml.patternsFor(a.name());
            target.servlet(xmlPatterns.isEmpty() ? a : new ServletDecl(a.name(), a.type(), a.factory(),
                    xmlPatterns, a.initParams(), a.loadOnStartup(), a.asyncSupported()));
        }
    }

    private static void mergeFilters(WebAppDescriptor webXml, AnnotatedComponents ann,
                                     ComponentFactory factory, WebAppModel.Builder target)
            throws ServletException {
        Map<String, FilterDecl> annotatedByName = new LinkedHashMap<>();
        for (FilterDecl f : ann.filters()) annotatedByName.put(f.name(), f);

        Set<String> done = new HashSet<>();
        for (var def : webXml.filters()) {
            FilterDecl a = annotatedByName.get(def.name());
            Class<? extends Filter> type = load(factory, def.className(), Filter.class, "filter", def.name());
            Map<String, String> params = new LinkedHashMap<>();
            if (a != null) params.putAll(a.initParams());
            params.putAll(def.initParams());
            boolean async = def.asyncSupported() || (a != null && a.asyncSupported());
            target.filter(new FilterDecl(def.name(), type, supplier(factory, type), params, async));
            done.add(def.name());
        }
        for (FilterDecl a : ann.filters()) {
            if (!done.contains(a.name())) target.filter(a);
        }

        // web.xml mappings come first and replace the annotation mappings of the same filter.
        Set<String> mappedInXml = new HashSet<>();
        for (var m : webXml.filterMappings()) {
            target.filterMapping(new FilterMappingDecl(m.filterName(), m.urlPattern(),
                    m.servletName(), m.dispatcherTypes()));
            mappedInXml.add(m.filterName());
        }
        for (FilterMappingDecl m : ann.filterMappings()) {
            if (!mappedInXml.contains(m.filterName())) target.filterMapping(m);
        }
    }

    private static void mergeListeners(WebAppDescriptor webXml, AnnotatedComponents ann,
                                       ComponentFactory factory, WebAppModel.Builder target)
            throws ServletException {
        Set<Class<?>> seen = new HashSet<>();
        for (String className : webXml.listenerClasses()) {
            Class<? extends EventListener> type = load(factory, className, EventListener.class,
                    "listener", className);
            if (seen.add(type)) target.listener(new ListenerDecl(type, supplier(factory, type)));
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

    private static <T> Supplier<T> supplier(ComponentFactory factory, Class<? extends T> type) {
        return () -> {
            try {
                return factory.newInstance(type);
            } catch (ServletException e) {
                throw new IllegalStateException("cannot instantiate " + type.getName(), e);
            }
        };
    }
}
