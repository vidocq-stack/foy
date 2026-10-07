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

import io.vidocq.foy.internal.boot.DescriptorMerger.AnnotatedComponents;
import io.vidocq.foy.internal.boot.WebAppModel.FilterDecl;
import io.vidocq.foy.internal.boot.WebAppModel.FilterMappingDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ListenerDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebInitParam;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.annotation.WebServlet;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Scans the {@link BeanManager} (Vauban) for CDI beans annotated {@code @WebServlet},
 * {@code @WebFilter} or {@code @WebListener} and returns them as {@link AnnotatedComponents},
 * to be merged with {@code web.xml} by {@link DescriptorMerger}.
 *
 * <p>Annotation values (URL patterns, init params, load-on-startup, async support, filter
 * servlet names and dispatcher types) are read through reflection on the bean class; Phase 2
 * replaces this with the generated index. Each component factory resolves the CDI reference
 * lazily; the deployer calls it once, so servlets and filters are singletons (Servlet 6.1 §2.2).</p>
 */
public final class WebAppDiscovery {

    private static final AnnotationLiteral<Any> ANY = new AnnotationLiteral<Any>() {};

    public static AnnotatedComponents discover(BeanManager bm) {
        List<ServletDecl> servlets = new ArrayList<>();
        List<FilterDecl> filters = new ArrayList<>();
        List<FilterMappingDecl> filterMappings = new ArrayList<>();
        List<ListenerDecl> listeners = new ArrayList<>();

        for (Bean<?> bean : bm.getBeans(Servlet.class, ANY)) {
            Class<?> cls = bean.getBeanClass();
            WebServlet ann = cls.getAnnotation(WebServlet.class);
            if (ann == null) continue;
            String name = ann.name().isEmpty() ? cls.getSimpleName() : ann.name();
            servlets.add(new ServletDecl(name, cls.asSubclass(Servlet.class),
                    reference(bm, bean, Servlet.class),
                    List.of(effectivePatterns(ann.urlPatterns(), ann.value())),
                    initParams(ann.initParams()), ann.loadOnStartup(), ann.asyncSupported()));
        }

        for (Bean<?> bean : bm.getBeans(Filter.class, ANY)) {
            Class<?> cls = bean.getBeanClass();
            WebFilter ann = cls.getAnnotation(WebFilter.class);
            if (ann == null) continue;
            String name = ann.filterName().isEmpty() ? cls.getSimpleName() : ann.filterName();
            filters.add(new FilterDecl(name, cls.asSubclass(Filter.class),
                    reference(bm, bean, Filter.class), initParams(ann.initParams()), ann.asyncSupported()));
            Set<DispatcherType> types = ann.dispatcherTypes().length == 0
                    ? EnumSet.of(DispatcherType.REQUEST)
                    : EnumSet.copyOf(List.of(ann.dispatcherTypes()));
            for (String pattern : effectivePatterns(ann.urlPatterns(), ann.value())) {
                filterMappings.add(new FilterMappingDecl(name, pattern, null, types));
            }
            for (String servletName : ann.servletNames()) {
                filterMappings.add(new FilterMappingDecl(name, null, servletName, types));
            }
        }

        for (Bean<?> bean : bm.getBeans(EventListener.class, ANY)) {
            Class<?> cls = bean.getBeanClass();
            if (cls.getAnnotation(WebListener.class) == null) continue;
            listeners.add(new ListenerDecl(cls.asSubclass(EventListener.class),
                    reference(bm, bean, EventListener.class)));
        }
        return new AnnotatedComponents(servlets, filters, filterMappings, listeners);
    }

    /** Returns {@code urlPatterns} if not empty, otherwise {@code value}. */
    private static String[] effectivePatterns(String[] urlPatterns, String[] value) {
        return urlPatterns.length > 0 ? urlPatterns : value;
    }

    private static Map<String, String> initParams(WebInitParam[] params) {
        Map<String, String> result = new LinkedHashMap<>();
        for (WebInitParam p : params) result.put(p.name(), p.value());
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> Supplier<T> reference(BeanManager bm, Bean<?> bean, Class<T> type) {
        Bean<Object> b = (Bean<Object>) bean;
        return () -> type.cast(bm.getReference(b, type, bm.createCreationalContext(b)));
    }

    public static BeanManager lookupBeanManager() {
        return CDI.current().getBeanManager();
    }

    private WebAppDiscovery() {}
}
