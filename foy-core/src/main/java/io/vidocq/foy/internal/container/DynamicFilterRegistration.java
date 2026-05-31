package io.vidocq.foy.internal.container;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dynamic registration of a filter created via {@link jakarta.servlet.ServletContext#addFilter}.
 */
public final class DynamicFilterRegistration implements FilterRegistration.Dynamic {

    public record Mapping(EnumSet<DispatcherType> dispatchers, boolean isMatchAfter,
                          List<String> servletNames, List<String> urlPatterns) {}

    private final String name;
    private Filter instance;
    private Class<? extends Filter> klass;
    private String className;
    private final Map<String, String> initParams = new LinkedHashMap<>();
    private final List<Mapping> mappings = new java.util.ArrayList<>();
    private boolean asyncSupported;

    public DynamicFilterRegistration(String name, Filter instance) {
        this.name = name; this.instance = instance;
    }
    public DynamicFilterRegistration(String name, Class<? extends Filter> klass) {
        this.name = name; this.klass = klass;
    }
    public DynamicFilterRegistration(String name, String className) {
        this.name = name; this.className = className;
    }

    @Override public String getName() { return name; }
    @Override public String getClassName() {
        if (instance != null) return instance.getClass().getName();
        if (klass != null) return klass.getName();
        return className;
    }
    public Filter instance() { return instance; }
    public Class<? extends Filter> klass() { return klass; }

    @Override public boolean setInitParameter(String name, String value) {
        if (initParams.containsKey(name)) return false;
        initParams.put(name, value); return true;
    }
    @Override public String getInitParameter(String name) { return initParams.get(name); }
    @Override public Set<String> setInitParameters(Map<String, String> params) {
        Set<String> conflicts = new LinkedHashSet<>();
        for (var e : params.entrySet()) {
            if (initParams.containsKey(e.getKey())) conflicts.add(e.getKey());
        }
        if (conflicts.isEmpty()) initParams.putAll(params);
        return conflicts;
    }
    @Override public Map<String, String> getInitParameters() {
        return Collections.unmodifiableMap(initParams);
    }

    @Override public void addMappingForServletNames(EnumSet<DispatcherType> dispatcherTypes,
                                                    boolean isMatchAfter, String... servletNames) {
        mappings.add(new Mapping(
                dispatcherTypes == null ? EnumSet.of(DispatcherType.REQUEST) : EnumSet.copyOf(dispatcherTypes),
                isMatchAfter, List.of(servletNames), List.of()));
    }
    @Override public Collection<String> getServletNameMappings() {
        var out = new java.util.ArrayList<String>();
        for (Mapping m : mappings) out.addAll(m.servletNames());
        return Collections.unmodifiableCollection(out);
    }
    @Override public void addMappingForUrlPatterns(EnumSet<DispatcherType> dispatcherTypes,
                                                   boolean isMatchAfter, String... urlPatterns) {
        mappings.add(new Mapping(
                dispatcherTypes == null ? EnumSet.of(DispatcherType.REQUEST) : EnumSet.copyOf(dispatcherTypes),
                isMatchAfter, List.of(), List.of(urlPatterns)));
    }
    @Override public Collection<String> getUrlPatternMappings() {
        var out = new java.util.ArrayList<String>();
        for (Mapping m : mappings) out.addAll(m.urlPatterns());
        return Collections.unmodifiableCollection(out);
    }
    public List<Mapping> allMappings() { return Collections.unmodifiableList(mappings); }
    @Override public void setAsyncSupported(boolean isAsyncSupported) {
        this.asyncSupported = isAsyncSupported;
    }
    public boolean isAsyncSupported() { return asyncSupported; }
}
