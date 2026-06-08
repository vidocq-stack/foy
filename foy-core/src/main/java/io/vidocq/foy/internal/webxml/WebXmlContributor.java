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
package io.vidocq.foy.internal.webxml;

import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.dispatcher.FilterMapping;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
import io.vidocq.foy.internal.listener.ListenerRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;

import java.io.IOException;
import java.io.InputStream;
import java.util.EventListener;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Loads {@code WEB-INF/web.xml} from the classpath (if it exists), instantiates the
 * servlets/filters/listeners by reflection and contributes to the dispatcher + registry.
 *
 * <p>The call is made after CDI discovery: a web.xml definition is added
 * without overwriting a name already discovered by annotation.</p>
 */
public final class WebXmlContributor {

    public record Contribution(List<ServletDispatcher.Mapping> servletMappings,
                               List<FilterMapping> filterMappings,
                               List<EventListener> listeners,
                               WebAppDescriptor descriptor) {}

    public static Optional<Contribution> load(ClassLoader providedLoader,
                                              List<ServletDispatcher.Mapping> existingServlets,
                                              List<FilterMapping> existingFilters,
                                              VidocqServletContext servletContext,
                                              ListenerRegistry registry) {
        final ClassLoader loader = providedLoader != null ? providedLoader
                : (Thread.currentThread().getContextClassLoader() != null
                        ? Thread.currentThread().getContextClassLoader()
                        : WebXmlContributor.class.getClassLoader());

        WebAppDescriptor descriptor;
        try (InputStream in = loader.getResourceAsStream("WEB-INF/web.xml")) {
            if (in == null) return Optional.empty();
            descriptor = WebXmlParser.parse(in);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read WEB-INF/web.xml", e);
        }
        if (descriptor.isEmpty()) return Optional.empty();

        Set<String> existingServletNames = new HashSet<>();
        existingServlets.forEach(m -> existingServletNames.add(m.servletName()));
        Set<String> existingFilterNames = new HashSet<>();
        existingFilters.forEach(m -> existingFilterNames.add(m.filterName()));

        var servletMappings = new java.util.ArrayList<ServletDispatcher.Mapping>();
        for (WebAppDescriptor.ServletDef def : descriptor.servlets()) {
            if (existingServletNames.contains(def.name())) continue;
            Servlet instance = instantiate(loader, def.className(), Servlet.class);
            for (String pattern : descriptor.patternsFor(def.name())) {
                servletMappings.add(new ServletDispatcher.Mapping(
                        UrlPatternMatcher.of(pattern), instance, def.name()));
            }
        }

        var filterMappings = new java.util.ArrayList<FilterMapping>();
        for (WebAppDescriptor.FilterDef def : descriptor.filters()) {
            if (existingFilterNames.contains(def.name())) continue;
            Filter instance = instantiate(loader, def.className(), Filter.class);
            for (WebAppDescriptor.FilterMappingDef mapping : descriptor.filterMappings()) {
                if (!mapping.filterName().equals(def.name())) continue;
                filterMappings.add(new FilterMapping(
                        UrlPatternMatcher.of(mapping.urlPattern()), instance, def.name(),
                        mapping.dispatcherTypes()));
            }
        }

        var listeners = new java.util.ArrayList<EventListener>();
        for (String cls : descriptor.listenerClasses()) {
            listeners.add(instantiate(loader, cls, EventListener.class));
        }

        descriptor.contextParams().forEach(servletContext::setInitParameter);
        descriptor.errorPages().forEach(def -> {
            if (def.statusCode() != null) {
                servletContext.errorPages().register(def.statusCode(), def.location());
            } else if (def.exceptionType() != null) {
                Class<? extends Throwable> t = loadThrowable(loader, def.exceptionType());
                servletContext.errorPages().register(t, def.location());
            }
        });
        if (descriptor.sessionTimeoutMinutes() > 0) {
            servletContext.setSessionTimeout(descriptor.sessionTimeoutMinutes());
        }

        return Optional.of(new Contribution(servletMappings, filterMappings, listeners, descriptor));
    }

    @SuppressWarnings("unchecked")
    private static <T> T instantiate(ClassLoader loader, String className, Class<T> type) {
        try {
            Class<?> cls = Class.forName(className, true, loader);
            if (!type.isAssignableFrom(cls)) {
                throw new IllegalStateException(className + " does not implement " + type.getName());
            }
            return (T) cls.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to instantiate " + className, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Throwable> loadThrowable(ClassLoader loader, String name) {
        try {
            Class<?> cls = Class.forName(name, true, loader);
            if (!Throwable.class.isAssignableFrom(cls)) {
                throw new IllegalStateException(name + " is not a Throwable");
            }
            return (Class<? extends Throwable>) cls;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("unknown exception class: " + name, e);
        }
    }

    private WebXmlContributor() {}
}
