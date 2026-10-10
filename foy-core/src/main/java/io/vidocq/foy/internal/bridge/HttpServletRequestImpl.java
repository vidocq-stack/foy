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
import io.vidocq.foy.internal.container.VidocqServletContext;
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
    private String canonicalPath;

    /**
     * Records the canonical request path (section 3.5.2, {@link io.vidocq.foy.internal.http.RequestPaths}),
     * computed once by the bridge.
     */
    public void setCanonicalPath(String canonicalPath) { this.canonicalPath = canonicalPath; }

    /**
     * The canonical (decoded, normalised, context-relative) request path, as used for servlet and
     * filter mapping and security constraints; {@code null} for a request the bridge did not build.
     */
    public String canonicalPath() { return canonicalPath; }

    /**
     * The decoded, canonical, context-relative path a zero-argument {@code AsyncContext.dispatch()}
     * targets (section 2.3.3.3): the URI of the innermost container forward or async dispatch in
     * the wrapper chain (already built from a decoded path) without its context path, else the
     * canonical path of the container request. Application wrappers are looked through, so a
     * wrapped request never falls back to the raw, encoded {@code getRequestURI()}. Returns
     * {@code null} when {@code request} wraps no HTTP request.
     */
    public static String canonicalDispatchPath(jakarta.servlet.ServletRequest request) {
        jakarta.servlet.ServletRequest r = request;
        while (r != null) {
            if (r instanceof HttpServletRequestImpl impl) {
                return impl.canonicalPath == null ? withoutContextPath(impl) : impl.canonicalPath;
            }
            if (r instanceof ForwardedRequest f && !f.isNamed()) return withoutContextPath(f);
            if (r instanceof AsyncDispatchRequest a) return withoutContextPath(a);
            if (r instanceof jakarta.servlet.ServletRequestWrapper w) r = w.getRequest();
            else return r instanceof HttpServletRequest h ? withoutContextPath(h) : null;
        }
        return null;
    }

    /** {@code getRequestURI()} without the leading {@code getContextPath()} it is built from. */
    private static String withoutContextPath(HttpServletRequest h) {
        String uri = h.getRequestURI();
        String cp = h.getContextPath();
        if (uri == null || cp == null || cp.isEmpty() || !uri.startsWith(cp)) return uri;
        String rest = uri.substring(cp.length());
        return rest.isEmpty() ? "/" : rest;
    }

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
    /** The request URI as received: neither decoded nor normalised, path parameters included. */
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
    /** Slice of the canonical, decoded request path (section 3.5.2). */
    @Override public String getServletPath() { return servletPath; }
    @Override public String getPathInfo() { return pathInfo; }
    @Override public String getPathTranslated() { return null; }
    @Override public String getQueryString() {
        String q = chappe.query();
        return q == null || q.isEmpty() ? null : q;
    }
    /** Source of {@link #getRequestId()}: unique within the JVM, never reused. */
    private static final java.util.concurrent.atomic.AtomicLong REQUEST_IDS =
            new java.util.concurrent.atomic.AtomicLong();
    /** A decimal counter value, unique for the lifetime of the JVM, assigned when the request is built. */
    private final String requestId = Long.toString(REQUEST_IDS.incrementAndGet());

    @Override public String getRequestId() { return requestId; }

    /**
     * {@code ""}: HTTP/1.x has no request identifier, and Chappe does not expose the HTTP/2
     * stream id to the request API (BUG-20261009-09).
     */
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
                Charset cs = formBodyCharset();
                decodeInto(out, new String(raw, StandardCharsets.ISO_8859_1), cs);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("cannot read form body", e);
            }
        }
        return parsedParams = out;
    }

    /** Body charset for form parameters; unknown or illegal names fall back to ISO-8859-1. */
    private Charset formBodyCharset() {
        String enc = getCharacterEncoding();
        if (enc == null) return StandardCharsets.ISO_8859_1;
        try {
            return Charset.forName(enc);
        } catch (RuntimeException e) {
            return StandardCharsets.ISO_8859_1;
        }
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
            try {
                k = java.net.URLDecoder.decode(k, cs);
                v = java.net.URLDecoder.decode(v, cs);
            } catch (IllegalArgumentException e) {
                continue; // malformed %-encoding: skip this pair only
            }
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
    private volatile ServletInputStreamImpl trackedBody;

    // ---- Non-blocking I/O (Servlet 6.1 section 3.7) ----

    private final Object callbacksMonitor = new Object();
    /** Created on first use; guarded by {@link #callbacksMonitor}. */
    private CallbackSerializer callbacks;
    /** A dispatch is running: listener callbacks wait. Guarded by {@link #callbacksMonitor}. */
    private boolean callbacksHeld = true;
    /**
     * Non-blocking input is allowed once async started (an upgraded connection gets its own
     * streams, see {@link #upgrade}); failures fail the open cycle.
     */
    private final ServletInputStreamImpl.NonBlockingHost nonBlockingHost = new ServletInputStreamImpl.NonBlockingHost() {
        @Override public boolean nonBlockingAllowed() { return isAsyncStarted(); }
        @Override public CallbackSerializer callbacks() { return callbackSerializer(); }
        @Override public void failed(Throwable t) {
            var cycle = asyncContext;
            if (cycle != null) cycle.fail(t);
        }
    };

    /** The request's listener callback serializer, run with the application's class loader. */
    CallbackSerializer callbackSerializer() {
        synchronized (callbacksMonitor) {
            if (callbacks == null) {
                callbacks = new CallbackSerializer(servletContext.getClassLoader());
                if (callbacksHeld) callbacks.hold();
            }
            return callbacks;
        }
    }

    /**
     * A dispatch (REQUEST or ASYNC) is about to run: listener callbacks wait for it, and the one in
     * progress finishes first. Called by the bridge on the pipeline thread.
     */
    void holdCallbacks() {
        CallbackSerializer s;
        synchronized (callbacksMonitor) {
            callbacksHeld = true;
            s = callbacks;
        }
        if (s != null) s.hold();
    }

    /** The dispatch returned: listener callbacks may run. Called by the bridge on the pipeline thread. */
    void releaseCallbacks() {
        CallbackSerializer s;
        synchronized (callbacksMonitor) {
            callbacksHeld = false;
            s = callbacks;
        }
        if (s != null) s.release();
    }

    /**
     * The async processing is over: no listener callback runs any more (the one in progress
     * finishes first) and the body pump stops after its current read. Never waits for that read: a
     * silent client must not hold the response back. On HTTP/2 the read (a DATA queue) is
     * interrupted; on HTTP/1.x it cannot be (that would close the connection), so the bridge waits
     * for it in {@link #handBackInput()} once the response is delivered. Idempotent.
     */
    void endNonBlockingIo() {
        CallbackSerializer s;
        synchronized (callbacksMonitor) {
            callbacksHeld = true;
            s = callbacks;
        }
        if (s != null) s.close();
        var body = trackedBody;
        if (body != null) body.endNonBlocking(chappe.version() == io.vidocq.chappe.api.HttpVersion.HTTP_2);
    }

    /**
     * Whether chappe must wait for the body pump before it reads the body itself (HTTP/1.x: the
     * unread body is drained on the connection after the response; HTTP/2 needs no hand-back).
     */
    boolean needsInputHandBack() {
        if (chappe.version() == io.vidocq.chappe.api.HttpVersion.HTTP_2) return false;
        var body = trackedBody;
        return body != null && body.pumpAlive();
    }

    /**
     * Called once the response is delivered (its body released, on a normal or a failed exit):
     * the body pump lets go of chappe's body ({@link ServletInputStreamImpl#handBack()}), so chappe
     * drains the unread body alone. Bounded: a pump still blocked on a silent upload is
     * interrupted, which closes the connection. No-op without a pump.
     */
    void handBackInput() {
        if (chappe.version() == io.vidocq.chappe.api.HttpVersion.HTTP_2) return;
        var body = trackedBody;
        if (body != null) body.handBack();
    }

    @Override public ServletInputStream getInputStream() throws IOException {
        if (reader != null) throw new IllegalStateException("getReader() already called");
        if (inputStream == null) {
            java.io.InputStream source = bodyConsumedByParameters
                    ? java.io.InputStream.nullInputStream()
                    : chappe.body().asInputStream();
            trackedBody = new ServletInputStreamImpl(source, nonBlockingHost);
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
            trackedBody = new ServletInputStreamImpl(source, nonBlockingHost);
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
            if (idx >= 0) {
                String v = ct.substring(idx + 8);
                int semi = v.indexOf(';');
                if (semi >= 0) v = v.substring(0, semi);
                v = v.trim();
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                    v = v.substring(1, v.length() - 1).trim();
                }
                return v;
            }
        }
        // No client charset: the context's default request encoding (descriptor or setter), if any.
        return servletContext instanceof VidocqServletContext v
                ? v.configuredRequestCharacterEncoding() : null;
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
        if (servletContext instanceof VidocqServletContext v) {
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
        // Section 9.1: the dispatcher path is context-relative; a relative path is resolved
        // against the parent of the current servlet path + path info. The context path is
        // never prepended (ServletContext#getRequestDispatcher takes a context-relative path).
        String relative = path;
        if (!path.startsWith("/")) {
            String current = servletPath + (pathInfo == null ? "" : pathInfo);
            int slash = current.lastIndexOf('/');
            String parent = slash <= 0 ? "/" : current.substring(0, slash + 1);
            relative = parent + path;
        }
        return servletContext.getRequestDispatcher(relative);
    }
    @Override public HttpServletMapping getHttpServletMapping() {
        HttpServletMapping m = mapping;
        return m != null ? m : new io.vidocq.foy.internal.dispatcher.ServletMappingImpl("", "", "", null);
    }
    public void setHttpServletMapping(HttpServletMapping mapping) { this.mapping = mapping; }
    private HttpServletMapping mapping;

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
    public void setUrlSessionId(String id) { this.urlSessionId = id; }

    /**
     * The session id sent by the client (section 7.1), read only through the effective tracking
     * modes. The session cookie wins when {@code COOKIE} is effective and the cookie is present
     * (as Tomcat: a {@code ;jsessionid=} in a link cannot override the session the browser
     * holds, which would ease session fixation); the {@code ;jsessionid=} path parameter is used
     * only without a session cookie and when {@code URL} is effective. A disabled mode's id is
     * ignored.
     */
    @Override public String getRequestedSessionId() {
        if (!requestedSessionIdResolved) {
            requestedSessionIdResolved = true;
            var modes = servletContext == null ? java.util.Set.<jakarta.servlet.SessionTrackingMode>of()
                    : servletContext.getEffectiveSessionTrackingModes();
            String fromCookie = modes.contains(jakarta.servlet.SessionTrackingMode.COOKIE)
                    ? extractSessionIdFromCookies() : null;
            if (fromCookie != null) {
                requestedSessionId = fromCookie;
            } else if (urlSessionId != null && modes.contains(jakarta.servlet.SessionTrackingMode.URL)) {
                requestedSessionId = urlSessionId;
                requestedSessionIdFromUrl = true;
            }
        }
        return requestedSessionId;
    }
    private boolean requestedSessionIdResolved;
    private boolean requestedSessionIdFromUrl;
    @Override public HttpSession getSession(boolean create) {
        if (currentSession != null && !currentSession.isInvalidated()) return currentSession;
        if (sessionManager == null) {
            if (create) throw new IllegalStateException("no SessionManager bound");
            return null;
        }
        String id = getRequestedSessionId();
        // Only the requested session is looked up, and once: after it was invalidated in this
        // request, only a new one can be bound.
        HttpSessionImpl existing = id == null || currentSession != null ? null : sessionManager.find(id);
        if (existing != null) {
            accessedSessions.add(existing);
            currentSession = existing;
            return existing;
        }
        if (!create) return null;
        HttpSessionImpl created = sessionManager.createNew();
        created.beginAccess();
        accessedSessions.add(created);
        currentSession = created;
        return created;
    }
    @Override public HttpSession getSession() { return getSession(true); }
    @Override public String changeSessionId() {
        if (!(getSession(false) instanceof HttpSessionImpl s)) throw new IllegalStateException("no session");
        return sessionManager.changeSessionId(s);
    }
    @Override public boolean isRequestedSessionIdValid() {
        String id = getRequestedSessionId();
        if (id == null || sessionManager == null) return false;
        if (currentSession != null) return !currentSession.isInvalidated() && id.equals(currentSession.getId());
        return sessionManager.peek(id) != null;
    }
    @Override public boolean isRequestedSessionIdFromCookie() {
        return getRequestedSessionId() != null && !requestedSessionIdFromUrl;
    }
    @Override public boolean isRequestedSessionIdFromURL() {
        return getRequestedSessionId() != null && requestedSessionIdFromUrl;
    }

    private String extractSessionIdFromCookies() {
        Cookie[] cookies = getCookies();
        if (cookies == null) return null;
        String name = servletContext instanceof VidocqServletContext v
                ? v.sessionCookieConfigInternal().getName() : SessionManager.COOKIE_NAME;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    public HttpSessionImpl boundSession() { return currentSession; }

    /**
     * Section 7.6: "the session is considered to be accessed when a request that is part of the
     * session is first handled by the container". Called by the bridge once the request is set
     * up, before any filter or servlet runs: the session the client named (if valid) begins an
     * access now, whether or not the application calls {@code getSession}, so that the end of this
     * request becomes its last-accessed time and it cannot expire while the request runs.
     */
    public void accessRequestedSession() {
        if (sessionManager != null && currentSession == null && getRequestedSessionId() != null) {
            getSession(false);
        }
    }

    /** Sessions this request began an access to; ended by {@link #endSessionAccess()}. */
    private final java.util.List<HttpSessionImpl> accessedSessions = new java.util.ArrayList<>(1);

    /**
     * End of request processing: ends the access of every session this request used, so that
     * its end becomes their last-accessed time and they may expire again.
     */
    public void endSessionAccess() {
        for (HttpSessionImpl s : accessedSessions) s.endAccess();
        accessedSessions.clear();
    }

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
        if (servletContext instanceof VidocqServletContext v) {
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
    /**
     * Server push is not supported: chappe emits no PUSH_PROMISE frame, and push is deprecated in
     * Servlet 6.1 in favour of 103 Early Hints. Returning {@code null} is the contract for
     * "push unavailable" (HTTP/1.x, push disabled by the peer, or no support in the container).
     *
     * @return always {@code null}
     */
    @Override public jakarta.servlet.http.PushBuilder newPushBuilder() {
        return null;
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
    /**
     * The cycle that ended with an ASYNC dispatch, kept until the dispatched target either opens a
     * new cycle ({@code onStartAsync} on its listeners) or returns (the bridge ends it).
     */
    private io.vidocq.foy.internal.async.AsyncContextImpl previousAsyncContext;

    /** Resets async state, used by the bridge between two async dispatches so
     *  startAsync in a redispatched servlet creates a new context. */
    public void clearAsyncContext() {
        this.previousAsyncContext = asyncContext;
        this.asyncContext = null;
    }

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
        var started = new io.vidocq.foy.internal.async.AsyncContextImpl(req, res, servletContext, original);
        this.asyncContext = started;
        // A new cycle may write the response from any thread again; a write failing because the
        // client is gone fails the cycle (onError).
        if (boundResponse instanceof HttpServletResponseImpl impl) impl.openOutput(started::fail);
        // Section 2.3.3.3: startAsync after an ASYNC dispatch fires onStartAsync on the previous
        // cycle's listeners, which are not carried over.
        var previous = previousAsyncContext;
        previousAsyncContext = null;
        if (previous != null) previous.handOverTo(started);
        return started;
    }
    @Override public boolean isAsyncStarted() {
        // §2.3.3.3 : true tant que le servlet (ou son dispatch) est encore en cours —
        // reste true après un complete() pendant la fin du service(). Le flag bascule
        // à false dès qu'un dispatch est planifié (le request original cède sa place
        // au servlet redispatched, cf. TCK asyncStartedTest4).
        // Once the container completed a timed-out or failed cycle (error dispatch), async is over.
        return asyncContext != null && !asyncContext.hasDispatch() && !asyncContext.containerCompleted();
    }
    @Override public boolean isAsyncSupported() { return asyncSupported; }
    @Override public AsyncContext getAsyncContext() {
        if (asyncContext == null) throw new IllegalStateException("no async context");
        return asyncContext;
    }
    /**
     * The connection as Chappe describes it. Chappe exposes no connection object, so the
     * connection id is the pair of socket addresses: the same for every request of one connection
     * and distinct between open connections; a later connection may reuse a closed one's ephemeral
     * port, hence its id (BUG-20261009-09). The protocol is the ALPN identifier ({@code http/1.0}, {@code http/1.1},
     * {@code h2} over TLS, {@code h2c} in clear text); no HTTP/1.x or HTTP/2 connection id is
     * exposed ({@code getProtocolConnectionId()} is {@code ""}).
     */
    @Override public ServletConnection getServletConnection() {
        var remote = chappe.remoteAddress();
        var local = chappe.localAddress();
        String id = endpoint(remote) + "-" + endpoint(local);
        String protocol = switch (chappe.version()) {
            case HTTP_1_0 -> "http/1.0";
            case HTTP_1_1 -> "http/1.1";
            case HTTP_2 -> chappe.isSecure() ? "h2" : "h2c";
        };
        return new Connection(id, protocol, chappe.isSecure());
    }

    private static String endpoint(java.net.InetSocketAddress a) {
        if (a == null) return "?";
        String host = a.getAddress() != null ? a.getAddress().getHostAddress() : a.getHostString();
        return host + ":" + a.getPort();
    }

    /** Immutable {@link ServletConnection} snapshot. */
    private record Connection(String id, String protocol, boolean secure) implements ServletConnection {
        @Override public String getConnectionId() { return id; }
        @Override public String getProtocol() { return protocol; }
        @Override public String getProtocolConnectionId() { return ""; }
        @Override public boolean isSecure() { return secure; }
    }
}
