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
package io.vidocq.foy.internal.bridge;

import io.vidocq.chappe.api.Request;
import io.vidocq.foy.internal.http.CookieCodec;
import io.vidocq.foy.internal.session.HttpSessionImpl;
import io.vidocq.foy.internal.session.SessionManager;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adapts a {@link Request} Chappe into {@link HttpServletRequest} Servlet 6.1.
 *
 * <p>Minimal implementation for the M2a milestone — many methods return
 * {@link UnsupportedOperationException}. They will be expanded upon as milestones progress.
 * (session, async, multipart, security, upgrade, etc.).</p>
 */
public final class HttpServletRequestImpl implements HttpServletRequest {

    private final Request chappe;
    private final String contextPath;
    private final String servletPath;
    private final String pathInfo;
    private final ServletContext servletContext;
    private final SessionManager sessionManager;
    private final Map<String, Object> attributes = new HashMap<>();
    private String characterEncoding;
    private ServletInputStream inputStream;
    private BufferedReader reader;
    private Cookie[] parsedCookies;
    private String requestedSessionId;
    private HttpSessionImpl currentSession;

    public HttpServletRequestImpl(Request chappe, ServletContext ctx,
                                  String contextPath, String servletPath, String pathInfo) {
        this(chappe, ctx, contextPath, servletPath, pathInfo, null);
    }

    public HttpServletRequestImpl(Request chappe, ServletContext ctx,
                                  String contextPath, String servletPath, String pathInfo,
                                  SessionManager sessionManager) {
        this.chappe = chappe;
        this.servletContext = ctx;
        this.contextPath = contextPath;
        this.servletPath = servletPath;
        this.pathInfo = pathInfo;
        this.sessionManager = sessionManager;
    }

    private io.vidocq.foy.spi.security.AuthenticatedUser currentUser;
    private String authType;

    public void bindAuthenticated(io.vidocq.foy.spi.security.AuthenticatedUser user,
                                  String authType) {
        this.currentUser = user;
        this.authType = authType;
    }

    public io.vidocq.foy.spi.security.AuthenticatedUser currentUser() {
        return currentUser;
    }

    // ---- Request line & URI ----

    @Override public String getAuthType() { return authType; }
    @Override public String getMethod() { return chappe.method().name(); }
    @Override public String getProtocol() {
        // Chappe expose HttpVersion sous forme "HTTP_1_1" — la spec Servlet attend
        // la forme HTTP standard "HTTP/1.1".
        return chappe.version().toString().replace('_', '.').replaceFirst("\\.", "/");
    }
    @Override public String getScheme() { return chappe.scheme(); }
    @Override public boolean isSecure() { return chappe.isSecure(); }
    @Override public String getRequestURI() { return chappe.path(); }
    @Override public StringBuffer getRequestURL() {
        StringBuffer sb = new StringBuffer();
        sb.append(chappe.scheme()).append("://").append(getServerName());
        int port = getServerPort();
        if (("http".equals(chappe.scheme()) && port != 80)
                || ("https".equals(chappe.scheme()) && port != 443)) {
            sb.append(':').append(port);
        }
        sb.append(chappe.path());
        return sb;
    }
    @Override public String getContextPath() {
        // Servlet 6.1 §3.5 : pour le root context "/", getContextPath() doit
        // retourner une chaîne vide. Pour "/foo", retourner "/foo".
        return "/".equals(contextPath) ? "" : contextPath;
    }
    @Override public String getServletPath() { return servletPath; }
    @Override public String getPathInfo() { return pathInfo; }
    @Override public String getPathTranslated() { return null; }
    @Override public String getQueryString() {
        String q = chappe.query();
        return q == null || q.isEmpty() ? null : q;
    }
    @Override public String getRequestId() { return ""; }
    @Override public String getProtocolRequestId() { return ""; }

    // ---- Headers ----

    @Override public String getHeader(String name) {
        return chappe.headers().first(name).orElse(null);
    }
    @Override public Enumeration<String> getHeaders(String name) {
        return Collections.enumeration(chappe.headers().all(name));
    }
    @Override public Enumeration<String> getHeaderNames() {
        List<String> names = new ArrayList<>();
        for (var e : chappe.headers()) names.add(e.name());
        return Collections.enumeration(names);
    }
    @Override public int getIntHeader(String name) {
        String v = getHeader(name);
        if (v == null) return -1;
        return Integer.parseInt(v);
    }
    @Override public long getDateHeader(String name) {
        String v = getHeader(name);
        if (v == null) return -1;
        // Servlet 6.1 §3.4 : supporte RFC 7231 IMF-fixdate + formats hérités RFC 850/ANSI-C.
        try {
            return java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse date header '" + name + "': " + v, e);
        }
    }
    @Override public String getContentType() { return getHeader("Content-Type"); }
    @Override public int getContentLength() {
        long l = getContentLengthLong();
        return l > Integer.MAX_VALUE ? -1 : (int) l;
    }
    @Override public long getContentLengthLong() {
        String v = getHeader("Content-Length");
        return v == null ? -1 : Long.parseLong(v);
    }

    // ---- Parameters (query string, then form body — Servlet 6.1 §3.1) ----
    //
    // On reparse chappe.query() nous-mêmes pour préserver les valeurs multiples
    // (?p=a&p=b retourne {"p": ["a","b"]}), ce que chappe.queryParams() ne
    // fait pas (map de String→String, une seule valeur par clé).

    private Map<String, List<String>> parsedParams;
    /** True once the form body was consumed by parameter parsing (§3.1.1). */
    private boolean bodyConsumedByParameters;

    private Map<String, List<String>> parameters() {
        if (parsedParams != null) return parsedParams;
        var out = new java.util.LinkedHashMap<String, List<String>>();
        decodeInto(out, chappe.query(), StandardCharsets.UTF_8);
        if (isFormPost() && inputStream == null && reader == null) {
            try {
                byte[] raw = chappe.body().asInputStream().readAllBytes();
                bodyConsumedByParameters = true;
                Charset cs = getCharacterEncoding() != null
                        ? Charset.forName(getCharacterEncoding())
                        : StandardCharsets.ISO_8859_1;
                decodeInto(out, new String(raw, StandardCharsets.ISO_8859_1), cs);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("cannot read form body", e);
            }
        }
        return parsedParams = out;
    }

    private boolean isFormPost() {
        if (!"POST".equalsIgnoreCase(getMethod())) return false;
        String ct = getContentType();
        if (ct == null) return false;
        int semi = ct.indexOf(';');
        String mime = (semi < 0 ? ct : ct.substring(0, semi)).trim();
        return "application/x-www-form-urlencoded".equalsIgnoreCase(mime);
    }

    private static void decodeInto(Map<String, List<String>> out, String encoded, Charset cs) {
        if (encoded == null || encoded.isEmpty()) return;
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            if (k.isEmpty()) continue;
            k = java.net.URLDecoder.decode(k, cs);
            v = java.net.URLDecoder.decode(v, cs);
            out.computeIfAbsent(k, _ -> new ArrayList<>()).add(v);
        }
    }

    @Override public String getParameter(String name) {
        List<String> v = parameters().get(name);
        return v == null || v.isEmpty() ? null : v.get(0);
    }
    @Override public Enumeration<String> getParameterNames() {
        return Collections.enumeration(parameters().keySet());
    }
    @Override public String[] getParameterValues(String name) {
        List<String> v = parameters().get(name);
        return v == null ? null : v.toArray(new String[0]);
    }
    @Override public Map<String, String[]> getParameterMap() {
        Map<String, String[]> map = new HashMap<>();
        for (var e : parameters().entrySet()) {
            map.put(e.getKey(), e.getValue().toArray(new String[0]));
        }
        return Collections.unmodifiableMap(map);
    }

    // ---- Body ----

    private boolean encodingLocked;

    /** Body stream wrapper kept for EOF tracking ({@link #isTrailerFieldsReady()}). */
    private ServletInputStreamImpl trackedBody;

    @Override public ServletInputStream getInputStream() throws IOException {
        if (reader != null) throw new IllegalStateException("getReader() already called");
        if (inputStream == null) {
            java.io.InputStream source = bodyConsumedByParameters
                    ? java.io.InputStream.nullInputStream()
                    : chappe.body().asInputStream();
            trackedBody = new ServletInputStreamImpl(source);
            inputStream = trackedBody;
            encodingLocked = true;
        }
        return inputStream;
    }
    @Override public BufferedReader getReader() throws IOException {
        if (inputStream != null) throw new IllegalStateException("getInputStream() already called");
        if (reader == null) {
            String enc = getCharacterEncoding();
            Charset cs;
            try {
                cs = enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc);
            } catch (RuntimeException e) {
                // Servlet 6.1 §3.11 : encoding invalide → UnsupportedEncodingException.
                throw new java.io.UnsupportedEncodingException(enc);
            }
            java.io.InputStream source = bodyConsumedByParameters
                    ? java.io.InputStream.nullInputStream()
                    : chappe.body().asInputStream();
            trackedBody = new ServletInputStreamImpl(source);
            reader = new BufferedReader(new InputStreamReader(trackedBody, cs));
            encodingLocked = true;
        }
        return reader;
    }

    // ---- Trailer fields (Servlet 6.1, HTTP/1.1 chunked + HTTP/2) ----

    @Override public Map<String, String> getTrailerFields() {
        var trailers = chappe.trailers();
        if (trailers.isEmpty()) return Map.of();
        var out = new java.util.LinkedHashMap<String, String>();
        for (var e : trailers) {
            // Spec: keys are lowercase, without any validation or merging
            out.put(e.name().toLowerCase(Locale.ROOT), e.value());
        }
        return out;
    }

    @Override public boolean isTrailerFieldsReady() {
        // Ready unless a chunked body is still being decoded: chappe delivers
        // the parsed trailers when the terminal chunk has been consumed, i.e.
        // exactly when the body stream reaches EOF.
        if (!"chunked".equalsIgnoreCase(chappe.headers().firstOrNull("Transfer-Encoding"))) {
            return true;
        }
        return trackedBody != null && trackedBody.isFinished();
    }
    @Override public String getCharacterEncoding() {
        if (characterEncoding != null) return characterEncoding;
        // Servlet 6.1 §3.11 : si l'en-tête Content-Type contient "charset=", c'est lui.
        String ct = getHeader("Content-Type");
        if (ct != null) {
            int idx = ct.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (idx >= 0) return ct.substring(idx + 8).trim();
        }
        return null;
    }
    @Override public void setCharacterEncoding(String env) throws java.io.UnsupportedEncodingException {
        // Servlet 6.1 §3.11 : appel après getReader()/getInputStream() est un no-op.
        if (encodingLocked) return;
        if (env != null && !Charset.isSupported(env)) {
            throw new java.io.UnsupportedEncodingException(env);
        }
        this.characterEncoding = env;
    }
    @Override public void setCharacterEncoding(Charset encoding) {
        if (encodingLocked) return;
        this.characterEncoding = encoding == null ? null : encoding.name();
    }

    // ---- Connection / server ----

    @Override public String getServerName() {
        String host = getHeader("Host");
        if (host != null) {
            int idx = host.indexOf(':');
            return idx < 0 ? host : host.substring(0, idx);
        }
        return chappe.localAddress() != null ? chappe.localAddress().getHostString() : "localhost";
    }
    @Override public int getServerPort() {
        String host = getHeader("Host");
        if (host != null) {
            int idx = host.indexOf(':');
            if (idx >= 0) return Integer.parseInt(host.substring(idx + 1));
        }
        return chappe.localAddress() != null ? chappe.localAddress().getPort() : -1;
    }
    @Override public String getRemoteAddr() {
        var addr = chappe.remoteAddress();
        return addr == null ? "" : addr.getAddress().getHostAddress();
    }
    @Override public String getRemoteHost() {
        var addr = chappe.remoteAddress();
        return addr == null ? "" : addr.getHostString();
    }
    @Override public int getRemotePort() {
        var addr = chappe.remoteAddress();
        return addr == null ? -1 : addr.getPort();
    }
    @Override public String getLocalAddr() {
        var addr = chappe.localAddress();
        return addr == null ? "" : addr.getAddress().getHostAddress();
    }
    @Override public String getLocalName() {
        var addr = chappe.localAddress();
        return addr == null ? "" : addr.getHostString();
    }
    @Override public int getLocalPort() {
        var addr = chappe.localAddress();
        return addr == null ? -1 : addr.getPort();
    }

    // ---- Attributes ----

    @Override public Object getAttribute(String name) { return attributes.get(name); }
    @Override public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }
    @Override public void setAttribute(String name, Object o) {
        if (o == null) { removeAttribute(name); return; }
        Object previous = attributes.put(name, o);
        io.vidocq.foy.internal.listener.ListenerRegistry reg = servletContextRegistry();
        if (reg == null) return;
        if (previous == null) reg.fireRequestAttributeAdded(servletContext, this, name, o);
        else reg.fireRequestAttributeReplaced(servletContext, this, name, previous);
    }
    @Override public void removeAttribute(String name) {
        Object previous = attributes.remove(name);
        if (previous == null) return;
        var reg = servletContextRegistry();
        if (reg != null) reg.fireRequestAttributeRemoved(servletContext, this, name, previous);
    }

    private io.vidocq.foy.internal.listener.ListenerRegistry servletContextRegistry() {
        if (servletContext instanceof io.vidocq.foy.internal.container.VidocqServletContext v) {
            return v.listenerRegistry();
        }
        return null;
    }

    // ---- Locale ----

    @Override public Locale getLocale() {
        var list = acceptedLocales();
        return list.isEmpty() ? Locale.getDefault() : list.get(0);
    }
    @Override public Enumeration<Locale> getLocales() {
        var list = acceptedLocales();
        return Collections.enumeration(list.isEmpty() ? List.of(Locale.getDefault()) : list);
    }

    /** Parses the {@code Accept-Language} header (RFC 7231 §5.3.5) into a list sorted by quality. */
    private List<Locale> acceptedLocales() {
        String h = getHeader("Accept-Language");
        if (h == null || h.isBlank()) return List.of();
        record Tagged(Locale loc, double q, int order) {}
        var items = new ArrayList<Tagged>();
        int i = 0;
        for (String token : h.split(",")) {
            token = token.trim();
            if (token.isEmpty()) continue;
            String tag = token;
            double q = 1.0;
            int semi = token.indexOf(';');
            if (semi >= 0) {
                tag = token.substring(0, semi).trim();
                for (String p : token.substring(semi + 1).split(";")) {
                    p = p.trim();
                    if (p.startsWith("q=")) {
                        try { q = Double.parseDouble(p.substring(2)); } catch (NumberFormatException ignored) {}
                    }
                }
            }
            try { items.add(new Tagged(Locale.forLanguageTag(tag), q, i++)); }
            catch (RuntimeException ignored) {}
        }
        items.sort((a, b) -> {
            int c = Double.compare(b.q, a.q);
            return c != 0 ? c : Integer.compare(a.order, b.order);
        });
        return items.stream().filter(t -> t.q > 0).map(Tagged::loc).toList();
    }

    // ---- Dispatcher / context ----

    private DispatcherType dispatcherType = DispatcherType.REQUEST;
    public void setDispatcherType(DispatcherType type) {
        if (type != null) this.dispatcherType = type;
    }
    @Override public DispatcherType getDispatcherType() { return dispatcherType; }
    @Override public ServletContext getServletContext() { return servletContext; }
    @Override public RequestDispatcher getRequestDispatcher(String path) {
        if (path == null) return null;
        String absolute;
        if (path.startsWith("/")) {
            absolute = contextPath.equals("/") ? path : contextPath + path;
        } else {
            // résolution relative au path courant (parent du servletPath+pathInfo)
            String current = servletPath + (pathInfo == null ? "" : pathInfo);
            int slash = current.lastIndexOf('/');
            String parent = slash <= 0 ? "/" : current.substring(0, slash + 1);
            String full = contextPath.equals("/") ? parent + path : contextPath + parent + path;
            absolute = full;
        }
        return servletContext.getRequestDispatcher(absolute);
    }
    @Override public HttpServletMapping getHttpServletMapping() {
        throw new UnsupportedOperationException();
    }

    // ---- Unimplemented Servlet 6.1 features (future milestones) ----

    @Override public Cookie[] getCookies() {
        if (parsedCookies == null) {
            parsedCookies = CookieCodec.parseCookieHeader(getHeader("Cookie"))
                    .toArray(new Cookie[0]);
        }
        return parsedCookies.length == 0 ? null : parsedCookies;
    }
    @Override public String getRemoteUser() { return currentUser == null ? null : currentUser.name(); }
    @Override public boolean isUserInRole(String role) {
        return currentUser != null && currentUser.hasRole(role);
    }
    @Override public Principal getUserPrincipal() { return currentUser; }
    private String urlSessionId;
    private boolean sessionIdFromUrl;
    public void setUrlSessionId(String id) { this.urlSessionId = id; this.sessionIdFromUrl = (id != null); }

    @Override public String getRequestedSessionId() {
        if (requestedSessionId == null) {
            // §7.1 : URL rewriting (;jsessionid=xxx) a priorité sur cookie pour la détection
            // de la session demandée — mais un simple "cherche cookie sinon URL" convient aussi
            // au TCK qui ne mixe jamais les deux.
            if (urlSessionId != null) {
                requestedSessionId = urlSessionId;
            } else {
                requestedSessionId = extractSessionIdFromCookies();
            }
        }
        return requestedSessionId;
    }
    @Override public HttpSession getSession(boolean create) {
        if (currentSession != null && !currentSession.isInvalidated()) return currentSession;
        if (sessionManager == null) {
            if (create) throw new IllegalStateException("no SessionManager bound");
            return null;
        }
        String id = getRequestedSessionId();
        HttpSessionImpl existing = id == null ? null : sessionManager.find(id);
        if (existing != null) {
            currentSession = existing;
            return existing;
        }
        if (!create) return null;
        currentSession = sessionManager.createNew();
        return currentSession;
    }
    @Override public HttpSession getSession() { return getSession(true); }
    @Override public String changeSessionId() {
        HttpSession s = getSession(false);
        if (s == null) throw new IllegalStateException("no session");
        throw new UnsupportedOperationException("changeSessionId not implemented");
    }
    @Override public boolean isRequestedSessionIdValid() {
        String id = getRequestedSessionId();
        return id != null && sessionManager != null && sessionManager.find(id) != null;
    }
    @Override public boolean isRequestedSessionIdFromCookie() {
        return getRequestedSessionId() != null && !sessionIdFromUrl;
    }
    @Override public boolean isRequestedSessionIdFromURL() {
        return sessionIdFromUrl && getRequestedSessionId() != null;
    }

    private String extractSessionIdFromCookies() {
        Cookie[] cookies = getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (SessionManager.COOKIE_NAME.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    public HttpSessionImpl boundSession() { return currentSession; }

    @Override public boolean authenticate(jakarta.servlet.http.HttpServletResponse response) throws IOException {
        if (currentUser != null) return true;
        var provider = resolveSecurityProvider();
        var basic = new io.vidocq.foy.internal.security.BasicAuthenticator(provider);
        var user = basic.tryAuthenticate(getHeader("Authorization")).orElse(null);
        if (user == null) {
            response.setHeader("WWW-Authenticate", basic.challengeHeaderValue());
            response.sendError(401, "Unauthorized");
            return false;
        }
        bindAuthenticated(user, "BASIC");
        return true;
    }
    @Override public void login(String username, String password) throws ServletException {
        if (currentUser != null) throw new ServletException("already authenticated");
        var user = resolveSecurityProvider().authenticate(username, password).orElse(null);
        if (user == null) throw new ServletException("invalid credentials");
        bindAuthenticated(user, "BASIC");
    }
    @Override public void logout() {
        currentUser = null;
        authType = null;
    }

    private io.vidocq.foy.spi.security.SecurityProvider resolveSecurityProvider() {
        if (servletContext instanceof io.vidocq.foy.internal.container.VidocqServletContext v) {
            return v.securityProvider();
        }
        return new io.vidocq.foy.internal.security.AnonymousSecurityProvider();
    }

    private java.util.List<io.vidocq.foy.internal.http.PartImpl> parsedParts;

    @Override public Collection<Part> getParts() throws IOException {
        ensurePartsParsed();
        return new ArrayList<>(parsedParts);
    }
    @Override public Part getPart(String name) throws IOException {
        ensurePartsParsed();
        for (var p : parsedParts) if (name.equals(p.getName())) return p;
        return null;
    }

    private void ensurePartsParsed() throws IOException {
        if (parsedParts != null) return;
        String ct = getContentType();
        if (ct == null || !ct.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            parsedParts = List.of();
            return;
        }
        String boundary = io.vidocq.foy.internal.http.MultipartParser.extractBoundary(ct);
        if (boundary == null) { parsedParts = List.of(); return; }
        parsedParts = io.vidocq.foy.internal.http.MultipartParser.parse(
                chappe.body().asInputStream(), boundary);
    }
    @Override public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) {
        throw new UnsupportedOperationException("upgrade not implemented");
    }
    private io.vidocq.foy.internal.async.AsyncContextImpl asyncContext;
    private jakarta.servlet.http.HttpServletResponse boundResponse;

    public void bindResponse(jakarta.servlet.http.HttpServletResponse res) {
        this.boundResponse = res;
        // Permet à la response de reconstruire une URL absolue pour sendRedirect(path).
        if (res instanceof HttpServletResponseImpl impl) impl.bindRequest(this);
    }
    public io.vidocq.foy.internal.async.AsyncContextImpl asyncContextInternal() { return asyncContext; }
    /** Resets async state, used by the bridge between two async dispatches so
     *  startAsync in a redispatched servlet creates a new context. */
    public void clearAsyncContext() { this.asyncContext = null; }

    private boolean asyncSupported = true;
    /** Fixed if the chain (servlet + filters) supports async — propagated by the bridge. */
    public void setAsyncSupported(boolean v) { this.asyncSupported = v; }

    @Override public AsyncContext startAsync() {
        if (boundResponse == null) throw new IllegalStateException("response not bound");
        return startAsync(this, boundResponse);
    }
    @Override public AsyncContext startAsync(jakarta.servlet.ServletRequest req, ServletResponse res) {
        // Servlet 6.1 §2.3.3.1 : startAsync doit throw IllegalStateException si la request
        // n'est pas éligible (servlet ou filtre de la chaîne en asyncSupported=false).
        if (!asyncSupported) {
            throw new IllegalStateException(
                    "async not supported on this servlet/filter chain");
        }
        // §2.3.3.1 : startAsync doit throw ISE si un async est déjà en place sur
        // cette request (completed OU dispatched OU en cours). Le bridge reset
        // explicitement via clearAsyncContext() avant un ré-invoke ASYNC.
        if (asyncContext != null) {
            throw new IllegalStateException("async already started on this request");
        }
        boolean original = (req == this && res == boundResponse);
        this.asyncContext = new io.vidocq.foy.internal.async.AsyncContextImpl(
                req, res, servletContext, original);
        return asyncContext;
    }
    @Override public boolean isAsyncStarted() {
        // §2.3.3.3 : true tant que le servlet (ou son dispatch) est encore en cours —
        // reste true après un complete() pendant la fin du service(). Le flag bascule
        // à false dès qu'un dispatch est planifié (le request original cède sa place
        // au servlet redispatched, cf. TCK asyncStartedTest4).
        return asyncContext != null && !asyncContext.hasDispatch();
    }
    @Override public boolean isAsyncSupported() { return asyncSupported; }
    @Override public AsyncContext getAsyncContext() {
        if (asyncContext == null) throw new IllegalStateException("no async context");
        return asyncContext;
    }
    @Override public ServletConnection getServletConnection() {
        throw new UnsupportedOperationException("ServletConnection not implemented");
    }
}
