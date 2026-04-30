package io.vidocq.foy.internal.bridge;

import io.vidocq.foy.internal.dispatcher.DispatchTarget;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * {@link HttpServletRequestWrapper} utilisé pendant un
 * {@link RequestDispatcher#forward forward} : expose les nouveaux servletPath/pathInfo/queryString,
 * et reporte {@link DispatcherType#FORWARD}.
 *
 * <p>Les attributs {@code jakarta.servlet.forward.*} sont positionnés sur la request
 * originale par {@link io.vidocq.foy.internal.dispatcher.RequestDispatcherImpl}
 * avant d'invoquer le wrapper.</p>
 */
public final class ForwardedRequest extends HttpServletRequestWrapper {

    private final DispatchTarget target;
    private final java.util.Map<String, String[]> forwardParams;

    public ForwardedRequest(HttpServletRequest original, DispatchTarget target) {
        super(original);
        this.target = target;
        this.forwardParams = parseQuery(target.queryString());
    }

    private static java.util.Map<String, String[]> parseQuery(String qs) {
        if (qs == null || qs.isEmpty()) return null;
        var out = new java.util.LinkedHashMap<String, java.util.List<String>>();
        for (String pair : qs.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            if (k.isEmpty()) continue;
            k = java.net.URLDecoder.decode(k, java.nio.charset.StandardCharsets.UTF_8);
            v = java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8);
            out.computeIfAbsent(k, _ -> new java.util.ArrayList<>()).add(v);
        }
        var result = new java.util.LinkedHashMap<String, String[]>();
        for (var e : out.entrySet()) result.put(e.getKey(), e.getValue().toArray(new String[0]));
        return result;
    }

    @Override public String getRequestURI() {
        HttpServletRequest delegate = (HttpServletRequest) getRequest();
        String ctx = delegate.getContextPath();
        return ctx.equals("/") ? target.path() : ctx + target.path();
    }
    @Override public String getServletPath() { return target.servletPath(); }
    @Override public String getPathInfo() { return target.pathInfo(); }
    @Override public String getQueryString() { return target.queryString(); }
    @Override public DispatcherType getDispatcherType() { return DispatcherType.FORWARD; }

    // §9.4 : pendant un forward, les paramètres de la request doivent être
    // l'agrégation des paramètres originaux *et* de ceux de la nouvelle
    // query-string (ceux de la nouvelle query-string prévalent sur collision).
    @Override public String getParameter(String name) {
        if (forwardParams != null && forwardParams.containsKey(name)) {
            String[] v = forwardParams.get(name);
            return v.length == 0 ? null : v[0];
        }
        return super.getParameter(name);
    }
    @Override public String[] getParameterValues(String name) {
        if (forwardParams != null && forwardParams.containsKey(name)) return forwardParams.get(name);
        return super.getParameterValues(name);
    }
    @Override public java.util.Map<String, String[]> getParameterMap() {
        if (forwardParams == null) return super.getParameterMap();
        var out = new java.util.LinkedHashMap<>(super.getParameterMap());
        out.putAll(forwardParams);
        return java.util.Collections.unmodifiableMap(out);
    }
    @Override public java.util.Enumeration<String> getParameterNames() {
        if (forwardParams == null) return super.getParameterNames();
        var union = new java.util.LinkedHashSet<String>();
        super.getParameterNames().asIterator().forEachRemaining(union::add);
        union.addAll(forwardParams.keySet());
        return java.util.Collections.enumeration(union);
    }
}
