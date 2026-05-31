package io.vidocq.foy.internal.boot;

import io.vidocq.foy.internal.dispatcher.FilterMapping;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.annotation.WebServlet;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.List;
import java.util.Set;

/**
 * Scan the {@link BeanManager} Vauban to discover:
 * <ul>
 *   <li>CDI beans annotated {@code @WebServlet} → {@link ServletDispatcher.Mapping}</li>
 *   <li>CDI beans annotated {@code @WebFilter} → {@link FilterMapping}</li>
 * </ul>
 *
 * <p>Each url-pattern produces a mapping. Servlets and filters are
 * treated as a singleton in the sense of Servlet 6.1 §2.2.</p>
 */
public final class WebAppDiscovery {

    private static final AnnotationLiteral<Any> ANY = new AnnotationLiteral<Any>() {};

    public static List<ServletDispatcher.Mapping> discoverServlets(BeanManager beanManager) {
        List<ServletDispatcher.Mapping> mappings = new ArrayList<>();
        Set<Bean<?>> beans = beanManager.getBeans(Servlet.class, ANY);
        for (Bean<?> bean : beans) {
            Class<?> cls = bean.getBeanClass();
            WebServlet ann = cls.getAnnotation(WebServlet.class);
            if (ann == null) continue;
            Servlet instance = (Servlet) resolveInstance(beanManager, bean, Servlet.class);
            String name = ann.name().isEmpty() ? cls.getSimpleName() : ann.name();
            for (String pattern : effectivePatterns(ann.urlPatterns(), ann.value())) {
                mappings.add(new ServletDispatcher.Mapping(
                        UrlPatternMatcher.of(pattern), instance, name));
            }
        }
        return mappings;
    }

    public static List<EventListener> discoverListeners(BeanManager beanManager) {
        List<EventListener> listeners = new ArrayList<>();
        Set<Bean<?>> beans = beanManager.getBeans(EventListener.class, ANY);
        for (Bean<?> bean : beans) {
            Class<?> cls = bean.getBeanClass();
            if (cls.getAnnotation(WebListener.class) == null) continue;
            listeners.add((EventListener) resolveInstance(beanManager, bean, EventListener.class));
        }
        return listeners;
    }

    public static List<FilterMapping> discoverFilters(BeanManager beanManager) {
        List<FilterMapping> mappings = new ArrayList<>();
        Set<Bean<?>> beans = beanManager.getBeans(Filter.class, ANY);
        for (Bean<?> bean : beans) {
            Class<?> cls = bean.getBeanClass();
            WebFilter ann = cls.getAnnotation(WebFilter.class);
            if (ann == null) continue;
            Filter instance = (Filter) resolveInstance(beanManager, bean, Filter.class);
            String name = ann.filterName().isEmpty() ? cls.getSimpleName() : ann.filterName();
            Set<DispatcherType> types = ann.dispatcherTypes().length == 0
                    ? EnumSet.of(DispatcherType.REQUEST)
                    : EnumSet.copyOf(List.of(ann.dispatcherTypes()));
            for (String pattern : effectivePatterns(ann.urlPatterns(), ann.value())) {
                mappings.add(new FilterMapping(
                        UrlPatternMatcher.of(pattern), instance, name, types));
            }
        }
        return mappings;
    }

    /** Returns {@code @WebServlet.urlPatterns} if not empty, otherwise {@code .value}. */
    private static String[] effectivePatterns(String[] urlPatterns, String[] value) {
        return urlPatterns.length > 0 ? urlPatterns : value;
    }

    @SuppressWarnings("unchecked")
    private static Object resolveInstance(BeanManager bm, Bean<?> bean, Class<?> type) {
        Bean<Object> b = (Bean<Object>) bean;
        return bm.getReference(b, type, bm.createCreationalContext(b));
    }

    public static BeanManager lookupBeanManager() {
        return CDI.current().getBeanManager();
    }

    private WebAppDiscovery() {}
}
