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
package io.vidocq.foy.internal.container;

import io.vidocq.foy.internal.dispatcher.DispatchResolver;
import io.vidocq.foy.internal.dispatcher.RequestDispatcherImpl;
import io.vidocq.foy.internal.listener.ListenerRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.descriptor.JspConfigDescriptor;

import java.nio.charset.Charset;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@link ServletContext} minimum for milestone M2a.
 * <p>
 * Dynamic configuration operations (addServlet/addFilter/...) are not
 * supported in this milestone — we rely solely on the CDI discovery of
 * beans annotated {@code @WebServlet}.
 * </p>
 */
public final class VidocqServletContext implements ServletContext {

    private final String contextPath;
    private final String serverInfo;
    private final Map<String, Object> attributes = new HashMap<>();
    private final Map<String, String> initParameters = new HashMap<>();
    /** Configured default encodings; {@code null} until the descriptor or the application sets one. */
    private String requestCharacterEncoding;
    private String responseCharacterEncoding;
    private Map<String, String> mimeMappings = Map.of();
    private java.util.List<String> welcomeFiles = java.util.List.of();
    private Set<SessionTrackingMode> descriptorTrackingModes;
    private int sessionTimeout = 30;
    private ListenerRegistry listenerRegistry = new ListenerRegistry();
    private DispatchResolver dispatchResolver;
    private RequestDispatcherImpl.Invoker dispatchInvoker;
    private io.vidocq.foy.internal.error.ErrorPageRegistry errorPages =
            new io.vidocq.foy.internal.error.ErrorPageRegistry();
    private io.vidocq.foy.spi.security.SecurityProvider securityProvider =
            new io.vidocq.foy.internal.security.AnonymousSecurityProvider();
    private boolean initialized;
    private boolean programmaticListenerActive;
    private Map<String, String> localeEncodingMappings = Map.of();
    private final java.util.LinkedHashMap<String, DynamicServletRegistration> dynamicServlets = new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashMap<String, DynamicFilterRegistration> dynamicFilters = new java.util.LinkedHashMap<>();
    /** Static registrations (web.xml / @WebServlet), exposed by
     *  {@link #getServletRegistrations()} but not applied again by
     *  the harness (they are already part of the active servlet list). */
    private final java.util.LinkedHashMap<String, DynamicServletRegistration> staticServlets = new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashMap<String, DynamicFilterRegistration> staticFilters = new java.util.LinkedHashMap<>();

    /** Registers a static servlet registration (from web.xml/@WebServlet).
     *  The name is also marked reserved to block a later dynamic addServlet. */
    public DynamicServletRegistration registerStaticServlet(String name, Class<? extends Servlet> klass,
                                                            java.util.List<String> patterns,
                                                            java.util.Map<String, String> initParams,
                                                            boolean asyncSupported) {
        DynamicServletRegistration r = new DynamicServletRegistration(name, klass);
        r.attach(this);
        if (initParams != null) r.setInitParameters(new java.util.LinkedHashMap<>(initParams));
        if (patterns != null) for (String p : patterns) r.addMappingDirect(p);
        r.setAsyncSupported(asyncSupported);
        staticServlets.put(name, r);
        reservedServletNames.add(name);
        if (patterns != null) for (String p : patterns) reserveUrlPattern(p);
        return r;
    }

    public DynamicFilterRegistration registerStaticFilter(String name, Class<? extends Filter> klass,
                                                          java.util.Map<String, String> initParams,
                                                          boolean asyncSupported) {
        DynamicFilterRegistration r = new DynamicFilterRegistration(name, klass);
        if (initParams != null) r.setInitParameters(new java.util.LinkedHashMap<>(initParams));
        r.setAsyncSupported(asyncSupported);
        staticFilters.put(name, r);
        reservedFilterNames.add(name);
        return r;
    }
    /** Names reserved by web.xml — an addServlet/addFilter with this name should return null. */
    private final java.util.Set<String> reservedServletNames = new java.util.HashSet<>();
    private final java.util.Set<String> reservedFilterNames = new java.util.HashSet<>();
    /** URL patterns already mapped by web.xml to a static servlet. */
    private final java.util.Set<String> reservedUrlPatterns = new java.util.HashSet<>();
    public void reserveServletName(String name) { reservedServletNames.add(name); }
    public void reserveFilterName(String name) { reservedFilterNames.add(name); }
    public void reserveUrlPattern(String pattern) {
        if (pattern != null && !pattern.isEmpty()) reservedUrlPatterns.add(pattern);
    }

    /** Indicates whether {@code pattern} is already mapped to a servlet other than
     *  {@code selfName}, either from web.xml or another
     *  {@link DynamicServletRegistration}. Used by addMapping (§4.4)
     *  to enforce all-or-nothing conflict semantics. */
    public boolean isUrlPatternMappedElsewhere(String selfName, String pattern) {
        if (pattern == null) return false;
        if (reservedUrlPatterns.contains(pattern)) return true;
        for (var entry : dynamicServlets.entrySet()) {
            if (entry.getKey().equals(selfName)) continue;
            if (entry.getValue().getMappings().contains(pattern)) return true;
        }
        return false;
    }

    public Map<String, DynamicServletRegistration> dynamicServletRegistrations() {
        return java.util.Collections.unmodifiableMap(dynamicServlets);
    }
    public Map<String, DynamicFilterRegistration> dynamicFilterRegistrations() {
        return java.util.Collections.unmodifiableMap(dynamicFilters);
    }

    /** Enables/disables the "programmatic listener init" phase, during which
     *  dynamic configuration methods must throw UOE (§4.4.3). */
    public void setProgrammaticListenerActive(boolean active) { this.programmaticListenerActive = active; }

    /** Mapping &lt;locale&gt; → &lt;encoding&gt; from {@code web.xml} (Servlet 6.1 §14.4). */
    public void setLocaleEncodingMappings(Map<String, String> mappings) {
        var normalized = new java.util.HashMap<String, String>();
        if (mappings != null) {
            // Accept "zh_CN", "zh-CN" and "zh" spellings in any case.
            mappings.forEach((k, v) -> normalized.put(k.trim().replace('_', '-').toLowerCase(java.util.Locale.ROOT), v));
        }
        this.localeEncodingMappings = Map.copyOf(normalized);
    }
    public String encodingForLocale(java.util.Locale locale) {
        if (locale == null) return null;
        String lang = locale.getLanguage();
        String country = locale.getCountry();
        // 1) Mappings explicites du web.xml (§14.4) — prioritaires.
        if (!localeEncodingMappings.isEmpty()) {
            if (country != null && !country.isEmpty()) {
                String full = lang + "-" + country.toLowerCase(java.util.Locale.ROOT);
                String v = localeEncodingMappings.get(full);
                if (v != null) return v;
            }
            String v = localeEncodingMappings.get(lang);
            if (v != null) return v;
        }
        // 2) Défauts du conteneur (table alignée avec Tomcat / Servlet 6.1).
        return DEFAULT_LOCALE_ENCODINGS.get(lang);
    }

    private static final Map<String, String> DEFAULT_LOCALE_ENCODINGS = Map.ofEntries(
            Map.entry("ar", "ISO-8859-6"),
            Map.entry("be", "ISO-8859-5"),
            Map.entry("bg", "ISO-8859-5"),
            Map.entry("ca", "ISO-8859-1"),
            Map.entry("cs", "ISO-8859-2"),
            Map.entry("da", "ISO-8859-1"),
            Map.entry("de", "ISO-8859-1"),
            Map.entry("el", "ISO-8859-7"),
            Map.entry("en", "ISO-8859-1"),
            Map.entry("es", "ISO-8859-1"),
            Map.entry("et", "ISO-8859-1"),
            Map.entry("fi", "ISO-8859-1"),
            Map.entry("fr", "ISO-8859-1"),
            Map.entry("hr", "ISO-8859-2"),
            Map.entry("hu", "ISO-8859-2"),
            Map.entry("is", "ISO-8859-1"),
            Map.entry("it", "ISO-8859-1"),
            Map.entry("iw", "ISO-8859-8"),
            Map.entry("ja", "Shift_JIS"),
            Map.entry("ko", "EUC-KR"),
            Map.entry("lt", "ISO-8859-2"),
            Map.entry("lv", "ISO-8859-2"),
            Map.entry("mk", "ISO-8859-5"),
            Map.entry("nl", "ISO-8859-1"),
            Map.entry("no", "ISO-8859-1"),
            Map.entry("pl", "ISO-8859-2"),
            Map.entry("pt", "ISO-8859-1"),
            Map.entry("ro", "ISO-8859-2"),
            Map.entry("ru", "ISO-8859-5"),
            Map.entry("sh", "ISO-8859-5"),
            Map.entry("sk", "ISO-8859-2"),
            Map.entry("sl", "ISO-8859-2"),
            Map.entry("sq", "ISO-8859-2"),
            Map.entry("sr", "ISO-8859-5"),
            Map.entry("sv", "ISO-8859-1"),
            Map.entry("tr", "ISO-8859-9"),
            Map.entry("uk", "ISO-8859-5"),
            Map.entry("zh", "GB2312"),
            Map.entry("zh_TW", "Big5"));

    public VidocqServletContext(String contextPath) {
        this.contextPath = contextPath;
        this.serverInfo = "Vidocq Servlet/Chappe";
    }

    /** End-of-initialization marker (Servlet 6.1 §4.4); after this call,
     *  dynamic configuration methods must throw {@link IllegalStateException}. */
    public void markInitialized() { this.initialized = true; }

    public void setListenerRegistry(ListenerRegistry registry) {
        this.listenerRegistry = registry;
    }

    public ListenerRegistry listenerRegistry() { return listenerRegistry; }

    public void setDispatchInfrastructure(DispatchResolver resolver, RequestDispatcherImpl.Invoker invoker) {
        this.dispatchResolver = resolver;
        this.dispatchInvoker = invoker;
    }

    public DispatchResolver dispatchResolver() { return dispatchResolver; }
    public RequestDispatcherImpl.Invoker dispatchInvoker() { return dispatchInvoker; }

    public io.vidocq.foy.internal.error.ErrorPageRegistry errorPages() {
        return errorPages;
    }

    public void setErrorPages(io.vidocq.foy.internal.error.ErrorPageRegistry errorPages) {
        this.errorPages = errorPages;
    }

    public io.vidocq.foy.spi.security.SecurityProvider securityProvider() {
        return securityProvider;
    }

    public void setSecurityProvider(io.vidocq.foy.spi.security.SecurityProvider provider) {
        this.securityProvider = java.util.Objects.requireNonNull(provider);
    }

    private volatile io.vidocq.foy.internal.boot.ComponentFactory componentFactory;

    /** Sets the factory behind {@code createServlet/createFilter/createListener/addListener}. */
    public void setComponentFactory(io.vidocq.foy.internal.boot.ComponentFactory factory) {
        this.componentFactory = java.util.Objects.requireNonNull(factory, "factory");
    }

    /**
     * The factory creating dynamically requested components; when none was set, a
     * registry-backed factory over this context's class loader.
     */
    public io.vidocq.foy.internal.boot.ComponentFactory componentFactory() {
        var f = componentFactory;
        if (f == null) {
            synchronized (this) {
                f = componentFactory;
                if (f == null) {
                    f = io.vidocq.foy.internal.gen.RegistryComponentFactory.forClassLoader(getClassLoader());
                    componentFactory = f;
                }
            }
        }
        return f;
    }

    @Override public String getContextPath() { return contextPath; }
    @Override public ServletContext getContext(String uripath) {
        // Servlet 6.1 §4.8 : le conteneur peut retourner null si cross-context non supporté.
        // Ici, on résout via le registre des contextes déployés dans le même JVM.
        if (uripath == null || uripath.isEmpty() || !uripath.startsWith("/")) return null;
        return CrossContextRegistry.lookup(uripath);
    }
    @Override public int getMajorVersion() { return 6; }
    @Override public int getMinorVersion() { return 1; }

    private int effectiveMajor = 6;
    private int effectiveMinor = 1;
    public void setEffectiveVersion(int major, int minor) {
        this.effectiveMajor = major; this.effectiveMinor = minor;
    }
    @Override public int getEffectiveMajorVersion() { return effectiveMajor; }
    @Override public int getEffectiveMinorVersion() { return effectiveMinor; }
    /** The descriptor's {@code mime-mapping} entries (lower-case extension, no dot), consulted first. */
    public void setMimeMappings(Map<String, String> mappings) { this.mimeMappings = Map.copyOf(mappings); }

    /** The descriptor's welcome files, in declaration order (used by the Phase 4 welcome-file dispatch). */
    public void setWelcomeFiles(java.util.List<String> files) { this.welcomeFiles = java.util.List.copyOf(files); }
    public java.util.List<String> getWelcomeFiles() { return welcomeFiles; }

    /** Pre-populates the session cookie configuration from the descriptor (before initialisation). */
    public void applyCookieConfig(io.vidocq.foy.internal.webxml.WebAppDescriptor.CookieConfigDef def) {
        if (def == null) return;
        if (def.name() != null) sessionCookieConfig.setName(def.name());
        if (def.domain() != null) sessionCookieConfig.setDomain(def.domain());
        if (def.path() != null) sessionCookieConfig.setPath(def.path());
        if (def.maxAge() != null) sessionCookieConfig.setMaxAge(def.maxAge());
        if (def.httpOnly() != null) sessionCookieConfig.setHttpOnly(def.httpOnly());
        if (def.secure() != null) sessionCookieConfig.setSecure(def.secure());
        def.attributes().forEach(sessionCookieConfig::setAttribute);
    }

    /** Tracking modes declared by the descriptor; they replace the container default. */
    public void setDescriptorTrackingModes(Set<SessionTrackingMode> modes) {
        this.descriptorTrackingModes = EnumSet.copyOf(modes);
    }

    /** The configured default request encoding, {@code null} when none (the client's charset rules). */
    public String configuredRequestCharacterEncoding() { return requestCharacterEncoding; }

    /** The configured default response encoding, {@code null} when none. */
    public String configuredResponseCharacterEncoding() { return responseCharacterEncoding; }

    /** The session cookie configuration, without the programmatic-listener guard (container use). */
    public io.vidocq.foy.internal.session.VidocqSessionCookieConfig sessionCookieConfigInternal() {
        return sessionCookieConfig;
    }

    @Override public String getMimeType(String file) {
        if (file == null) return null;
        int dot = file.lastIndexOf('.');
        if (dot < 0) return null;
        String ext = file.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        String mapped = mimeMappings.get(ext);
        if (mapped != null) return mapped;
        return switch (ext) {
            case "class" -> "application/x-java-class";
            case "html", "htm" -> "text/html";
            case "txt" -> "text/plain";
            case "xml" -> "text/xml";
            case "json" -> "application/json";
            case "css" -> "text/css";
            case "js"  -> "application/javascript";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "svg" -> "image/svg+xml";
            case "pdf" -> "application/pdf";
            case "zip" -> "application/zip";
            default -> java.net.URLConnection.guessContentTypeFromName(file);
        };
    }
    /**
     * WAR resource source — provided by the DeployableContainer at startup
     * of the harness. The provider exposes the known paths and opens the flows.
     */
    public interface ResourceProvider {
        /** Lists immediate paths under {@code path} (type {@code /WEB-INF/}). */
        Set<String> listPaths(String path);
        java.io.InputStream openStream(String path);
        /** Returns a URL (for example {@code file:}) exposing {@code path}
         *  with original path case and structure, or {@code null}. */
        default java.net.URL toUrl(String path) { return null; }
        /**
         * Size and last-modification time of the file at {@code path}, read from the provider's
         * own index or file system so that no URL connection is opened for metadata;
         * {@code null} when {@code path} is not a file or the provider does not know.
         */
        default Metadata metadata(String path) { return null; }

        /** A file's length in bytes and modification time in epoch milliseconds ({@code -1}: unknown). */
        record Metadata(long length, long lastModified) {}
    }

    private ResourceProvider resourceProvider;
    public void setResourceProvider(ResourceProvider provider) { this.resourceProvider = provider; }

    @Override public Set<String> getResourcePaths(String path) {
        if (path == null || !path.startsWith("/")) return null;
        if (resourceProvider == null) return null;
        Set<String> out = resourceProvider.listPaths(path);
        return (out == null || out.isEmpty()) ? null : out;
    }
    @Override public java.net.URL getResource(String path) throws java.net.MalformedURLException {
        if (path == null) return null;
        if (!path.startsWith("/")) {
            throw new java.net.MalformedURLException("path must start with '/': " + path);
        }
        if (resourceProvider == null) return null;
        return resourceProvider.toUrl(path);
    }
    /** {@link ResourceProvider#metadata} of the configured provider, {@code null} when unknown. */
    public ResourceProvider.Metadata resourceMetadata(String path) {
        if (path == null || !path.startsWith("/") || resourceProvider == null) return null;
        return resourceProvider.metadata(path);
    }
    @Override public java.io.InputStream getResourceAsStream(String path) {
        if (path == null || !path.startsWith("/")) return null;
        if (resourceProvider == null) return null;
        return resourceProvider.openStream(path);
    }
    @Override public RequestDispatcher getRequestDispatcher(String path) {
        // Servlet 6.1 section 9.1: a dispatcher for every context-relative path. A deployed
        // application always resolves (its "/" servlet or the container default servlet, which
        // answers 404 for a missing resource); notFound only serves a bridge built without one.
        if (path == null) return null;
        if (!path.startsWith("/")) return null; // doit être absolu dans le contexte
        if (dispatchResolver == null || dispatchInvoker == null) return null;
        String tmp = path;
        if (!contextPath.equals("/") && path.startsWith(contextPath)) {
            tmp = path.substring(contextPath.length());
            if (tmp.isEmpty()) tmp = "/";
        }
        // Section 9.1.1: the dispatch path is already decoded; only its dot segments are normalised
        // (query string split off first). A path climbing above the context root has no dispatcher.
        String query = "";
        int q = tmp.indexOf('?');
        if (q >= 0) { query = tmp.substring(q); tmp = tmp.substring(0, q); }
        String normalized = io.vidocq.foy.internal.http.RequestPaths.normalize(tmp);
        if (normalized == null) return null;
        final String resolvePath = normalized + query;
        return dispatchResolver.resolve(resolvePath)
                .<RequestDispatcher>map(t -> new RequestDispatcherImpl(t, dispatchInvoker))
                .orElseGet(() -> RequestDispatcherImpl.notFound(resolvePath));
    }
    @Override public RequestDispatcher getNamedDispatcher(String name) {
        if (dispatchResolver == null || dispatchInvoker == null || name == null) return null;
        return dispatchResolver.resolveByName(name)
                .<RequestDispatcher>map(t -> new RequestDispatcherImpl(t, dispatchInvoker))
                .orElse(null);
    }
    @Override public void log(String msg) { System.getLogger("servlet.log").log(System.Logger.Level.INFO, msg); }
    @Override public void log(String message, Throwable throwable) {
        System.getLogger("servlet.log").log(System.Logger.Level.ERROR, message, throwable);
    }
    @Override public String getRealPath(String path) { return null; }
    @Override public String getServerInfo() { return serverInfo; }
    @Override public String getInitParameter(String name) {
        if (name == null) throw new NullPointerException("name is null");
        return initParameters.get(name);
    }
    @Override public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParameters.keySet());
    }
    @Override public boolean setInitParameter(String name, String value) {
        if (name == null) throw new NullPointerException("name is null");
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        return initParameters.putIfAbsent(name, value) == null;
    }

    @Override public Object getAttribute(String name) {
        // Servlet 6.1 §4.0 : NullPointerException sur name null.
        if (name == null) throw new NullPointerException("name is null");
        return attributes.get(name);
    }
    @Override public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }
    @Override public void setAttribute(String name, Object object) {
        if (name == null) throw new NullPointerException("name is null");
        if (object == null) { removeAttribute(name); return; }
        Object previous = attributes.put(name, object);
        if (previous == null) listenerRegistry.fireContextAttributeAdded(this, name, object);
        else listenerRegistry.fireContextAttributeReplaced(this, name, previous);
    }
    @Override public void removeAttribute(String name) {
        if (name == null) throw new NullPointerException("name is null");
        Object previous = attributes.remove(name);
        if (previous != null) listenerRegistry.fireContextAttributeRemoved(this, name, previous);
    }

    private String servletContextName = "vidocq";
    public void setServletContextName(String name) {
        if (name != null && !name.isEmpty()) this.servletContextName = name;
    }
    @Override public String getServletContextName() { return servletContextName; }

    // ---- Dynamic registration — not supported in M2a ----
    // Servlet 6.1 §4.4 : après initialisation du contexte, ces méthodes doivent throw
    // IllegalStateException. Avant initialisation, elles throw UnsupportedOperationException
    // tant que la feature n'est pas implémentée.

    @Override public ServletRegistration.Dynamic addServlet(String name, String className) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        requireNonEmptyName(name, "servletName");
        if (dynamicServlets.containsKey(name) || reservedServletNames.contains(name)) return null;
        var r = new DynamicServletRegistration(name, className);
        r.attach(this);
        dynamicServlets.put(name, r);
        return r;
    }
    @Override public ServletRegistration.Dynamic addServlet(String name, Servlet servlet) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        requireNonEmptyName(name, "servletName");
        if (dynamicServlets.containsKey(name) || reservedServletNames.contains(name)) return null;
        var r = new DynamicServletRegistration(name, servlet);
        r.attach(this);
        dynamicServlets.put(name, r);
        return r;
    }
    @Override public ServletRegistration.Dynamic addServlet(String name, Class<? extends Servlet> c) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        requireNonEmptyName(name, "servletName");
        if (dynamicServlets.containsKey(name) || reservedServletNames.contains(name)) return null;
        var r = new DynamicServletRegistration(name, c);
        r.attach(this);
        dynamicServlets.put(name, r);
        return r;
    }
    @Override public ServletRegistration.Dynamic addJspFile(String name, String jspFile) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        // §4.4 addJspFile : IllegalArgumentException si servletName null ou vide.
        requireNonEmptyName(name, "servletName");
        // JSP non supporté — on enregistre quand même la registration pour les tests qui
        // vérifient le flux de configuration (la request vers cette URL renverra 404).
        if (dynamicServlets.containsKey(name) || reservedServletNames.contains(name)) return null;
        var r = new DynamicServletRegistration(name, (String) null);
        r.attach(this);
        dynamicServlets.put(name, r);
        return r;
    }

    private static void requireNonEmptyName(String name, String arg) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException(arg + " is null or empty");
        }
    }
    @Override public <T extends Servlet> T createServlet(Class<T> c) throws jakarta.servlet.ServletException {
        if (programmaticListenerActive) throw programmaticForbidden();
        return componentFactory().newInstance(c);
    }
    @Override public ServletRegistration getServletRegistration(String name) {
        if (programmaticListenerActive) throw programmaticForbidden();
        var d = dynamicServlets.get(name);
        return d != null ? d : staticServlets.get(name);
    }
    @Override public Map<String, ? extends ServletRegistration> getServletRegistrations() {
        if (programmaticListenerActive) throw programmaticForbidden();
        // §4.4 : retourne *toutes* les ServletRegistration — web.xml + dynamiques.
        var merged = new java.util.LinkedHashMap<String, ServletRegistration>();
        merged.putAll(staticServlets);
        merged.putAll(dynamicServlets);
        return java.util.Collections.unmodifiableMap(merged);
    }
    @Override public FilterRegistration.Dynamic addFilter(String name, String className) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        requireNonEmptyName(name, "filterName");
        if (dynamicFilters.containsKey(name) || reservedFilterNames.contains(name)) return null;
        var r = new DynamicFilterRegistration(name, className);
        dynamicFilters.put(name, r);
        return r;
    }
    @Override public FilterRegistration.Dynamic addFilter(String name, Filter f) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        requireNonEmptyName(name, "filterName");
        if (dynamicFilters.containsKey(name) || reservedFilterNames.contains(name)) return null;
        var r = new DynamicFilterRegistration(name, f);
        dynamicFilters.put(name, r);
        return r;
    }
    @Override public FilterRegistration.Dynamic addFilter(String name, Class<? extends Filter> c) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        requireNonEmptyName(name, "filterName");
        if (dynamicFilters.containsKey(name) || reservedFilterNames.contains(name)) return null;
        var r = new DynamicFilterRegistration(name, c);
        dynamicFilters.put(name, r);
        return r;
    }
    @Override public <T extends Filter> T createFilter(Class<T> c) throws jakarta.servlet.ServletException {
        if (programmaticListenerActive) throw programmaticForbidden();
        return componentFactory().newInstance(c);
    }
    @Override public FilterRegistration getFilterRegistration(String name) {
        if (programmaticListenerActive) throw programmaticForbidden();
        var d = dynamicFilters.get(name);
        return d != null ? d : staticFilters.get(name);
    }
    @Override public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        if (programmaticListenerActive) throw programmaticForbidden();
        var merged = new java.util.LinkedHashMap<String, FilterRegistration>();
        merged.putAll(staticFilters);
        merged.putAll(dynamicFilters);
        return java.util.Collections.unmodifiableMap(merged);
    }

    // ---- Listeners ----
    // Servlet 6.1 §4.4 : addListener n'est autorisé que pendant l'initialisation
    // (SCI.onStartup ou contextInitialized d'un listener non-programmatique).

    @Override public void addListener(String className) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        var factory = componentFactory();
        try {
            Class<?> c = factory.load(className);
            addProgrammaticListener((java.util.EventListener) factory.newInstance(c));
        } catch (ClassNotFoundException | jakarta.servlet.ServletException | ClassCastException e) {
            throw new IllegalArgumentException("cannot load listener " + className, e);
        }
    }
    @Override public <T extends java.util.EventListener> void addListener(T t) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        addProgrammaticListener(t);
    }
    @Override public void addListener(Class<? extends java.util.EventListener> listenerClass) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        try {
            addProgrammaticListener(componentFactory().newInstance(listenerClass));
        } catch (jakarta.servlet.ServletException e) {
            throw new IllegalArgumentException("cannot instantiate " + listenerClass, e);
        }
    }
    @Override public <T extends java.util.EventListener> T createListener(Class<T> c) throws jakarta.servlet.ServletException {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        // Servlet 6.1 §4.4 : seule une classe implémentant une interface reconnue
        // peut être instanciée par createListener.
        if (!jakarta.servlet.ServletContextListener.class.isAssignableFrom(c)
                && !jakarta.servlet.ServletContextAttributeListener.class.isAssignableFrom(c)
                && !jakarta.servlet.ServletRequestListener.class.isAssignableFrom(c)
                && !jakarta.servlet.ServletRequestAttributeListener.class.isAssignableFrom(c)
                && !jakarta.servlet.http.HttpSessionListener.class.isAssignableFrom(c)
                && !jakarta.servlet.http.HttpSessionAttributeListener.class.isAssignableFrom(c)
                && !jakarta.servlet.http.HttpSessionIdListener.class.isAssignableFrom(c)) {
            throw new IllegalArgumentException(
                    "class " + c.getName() + " does not implement any supported listener interface");
        }
        return componentFactory().newInstance(c);
    }

    private boolean contextInitializedPhase;
    /** Active/désactive la phase d'appel des {@code contextInitialized} des listeners
     *  declared (web.xml/@WebListener) — during this phase, addListener
     *  d'un ServletContextListener doit throw IllegalArgumentException (§4.4). */
    public void setContextInitializedPhase(boolean active) { this.contextInitializedPhase = active; }

    private void addProgrammaticListener(java.util.EventListener l) {
        // Servlet 6.1 §4.4 : rejette un EventListener qui n'implémente aucune des
        // interfaces écoute reconnues.
        if (!(l instanceof jakarta.servlet.ServletContextListener
                || l instanceof jakarta.servlet.ServletContextAttributeListener
                || l instanceof jakarta.servlet.ServletRequestListener
                || l instanceof jakarta.servlet.ServletRequestAttributeListener
                || l instanceof jakarta.servlet.http.HttpSessionListener
                || l instanceof jakarta.servlet.http.HttpSessionAttributeListener
                || l instanceof jakarta.servlet.http.HttpSessionIdListener)) {
            throw new IllegalArgumentException(
                    "listener " + l.getClass().getName() + " does not implement any supported listener interface");
        }
        // §4.4 : addListener d'un ServletContextListener n'est autorisé que depuis
        // un SCI.onStartup — jamais depuis un contextInitialized d'un autre SCL.
        if (contextInitializedPhase && l instanceof jakarta.servlet.ServletContextListener) {
            throw new IllegalArgumentException(
                    "ServletContextListener " + l.getClass().getName()
                            + " can only be added from a ServletContainerInitializer");
        }
        if (listenerRegistry == null) listenerRegistry = new ListenerRegistry();
        listenerRegistry.register(l, true);
    }

    private static UnsupportedOperationException programmaticForbidden() {
        return new UnsupportedOperationException(
                "dynamic configuration not allowed from a programmatic listener");
    }

    // ---- Sessions ----

    private Set<SessionTrackingMode> effectiveSessionTrackingModes; // null = défaut COOKIE
    private final io.vidocq.foy.internal.session.VidocqSessionCookieConfig sessionCookieConfig
            = new io.vidocq.foy.internal.session.VidocqSessionCookieConfig(this);
    @Override public SessionCookieConfig getSessionCookieConfig() {
        if (programmaticListenerActive) throw programmaticForbidden();
        return sessionCookieConfig;
    }
    @Override public void setSessionTrackingModes(Set<SessionTrackingMode> modes) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        if (modes != null && modes.contains(SessionTrackingMode.SSL) && modes.size() > 1) {
            throw new IllegalArgumentException("SSL tracking mode is mutually exclusive");
        }
        this.effectiveSessionTrackingModes = modes == null ? null : EnumSet.copyOf(modes);
    }
    @Override public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        return descriptorTrackingModes == null ? EnumSet.of(SessionTrackingMode.COOKIE)
                : EnumSet.copyOf(descriptorTrackingModes);
    }
    @Override public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        return effectiveSessionTrackingModes == null
                ? getDefaultSessionTrackingModes()
                : EnumSet.copyOf(effectiveSessionTrackingModes);
    }
    /** Flag "initialized" no longer prevents tracking modes from being read from a contextInitialized. */
    public boolean isInitializedInternal() { return initialized; }
    @Override public int getSessionTimeout() { return sessionTimeout; }
    @Override public void setSessionTimeout(int sessionTimeout) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        this.sessionTimeout = sessionTimeout;
    }
    /** Setter interne (contourne les checks) — utilisé par le harness pour
     *  propager la valeur de {@code <session-timeout>} du web.xml. */
    public void setSessionTimeoutInternal(int minutes) { this.sessionTimeout = minutes; }

    @Override public JspConfigDescriptor getJspConfigDescriptor() { return null; }
    @Override public ClassLoader getClassLoader() { return Thread.currentThread().getContextClassLoader(); }
    @Override public void declareRoles(String... roleNames) {}
    @Override public String getVirtualServerName() { return "vidocq"; }

    /** {@code null} when nothing is configured (Servlet 6.1). */
    @Override public String getRequestCharacterEncoding() { return requestCharacterEncoding; }
    @Override public void setRequestCharacterEncoding(String encoding) {
        if (initialized) throw alreadyInitialized();
        this.requestCharacterEncoding = encoding;
    }
    @Override public void setRequestCharacterEncoding(Charset encoding) {
        setRequestCharacterEncoding(encoding == null ? null : encoding.name());
    }
    @Override public String getResponseCharacterEncoding() { return responseCharacterEncoding; }
    @Override public void setResponseCharacterEncoding(String encoding) {
        if (initialized) throw alreadyInitialized();
        this.responseCharacterEncoding = encoding;
    }
    @Override public void setResponseCharacterEncoding(Charset encoding) {
        setResponseCharacterEncoding(encoding == null ? null : encoding.name());
    }

    private RuntimeException dynamicUnavailable() {
        // Servlet 6.1 §4.4 : après initialisation, IllegalStateException est requis.
        if (initialized) return alreadyInitialized();
        return new UnsupportedOperationException("dynamic registration not implemented in M2a");
    }

    private static IllegalStateException alreadyInitialized() {
        return new IllegalStateException("ServletContext already initialized");
    }
}
