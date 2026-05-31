package io.vidocq.foy.internal.container;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletSecurityElement;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Dynamic registration of a servlet created via {@link jakarta.servlet.ServletContext#addServlet}.
 *
 * <p>Stores the name, class/instance/className, url-pattern mappings,
 * init-params and options (load-on-startup, async-supported, run-as, etc.).
 * Will be materialized in the dispatcher by the harness at the end of the initialization phase.</p>
 */
public final class DynamicServletRegistration implements ServletRegistration.Dynamic {

    private final String name;
    private Servlet instance;
    private Class<? extends Servlet> klass;
    private String className;
    private final Map<String, String> initParams = new LinkedHashMap<>();
    private final Set<String> mappings = new LinkedHashSet<>();
    private int loadOnStartup = -1;
    private boolean asyncSupported;
    private MultipartConfigElement multipart;
    private String runAsRole;
    private ServletSecurityElement security;
    private VidocqServletContext owner;

    public DynamicServletRegistration(String name, Servlet instance) {
        this.name = name; this.instance = instance;
    }
    public DynamicServletRegistration(String name, Class<? extends Servlet> klass) {
        this.name = name; this.klass = klass;
    }
    public DynamicServletRegistration(String name, String className) {
        this.name = name; this.className = className;
    }

    /** Links this registration to its context so addMapping can inspect
     *  other registrations / reserved url-patterns to detect conflicts. */
    void attach(VidocqServletContext ctx) { this.owner = ctx; }

    @Override public String getName() { return name; }
    @Override public String getClassName() {
        if (instance != null) return instance.getClass().getName();
        if (klass != null) return klass.getName();
        return className;
    }
    public Servlet instance() { return instance; }
    public Class<? extends Servlet> klass() { return klass; }

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

    @Override public Set<String> addMapping(String... urlPatterns) {
        // Servlet 6.1 §4.4 / ServletRegistration.addMapping : si *un* pattern est déjà
        // mappé à un autre servlet (web.xml ou autre dynamic), AUCUN update n'est fait
        // et la méthode retourne l'ensemble des patterns en conflit.
        Set<String> conflicts = new LinkedHashSet<>();
        for (String p : urlPatterns) {
            if (mappings.contains(p)) continue; // déjà sur CE servlet : pas un conflit
            if (owner != null && owner.isUrlPatternMappedElsewhere(name, p)) conflicts.add(p);
        }
        if (!conflicts.isEmpty()) return conflicts;
        for (String p : urlPatterns) mappings.add(p);
        return conflicts;
    }
    @Override public Collection<String> getMappings() { return Collections.unmodifiableSet(mappings); }
    /** Adds a url-pattern without conflict checks, reserved for the harness
     *  to populate ServletRegistration entries from web.xml / @WebServlet. */
    void addMappingDirect(String pattern) { if (pattern != null) mappings.add(pattern); }
    @Override public String getRunAsRole() { return runAsRole; }
    @Override public void setRunAsRole(String roleName) { this.runAsRole = roleName; }

    @Override public void setLoadOnStartup(int loadOnStartup) { this.loadOnStartup = loadOnStartup; }
    public int getLoadOnStartup() { return loadOnStartup; }
    @Override public Set<String> setServletSecurity(ServletSecurityElement constraint) {
        this.security = constraint;
        return Collections.emptySet();
    }
    public ServletSecurityElement getServletSecurity() { return security; }
    @Override public void setMultipartConfig(MultipartConfigElement multipartConfig) {
        this.multipart = multipartConfig;
    }
    public MultipartConfigElement getMultipartConfig() { return multipart; }
    @Override public void setAsyncSupported(boolean isAsyncSupported) {
        this.asyncSupported = isAsyncSupported;
    }
    public boolean isAsyncSupported() { return asyncSupported; }
}
