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

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@link HttpServletResponse} which accumulates the state (status, headers, body) and materializes
 * in {@link io.vidocq.chappe.api.Response Response} Immutable trap at the end of dispatch.
 */
public final class HttpServletResponseImpl implements HttpServletResponse {

    private int status = 200;
    private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final List<Cookie> cookies = new ArrayList<>();
    private String contentType;
    private String characterEncoding;
    /** Context default response encoding, used only while the application has set none. */
    private String defaultCharacterEncoding;
    private Locale locale = Locale.getDefault();
    private final ServletOutputStreamImpl outputStream = new ServletOutputStreamImpl();
    /** True while the response itself drains the writer into the buffer: that is not a commit. */
    private boolean internalFlush;
    { outputStream.setFlushListener(() -> { if (!internalFlush) committed = true; }); }
    private PrintWriter writer;
    private boolean streamAcquired;
    private boolean committed;
    /** Declared Content-Length, or -1 when none. */
    private long contentLength = -1;
    private boolean errorTriggered;
    private String errorMessage;

    // ---- Status ----

    @Override public int getStatus() { return status; }
    @Override public void setStatus(int sc) {
        if (isCommitted()) return;
        this.status = sc;
    }
    @Override public void sendError(int sc, String msg) throws IOException {
        if (isCommitted()) throw new IllegalStateException("response already committed");
        setStatus(sc);
        this.errorTriggered = true;
        this.errorMessage = msg;
        // Servlet 6.1 §5.8: sendError clears the buffer, so whatever the servlet
        // wrote before is discarded. The default message is then written as raw bytes
        // directly into the internal buffer to avoid the getWriter()/getOutputStream() conflict.
        clearContentLength();
        outputStream.resetBuffer();
        writer = null;
        streamAcquired = false;
        // Par convention des conteneurs servlet, sendError renvoie une page d'erreur HTML
        // minimaliste qui inclut le status + le message — cf. Tomcat/Jetty ErrorPages.
        setContentType("text/html");
        String safeMsg = msg == null ? "" : msg;
        String body = "<html><head><title>HTTP Error " + sc + "</title></head><body>"
                + "<h1>HTTP Status " + sc + " - " + safeMsg + "</h1></body></html>";
        outputStream.write(body.getBytes(charset()));
        committed = true;
        outputStream.setDiscarding(true);
    }
    @Override public void sendError(int sc) throws IOException { sendError(sc, null); }

    /**
     * Servlet 6.1 section 9.4: once a forward returns, the response is committed and closed; the
     * buffered content stands and any later write is discarded. No-op when already committed.
     */
    public void closeAfterForward() {
        if (isCommitted()) return;
        drainWriter();
        committed = true;
        outputStream.setDiscarding(true);
    }

    public boolean isErrorTriggered() { return errorTriggered; }
    public String errorMessage() { return errorMessage; }
    public void clearErrorState() {
        this.errorTriggered = false;
        this.errorMessage = null;
        this.committed = false;
        outputStream.setDiscarding(false);
    }
    @Override public void sendRedirect(String location) throws IOException {
        sendRedirect(location, SC_FOUND, true);
    }
    @Override public void sendRedirect(String location, int sc, boolean clearBuffer) throws IOException {
        if (isCommitted()) throw new IllegalStateException("response already committed");
        if (clearBuffer) {
            clearContentLength();
            resetBuffer();
        } else {
            drainWriter();
        }
        setStatus(sc);
        setHeader("Location", toAbsoluteRedirectUrl(location));
        // The response is closed: the redirect is committed and any later write is discarded.
        committed = true;
        outputStream.setDiscarding(true);
    }

    /** Servlet 6.1 §5.8.2 — sendRedirect must produce an absolute URL. */
    private String toAbsoluteRedirectUrl(String location) {
        if (location == null) return null;
        if (boundRequest == null) return location;
        String scheme = boundRequest.getScheme();
        String host = boundRequest.getServerName();
        int port = boundRequest.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        var origin = new StringBuilder(scheme).append("://").append(host);
        if (!defaultPort) origin.append(':').append(port);
        String requestUri = boundRequest.getRequestURI();
        if (requestUri == null || requestUri.isEmpty()) requestUri = "/";
        try {
            java.net.URI ref = new java.net.URI(location);
            // Already absolute (any scheme).
            if (ref.isAbsolute()) return location;
            java.net.URI base = new java.net.URI(origin + requestUri);
            if (location.startsWith("?")) {
                // Query-only reference: keep the request path, replace the query.
                return origin + requestUri + location;
            }
            // RFC 3986 resolution: a leading '/' is relative to the server root, anything else to
            // the directory of the request URI, with "." and ".." segments removed.
            return base.resolve(ref).toString();
        } catch (java.net.URISyntaxException e) {
            // Not a valid URI reference (e.g. unescaped characters): keep the simple concatenation.
            if (location.startsWith("/")) return origin + location;
            int slash = requestUri.lastIndexOf('/');
            return origin + requestUri.substring(0, slash + 1) + location;
        }
    }

    private jakarta.servlet.http.HttpServletRequest boundRequest;
    public void bindRequest(jakarta.servlet.http.HttpServletRequest req) { this.boundRequest = req; }

    // ---- Headers ----

    @Override public void setHeader(String name, String value) {
        if (isCommitted()) return;
        putHeader(name, value);
    }
    @Override public void addHeader(String name, String value) {
        if (isCommitted()) return;
        appendHeader(name, value);
    }

    /**
     * Bridge-internal header append which, unlike the public API, is not blocked by commit.
     * Used for the session cookie, which the buffered model can still attach after the servlet
     * flushed. When Phase 5 introduces real streaming, this header must be emitted before the
     * first flush instead.
     */
    void addHeaderInternal(String name, String value) { appendHeader(name, value); }

    private void putHeader(String name, String value) {
        List<String> list = new ArrayList<>();
        list.add(value);
        headers.put(name, list);
        interceptSpecialHeader(name, value);
    }
    private void appendHeader(String name, String value) {
        headers.computeIfAbsent(name, _ -> new ArrayList<>()).add(value);
        interceptSpecialHeader(name, value);
    }
    @Override public void setIntHeader(String name, int value) { setHeader(name, Integer.toString(value)); }
    @Override public void addIntHeader(String name, int value) { addHeader(name, Integer.toString(value)); }
    @Override public void setDateHeader(String name, long date) {
        setHeader(name, formatHttpDate(date));
    }
    @Override public void addDateHeader(String name, long date) {
        addHeader(name, formatHttpDate(date));
    }

    /** RFC 7231 §7.1.1.1 — IMF-fixdate: "Sun, 06 Nov 1994 08:49:37 GMT". */
    private static String formatHttpDate(long dateMillis) {
        return io.vidocq.foy.internal.http.CookieCodec.formatImfFixdate(java.time.Instant.ofEpochMilli(dateMillis));
    }
    @Override public boolean containsHeader(String name) { return headers.containsKey(name); }
    @Override public String getHeader(String name) {
        List<String> list = headers.get(name);
        return list == null || list.isEmpty() ? null : list.get(0);
    }
    @Override public Collection<String> getHeaders(String name) {
        List<String> list = headers.get(name);
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }
    @Override public Collection<String> getHeaderNames() { return new HashSet<>(headers.keySet()); }

    private void interceptSpecialHeader(String name, String value) {
        if ("Content-Length".equalsIgnoreCase(name)) {
            try { this.contentLength = Long.parseLong(value.trim()); }
            catch (RuntimeException e) { this.contentLength = -1; }
        }
        if ("Content-Type".equalsIgnoreCase(name)) {
            this.contentType = value;
            int idx = value == null ? -1 : value.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (idx >= 0) {
                this.characterEncoding = value.substring(idx + 8).trim();
            }
        }
    }

    // ---- Cookies ----

    @Override public void addCookie(Cookie cookie) {
        if (isCommitted()) return;
        cookies.add(cookie);
    }
    public List<Cookie> cookies() { return cookies; }

    // ---- Content-Type / charset ----

    /** "Raw" MIME type (without the charset) derived from setContentType. */
    private String mediaType;
    private boolean charsetExplicit;
    /** The charset is locked after getWriter() (Servlet 6.1 §5.4). */
    private boolean charsetLocked;

    @Override public String getContentType() {
        if (contentType == null) return null;
        String enc = configuredEncoding();
        if (contentType.toLowerCase(Locale.ROOT).contains("charset=") || enc == null) {
            return contentType;
        }
        return mediaType + ";charset=" + enc;
    }
    @Override public void setContentType(String type) {
        // Servlet 6.1 §5.4: setContentType is silently ignored once the response is committed.
        if (isCommitted()) return;
        this.contentType = type;
        if (type == null) return;
        int idx = type.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (idx >= 0) {
            this.mediaType = type.substring(0, idx).replaceAll(";\\s*$", "").trim();
            // The charset of the content type is only accepted while not yet locked by getWriter().
            if (!charsetLocked) {
                this.characterEncoding = type.substring(idx + 8).trim();
                this.charsetExplicit = true;
            }
        } else {
            this.mediaType = type;
        }
        refreshContentTypeHeader();
    }
    public void setDefaultCharacterEncoding(String encoding) { this.defaultCharacterEncoding = encoding; }

    /** The encoding set by the application or configured for the context, or {@code null} when neither. */
    private String configuredEncoding() {
        return characterEncoding != null ? characterEncoding : defaultCharacterEncoding;
    }

    @Override public String getCharacterEncoding() {
        // ServletResponse#getCharacterEncoding: ISO-8859-1 when nothing was assigned. The emitted
        // Content-Type header only carries a charset that was actually set (see configuredEncoding()).
        String enc = configuredEncoding();
        return enc != null ? enc : "ISO-8859-1";
    }
    @Override public void setCharacterEncoding(String charset) {
        if (isCommitted() || charsetLocked) return;
        this.characterEncoding = charset;
        this.charsetExplicit = (charset != null);
        refreshContentTypeHeader();
    }
    @Override public void setCharacterEncoding(Charset encoding) {
        if (isCommitted() || charsetLocked) return;
        this.characterEncoding = encoding == null ? null : encoding.name();
        this.charsetExplicit = (encoding != null);
        refreshContentTypeHeader();
    }

    /** Recalculates the {@code Content-Type} header by combining mediaType + charset. */
    private void refreshContentTypeHeader() {
        if (mediaType == null) return;
        // For text/* types the charset is always included (explicit or default
        // "ISO-8859-1", cf. Servlet 6.1 §5.4) afin que le header Content-Type final
        // reflects the encoding actually used by getWriter().
        boolean isText = mediaType.toLowerCase(Locale.ROOT).startsWith("text/");
        String enc = configuredEncoding() != null ? configuredEncoding()
                : (isText ? "ISO-8859-1" : null);
        String composed = enc != null ? mediaType + ";charset=" + enc : mediaType;
        List<String> list = new ArrayList<>();
        list.add(composed);
        headers.put("Content-Type", list);
        this.contentType = composed;
    }
    @Override public void setContentLength(int len) { setIntHeader("Content-Length", len); }
    @Override public void setContentLengthLong(long len) { setHeader("Content-Length", Long.toString(len)); }

    // ---- Body ----

    @Override public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) throw new IllegalStateException("getWriter() already called");
        streamAcquired = true;
        return outputStream;
    }
    @Override public PrintWriter getWriter() throws IOException {
        if (streamAcquired) throw new IllegalStateException("getOutputStream() already called");
        if (writer == null) {
            // Servlet 6.1 §5.4: an explicitly set charset which the JVM does not support
            // makes getWriter throw UnsupportedEncodingException.
            if (characterEncoding != null && !Charset.isSupported(characterEncoding)) {
                throw new java.io.UnsupportedEncodingException(characterEncoding);
            }
            // Resolve the charset (ISO-8859-1 by default) and lock it: the state now
            // reflects the charset actually used to write the body.
            if (characterEncoding == null) {
                characterEncoding = defaultCharacterEncoding != null ? defaultCharacterEncoding : "ISO-8859-1";
            }
            charsetLocked = true;
            writer = new PrintWriter(new java.io.OutputStreamWriter(outputStream, charset()), false);
            refreshContentTypeHeader();
        }
        return writer;
    }

    private Charset charset() {
        String enc = getCharacterEncoding();
        try { return Charset.forName(enc); }
        catch (RuntimeException e) { return StandardCharsets.ISO_8859_1; }
    }

    // ---- Buffer / commit ----

    // Nominal buffer size exposed to the servlet: everything is buffered in memory,
    // so the effective capacity is unbounded, but a usual value is exposed
    // (8 KiB) conforme aux attentes des tests TCK.
    private int bufferSize = 8192;
    @Override public void setBufferSize(int size) {
        if (outputStream.size() > 0) throw new IllegalStateException("content already written");
        this.bufferSize = size;
    }
    @Override public int getBufferSize() { return bufferSize; }
    @Override public void flushBuffer() {
        if (writer != null) writer.flush();
        committed = true;
    }
    /** Drops the declared Content-Length, which no longer describes a cleared body. */
    private void clearContentLength() {
        contentLength = -1;
        headers.remove("Content-Length");
    }

    @Override public void resetBuffer() {
        if (isCommitted()) throw new IllegalStateException("committed");
        outputStream.resetBuffer();
        writer = null;
        streamAcquired = false;
    }
    @Override public boolean isCommitted() {
        // Servlet 6.1 §5.1/§5.2 (setContentLength Javadoc): once the amount of content written
        // reaches the declared content length, the response is committed and closed.
        if (!committed && contentLength >= 0) {
            drainWriter();
            if (outputStream.size() > 0 && outputStream.size() >= contentLength) committed = true;
        }
        return committed;
    }
    @Override public void reset() {
        if (isCommitted()) throw new IllegalStateException("committed");
        status = 200;
        contentLength = -1;
        headers.clear();
        cookies.clear();
        contentType = null;
        characterEncoding = null;
        mediaType = null;
        charsetExplicit = false;
        charsetLocked = false;
        outputStream.resetBuffer();
        writer = null;
        streamAcquired = false;
    }
    @Override public void setLocale(Locale loc) {
        if (isCommitted() || loc == null) return;
        this.locale = loc;
        // Servlet 6.1 §5.4: setLocale sets Content-Language (BCP 47 tag).
        setHeader("Content-Language", loc.toLanguageTag());
        // If the charset is not explicit, resolve it through the web.xml locale-encoding-mapping-list.
        if (!charsetExplicit && !charsetLocked && boundRequest != null
                && boundRequest.getServletContext() instanceof
                io.vidocq.foy.internal.container.VidocqServletContext vctx) {
            String enc = vctx.encodingForLocale(loc);
            if (enc != null) {
                this.characterEncoding = enc;
                refreshContentTypeHeader();
            }
        }
    }
    @Override public Locale getLocale() { return locale; }

    // ---- URL encoding ----

    @Override public String encodeURL(String url) { return url; }
    @Override public String encodeRedirectURL(String url) { return url; }

    // ---- Internal access for the Chappe bridge ----

    private void drainWriter() {
        if (writer == null) return;
        internalFlush = true;
        try { writer.flush(); } finally { internalFlush = false; }
    }

    public byte[] bodyBytes() {
        drainWriter();
        byte[] all = outputStream.toByteArray();
        // Content beyond the declared Content-Length is never sent.
        if (contentLength >= 0 && all.length > contentLength) {
            return java.util.Arrays.copyOf(all, (int) contentLength);
        }
        return all;
    }

    public Map<String, List<String>> allHeaders() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (var e : headers.entrySet()) out.put(e.getKey(), List.copyOf(e.getValue()));
        for (Cookie c : cookies) {
            out.computeIfAbsent("Set-Cookie", _ -> new ArrayList<>())
                    .add(io.vidocq.foy.internal.http.CookieCodec.serializeSetCookie(c));
        }
        return out;
    }

    public Set<String> headerNames() {
        return new HashSet<>(headers.keySet());
    }
}
