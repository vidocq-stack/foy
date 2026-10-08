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
import io.vidocq.foy.internal.boot.WebAppModel.FilterMappingDecl;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.gen.WebComponentRegistry;
import io.vidocq.foy.spi.cdi.CdiWebComponents;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CDI discovery: classes from {@link CdiWebComponents}, metadata from the registry, instances from CDI. */
class WebAppDiscoveryTest {

    @WebServlet("/s")
    public static class DiscoveredServlet extends HttpServlet {}

    @WebFilter(urlPatterns = "/f", servletNames = "named", dispatcherTypes = DispatcherType.FORWARD)
    public static class DiscoveredFilter implements Filter {
        @Override public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {}
    }

    @WebListener
    public static class DiscoveredListener implements ServletContextListener {
        @Override public void contextInitialized(ServletContextEvent e) {}
    }

    /** Annotated subclass of an annotated servlet: a lookup by type DiscoveredServlet matches both beans. */
    @WebServlet("/sub")
    public static class SubServlet extends DiscoveredServlet {}

    /** Listed by an index but never given a bean. */
    @WebServlet("/orphan")
    public static class OrphanServlet extends HttpServlet {}

    /** A CDI bean implementing Servlet without {@code @WebServlet}: not a web component. */
    public static class PlainServlet extends HttpServlet {}

    private final WebComponentRegistry registry =
            WebComponentRegistry.forClassLoader(WebAppDiscoveryTest.class.getClassLoader());
    private LogCapture log;

    @BeforeEach
    void captureLog() {
        // foy-core does not read java.logging; the test (patched into the module) adds the edge
        // before LogCapture, the only class naming java.util.logging types, is loaded.
        WebAppDiscoveryTest.class.getModule().addReads(ModuleLayer.boot().findModule("java.logging").orElseThrow());
        log = new LogCapture(WebAppDiscovery.class.getName());
    }

    @AfterEach
    void releaseLog() {
        log.close();
    }

    @Test
    void cdiWebComponentsBeanGivesTheClassesAndTheRegistryTheMetadata() {
        var servlet = new DiscoveredServlet();
        var filter = new DiscoveredFilter();
        var listener = new DiscoveredListener();
        CdiWebComponents index = () -> List.of(DiscoveredServlet.class, DiscoveredFilter.class,
                DiscoveredListener.class);
        var bm = new FakeBeanManager()
                .bean(CdiWebComponents.class, index)
                .bean(DiscoveredServlet.class, servlet)
                .bean(DiscoveredFilter.class, filter)
                .bean(DiscoveredListener.class, listener);

        AnnotatedComponents found = WebAppDiscovery.discover(bm.proxy(), registry);

        ServletDecl s = found.servlets().getFirst();
        assertEquals(DiscoveredServlet.class.getName(), s.name(), "default name is the binary class name");
        assertEquals(List.of("/s"), s.urlPatterns());
        assertSame(servlet, s.factory().get(), "instance comes from BeanManager.getReference");
        var f = found.filters().getFirst();
        assertEquals(DiscoveredFilter.class.getName(), f.name());
        assertSame(filter, f.factory().get());
        assertEquals(List.of(
                        new FilterMappingDecl(f.name(), "/f", null, Set.of(DispatcherType.FORWARD)),
                        new FilterMappingDecl(f.name(), null, "named", Set.of(DispatcherType.FORWARD))),
                found.filterMappings());
        assertSame(listener, found.listeners().getFirst().factory().get());
        assertEquals(List.of(), bm.walked, "the Servlet/Filter/EventListener walk is not used");
        assertEquals(0, infoLines());
    }

    @Test
    void withoutCdiWebComponentsTheBeansAreWalkedAndOneInfoLineIsLogged() {
        var servlet = new DiscoveredServlet();
        var bm = new FakeBeanManager()
                .bean(DiscoveredServlet.class, servlet, Servlet.class)
                .bean(PlainServlet.class, new PlainServlet(), Servlet.class)
                .bean(DiscoveredFilter.class, new DiscoveredFilter(), Filter.class)
                .bean(DiscoveredListener.class, new DiscoveredListener(), EventListener.class);

        AnnotatedComponents found = WebAppDiscovery.discover(bm.proxy(), registry);

        assertEquals(List.of(DiscoveredServlet.class.getName()),
                found.servlets().stream().map(ServletDecl::name).toList(), "unannotated beans are skipped");
        assertSame(servlet, found.servlets().getFirst().factory().get());
        assertEquals(1, found.filters().size());
        assertEquals(1, found.listeners().size());
        assertEquals(List.<Type>of(Servlet.class, Filter.class, EventListener.class), bm.walked);
        assertEquals(1, infoLines());
    }

    @Test
    void everyCdiWebComponentsBeanIsMergedInBeanThenListOrderWithoutDuplicates() {
        // One index per archive built with foy-cdi-vauban: resolving a single one would be ambiguous.
        CdiWebComponents app = () -> List.of(DiscoveredServlet.class, DiscoveredFilter.class);
        CdiWebComponents library = () -> List.of(DiscoveredFilter.class, DiscoveredListener.class);
        var bm = new FakeBeanManager()
                .bean(AppIndex.class, app, CdiWebComponents.class)
                .bean(LibraryIndex.class, library, CdiWebComponents.class)
                .bean(DiscoveredServlet.class, new DiscoveredServlet())
                .bean(DiscoveredFilter.class, new DiscoveredFilter())
                .bean(DiscoveredListener.class, new DiscoveredListener());

        AnnotatedComponents found = WebAppDiscovery.discover(bm.proxy(), registry);

        assertEquals(List.of(DiscoveredServlet.class), found.servlets().stream().map(ServletDecl::type).toList());
        assertEquals(1, found.filters().size(), "a class listed by two indexes is discovered once");
        assertEquals(1, found.listeners().size());
        assertEquals(List.of(), bm.walked);
    }

    @Test
    void aClassIsMatchedToItsOwnBeanNotToTheBeansOfItsSubclasses() {
        var parent = new DiscoveredServlet();
        var sub = new SubServlet();
        CdiWebComponents index = () -> List.of(DiscoveredServlet.class, SubServlet.class);
        var bm = new FakeBeanManager()
                .bean(CdiWebComponents.class, index)
                .bean(DiscoveredServlet.class, parent)
                .bean(SubServlet.class, sub, DiscoveredServlet.class);

        AnnotatedComponents found = WebAppDiscovery.discover(bm.proxy(), registry);

        assertEquals(List.of(DiscoveredServlet.class, SubServlet.class),
                found.servlets().stream().map(ServletDecl::type).toList());
        assertSame(parent, found.servlets().get(0).factory().get());
        assertSame(sub, found.servlets().get(1).factory().get());
    }

    @Test
    void anIndexedClassWithoutABeanIsSkippedWithOneWarning() {
        CdiWebComponents index = () -> List.of(OrphanServlet.class, DiscoveredServlet.class);
        var bm = new FakeBeanManager()
                .bean(CdiWebComponents.class, index)
                .bean(DiscoveredServlet.class, new DiscoveredServlet());

        AnnotatedComponents found = WebAppDiscovery.discover(bm.proxy(), registry);

        assertEquals(List.of(DiscoveredServlet.class), found.servlets().stream().map(ServletDecl::type).toList());
        var warnings = log.messages(Level.WARNING);
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().contains(OrphanServlet.class.getName()), warnings::toString);
    }

    /** Bean classes of the two index beans of the merge test. */
    private interface AppIndex {}
    private interface LibraryIndex {}

    private long infoLines() {
        return log.infoLines();
    }

    /** Captures the java.util.logging records of one logger. */
    private static final class LogCapture {
        private final List<LogRecord> records = new ArrayList<>();
        private final Logger logger;
        private final Handler handler = new Handler() {
            @Override public void publish(LogRecord r) { records.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        };

        LogCapture(String name) {
            logger = Logger.getLogger(name);
            logger.addHandler(handler);
        }

        long infoLines() {
            return records.stream().filter(r -> r.getLevel() == Level.INFO).count();
        }

        List<String> messages(Level level) {
            return records.stream().filter(r -> r.getLevel() == level).map(LogRecord::getMessage).toList();
        }

        void close() {
            logger.removeHandler(handler);
        }
    }

    /** Minimal {@link BeanManager}: beans by bean class, plus optional extra lookup types. */
    private static final class FakeBeanManager {
        private final Map<Bean<?>, Object> instances = new LinkedHashMap<>();
        private final Map<Type, Set<Bean<?>>> byType = new LinkedHashMap<>();
        final List<Type> walked = new ArrayList<>();

        FakeBeanManager bean(Class<?> beanClass, Object instance, Type... lookupTypes) {
            Bean<?> bean = (Bean<?>) Proxy.newProxyInstance(Bean.class.getClassLoader(), new Class<?>[]{Bean.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "getBeanClass" -> beanClass;
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == a[0];
                        case "toString" -> "Bean[" + beanClass.getName() + "]";
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
            instances.put(bean, instance);
            byType.computeIfAbsent(beanClass, k -> new LinkedHashSet<>()).add(bean);
            for (Type t : lookupTypes) byType.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(bean);
            return this;
        }

        BeanManager proxy() {
            return (BeanManager) Proxy.newProxyInstance(BeanManager.class.getClassLoader(),
                    new Class<?>[]{BeanManager.class}, (p, m, a) -> switch (m.getName()) {
                        case "getBeans" -> {
                            Type t = (Type) a[0];
                            if (t == Servlet.class || t == Filter.class || t == EventListener.class) walked.add(t);
                            yield byType.getOrDefault(t, Set.of());
                        }
                        case "resolve" -> {
                            @SuppressWarnings("unchecked") Set<Bean<?>> set = (Set<Bean<?>>) a[0];
                            if (set.size() > 1) throw new AmbiguousResolutionException(set.toString());
                            yield set.isEmpty() ? null : set.iterator().next();
                        }
                        case "getReference" -> instances.get(a[0]);
                        case "createCreationalContext" -> null;
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == a[0];
                        case "toString" -> "FakeBeanManager";
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
        }
    }
}
