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
import io.vidocq.foy.internal.gen.WebComponentRegistry;
import io.vidocq.foy.spi.cdi.CdiWebComponents;
import io.vidocq.foy.spi.gen.WebComponentDescriptor;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Finds the CDI-managed web components ({@code @WebServlet}, {@code @WebFilter},
 * {@code @WebListener}) of a {@link BeanManager} and returns them as {@link AnnotatedComponents},
 * to be merged with {@code web.xml} by {@link DescriptorMerger}.
 *
 * <p>The component classes come from the {@link CdiWebComponents} beans registered at build time
 * by foy-cdi-vauban (one per archive, merged; each class is matched to the bean of that exact
 * class, and a listed class without one is skipped with a warning); without them (another CDI
 * container), the {@code Servlet}, {@code Filter} and
 * {@code EventListener} beans are walked instead. Metadata (names, URL patterns, init params,
 * load-on-startup, async support, filter dispatcher types and servlet names, security) always
 * comes from {@link WebComponentRegistry#lookup(Class)}, never from runtime annotation reflection.
 * Instances come from {@link BeanManager#getReference}, so CDI injection applies; the deployer
 * calls each factory once, so servlets and filters are singletons (Servlet 6.1 §2.2).</p>
 */
public final class WebAppDiscovery {

    private static final System.Logger LOG = System.getLogger(WebAppDiscovery.class.getName());
    private static final AnnotationLiteral<Any> ANY = new AnnotationLiteral<Any>() {};

    /**
     * @param bm the CDI bean manager
     * @param registry the registry giving each class its descriptor
     * @return the annotated components, in discovery order
     * @throws IllegalArgumentException when a component misuses the Servlet annotations
     */
    public static AnnotatedComponents discover(BeanManager bm, WebComponentRegistry registry) {
        Objects.requireNonNull(bm, "bm");
        Objects.requireNonNull(registry, "registry");
        List<ServletDecl> servlets = new ArrayList<>();
        List<FilterDecl> filters = new ArrayList<>();
        List<FilterMappingDecl> filterMappings = new ArrayList<>();
        List<ListenerDecl> listeners = new ArrayList<>();

        for (Candidate c : candidates(bm)) {
            Class<?> cls = c.type();
            WebComponentDescriptor d = registry.lookup(cls).descriptor();
            switch (d.kind()) {
                case SERVLET -> {
                    if (!Servlet.class.isAssignableFrom(cls)) continue;
                    servlets.add(new ServletDecl(d.name(), cls.asSubclass(Servlet.class),
                            reference(bm, c.bean(), Servlet.class), d.urlPatterns(), d.initParams(),
                            d.loadOnStartup(), d.asyncSupported(), d.servletSecurity()));
                }
                case FILTER -> {
                    if (!Filter.class.isAssignableFrom(cls)) continue;
                    filters.add(new FilterDecl(d.name(), cls.asSubclass(Filter.class),
                            reference(bm, c.bean(), Filter.class), d.initParams(), d.asyncSupported()));
                    Set<DispatcherType> types = d.dispatcherTypes().isEmpty()
                            ? EnumSet.of(DispatcherType.REQUEST) : EnumSet.copyOf(d.dispatcherTypes());
                    for (String pattern : d.urlPatterns()) {
                        filterMappings.add(new FilterMappingDecl(d.name(), pattern, null, types));
                    }
                    for (String servletName : d.servletNames()) {
                        filterMappings.add(new FilterMappingDecl(d.name(), null, servletName, types));
                    }
                }
                case LISTENER -> {
                    if (!EventListener.class.isAssignableFrom(cls)) continue;
                    listeners.add(new ListenerDecl(cls.asSubclass(EventListener.class),
                            reference(bm, c.bean(), EventListener.class)));
                }
                default -> { /* not a web component */ }
            }
        }
        return new AnnotatedComponents(servlets, filters, filterMappings, listeners);
    }

    private record Candidate(Class<?> type, Bean<?> bean) {}

    /** The CDI-managed component classes, each with its bean, without duplicates. */
    private static List<Candidate> candidates(BeanManager bm) {
        var seen = new LinkedHashSet<Class<?>>();
        var result = new ArrayList<Candidate>();
        Set<Bean<?>> index = bm.getBeans(CdiWebComponents.class, ANY);
        if (!index.isEmpty()) {
            // One index bean per archive built with foy-cdi-vauban: merge them all, in bean then list order.
            for (Bean<?> indexBean : index) {
                CdiWebComponents components = reference(bm, indexBean, CdiWebComponents.class).get();
                for (Class<?> cls : components.componentClasses()) {
                    if (!seen.add(cls)) continue;
                    // A lookup by type also matches the beans of subclasses: keep the class's own bean.
                    Set<Bean<?>> own = new LinkedHashSet<>();
                    for (Bean<?> bean : bm.getBeans(cls, ANY)) {
                        if (bean.getBeanClass() == cls) own.add(bean);
                    }
                    if (own.isEmpty()) {
                        LOG.log(System.Logger.Level.WARNING, "foy: " + cls.getName() + " is listed by "
                                + CdiWebComponents.class.getSimpleName() + " but has no bean of that class"
                                + " (vetoed, or loaded by another class loader); skipped");
                        continue;
                    }
                    result.add(new Candidate(cls, own.size() == 1 ? own.iterator().next() : bm.resolve(own)));
                }
            }
            return result;
        }
        LOG.log(System.Logger.Level.INFO, "foy: no " + CdiWebComponents.class.getSimpleName()
                + " bean (CDI container other than Vauban); walking the Servlet, Filter and EventListener beans");
        for (Class<?> base : List.of(Servlet.class, Filter.class, EventListener.class)) {
            for (Bean<?> bean : bm.getBeans(base, ANY)) {
                Class<?> cls = bean.getBeanClass();
                if (seen.add(cls)) result.add(new Candidate(cls, bean));
            }
        }
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
