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

import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.http.CookieCodec;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * {@link HttpServletResponse} which accumulates the state (status, headers, body) up to its first
 * commit. A response that never commits is materialised as one immutable
 * {@link io.vidocq.chappe.api.Response Response} at the end of the request; a response committed
 * earlier (buffer overflow, flush) hands its head to chappe through the commit target bound by the
 * bridge ({@link #bindCommitTarget}) and streams the rest of its body through a {@link ResponsePipe}.
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
    { outputStream.setOwner(new StreamOwner()); }
    private PrintWriter writer;
    private boolean streamAcquired;
    /** Committed as the application sees it ({@link #isCommitted()}): status and headers are frozen. */
    private volatile boolean committed;
    /** Committed for real: the head was handed to chappe and the body is live. Implies {@link #committed}. */
    private volatile boolean headSent;
    /** Receives the response at its first real commit; {@code null} for a detached response (unit tests). */
    private CommitTarget onCommit;
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
        // Kept in the buffer whatever its size: an error page may still replace it.
        outputStream.writeBuffered(body.getBytes(charset()));
        committed = true;
        outputStream.setDiscarding(true);
    }
    @Override public void sendError(int sc) throws IOException { sendError(sc, null); }

    /**
     * Servlet 6.1 section 9.4: once a forward returns, the response is committed and closed; the
     * buffered content stands and any later write is discarded, also when the target had already
     * committed (flushed) the response.
     */
    public void closeAfterForward() {
        drainWriter();
        committed = true;
        outputStream.setDiscarding(true);
    }

    public boolean isErrorTriggered() { return errorTriggered; }
    public String errorMessage() { return errorMessage; }
    public void clearErrorState() {
        this.errorTriggered = false;
        this.errorMessage = null;
        // A response whose head is on the wire stays committed.
        this.committed = headSent;
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
        setHeader("Location", toAbsoluteRedirectUrl(location == null ? null : encodeLocation(location)));
        // The response is closed: the redirect is committed and any later write is discarded.
        committed = true;
        outputStream.setDiscarding(true);
    }

    /** The characters a Location URI reference keeps as they are: RFC 3986 unreserved, reserved and '%'. */
    private static final String LOCATION_PLAIN = "-._~:/?#[]@!$&'()*+,;=%";

    /**
     * Percent-encodes (UTF-8) every character of a redirect location that is not an RFC 3986
     * unreserved or reserved character or {@code '%'}: non-ASCII characters, spaces and controls,
     * CR and LF included. The location therefore never carries a line break, nor a character that a
     * byte-per-char header writer would turn into one (U+010D U+010A become CR LF once truncated).
     * Existing percent escapes are kept.
     */
    static String encodeLocation(String location) {
        StringBuilder out = null;
        for (int i = 0; i < location.length(); i++) {
            char c = location.charAt(i);
            boolean plain = c < 0x80 && ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || LOCATION_PLAIN.indexOf(c) >= 0);
            if (plain) {
                if (out != null) out.append(c);
                continue;
            }
            if (out == null) out = new StringBuilder(location.length() + 16).append(location, 0, i);
            int end = Character.isHighSurrogate(c) && i + 1 < location.length()
                    && Character.isLowSurrogate(location.charAt(i + 1)) ? i + 2 : i + 1;
            for (byte b : location.substring(i, end).getBytes(StandardCharsets.UTF_8)) {
                out.append('%').append(HEX.charAt((b >> 4) & 15)).append(HEX.charAt(b & 15));
            }
            i = end - 1;
        }
        return out == null ? location : out.toString();
    }

    private static final String HEX = "0123456789ABCDEF";

    /**
     * Rejects a header name or value that could split the header block or be mangled on the wire:
     * CR, LF, NUL, or any character above U+00FF (the HTTP/1.1 writer emits one byte per char, so
     * U+010D U+010A would become CR LF). Common container practice is an
     * {@link IllegalArgumentException}, which Foy follows for every header entry point.
     */
    static void checkHeaderText(String what, String text) {
        if (text == null) return;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n' || c == 0 || c > 0xff) {
                throw new IllegalArgumentException("illegal character U+" + String.format("%04X", (int) c)
                        + " in header " + what);
            }
        }
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
            URI ref = new URI(location);
            // Already absolute (any scheme).
            if (ref.isAbsolute()) return location;
            URI base = new URI(origin + requestUri);
            if (location.startsWith("?")) {
                // Query-only reference: keep the request path, replace the query.
                return origin + requestUri + location;
            }
            // RFC 3986 resolution: a leading '/' is relative to the server root, anything else to
            // the directory of the request URI, with "." and ".." segments removed.
            return base.resolve(ref).toString();
        } catch (URISyntaxException e) {
            // Not a valid URI reference (e.g. unescaped characters): keep the simple concatenation.
            if (location.startsWith("/")) return origin + location;
            int slash = requestUri.lastIndexOf('/');
            return origin + requestUri.substring(0, slash + 1) + location;
        }
    }

    private HttpServletRequest boundRequest;
    public void bindRequest(HttpServletRequest req) {
        this.boundRequest = req;
        // Non-blocking output (WriteListener) follows the request's async state and callbacks.
        if (req instanceof HttpServletRequestImpl impl) outputStream.setHost(impl.nonBlockingHost());
    }

    // ---- Trailer fields ----

    private volatile Supplier<Map<String, String>> trailerFields;

    /**
     * Servlet 6.1 §5.3: trailers need a chunked HTTP/1.1 body or HTTP/2. Refused once committed,
     * on HTTP/1.0, and on HTTP/1.1 when a {@code Content-Length} was declared. The supplier is
     * evaluated by chappe once the body is complete ({@link FoyResponse#trailers()}).
     */
    @Override
    public void setTrailerFields(Supplier<Map<String, String>> supplier) {
        if (isCommitted()) throw new IllegalStateException("response already committed");
        String protocol = boundRequest == null ? null : boundRequest.getProtocol();
        if ("HTTP/1.0".equals(protocol) || "HTTP/0.9".equals(protocol)) {
            throw new IllegalStateException("trailer fields are not supported on " + protocol);
        }
        if (contentLength >= 0 && !"HTTP/2".equals(protocol)) {
            throw new IllegalStateException("trailer fields need a chunked response, but a Content-Length was declared");
        }
        this.trailerFields = supplier;
    }

    @Override
    public Supplier<Map<String, String>> getTrailerFields() { return trailerFields; }

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
     * Used for the session cookie, which the bridge attaches at the first real commit (before the
     * head is handed to chappe) or at the end of a request that never committed.
     */
    void addHeaderInternal(String name, String value) { appendHeader(name, value); }

    private void putHeader(String name, String value) {
        checkHeader(name, value);
        List<String> list = new ArrayList<>();
        list.add(value);
        headers.put(name, list);
        interceptSpecialHeader(name, value);
    }
    private void appendHeader(String name, String value) {
        checkHeader(name, value);
        headers.computeIfAbsent(name, _ -> new ArrayList<>()).add(value);
        interceptSpecialHeader(name, value);
    }
    private static void checkHeader(String name, String value) {
        checkHeaderText("name", name);
        checkHeaderText(name, value);
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
        return CookieCodec.formatImfFixdate(Instant.ofEpochMilli(dateMillis));
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
            // Declared after the content was written: the response is complete already.
            if (contentLength >= 0 && outputStream.hasContent() && outputStream.written() >= contentLength) {
                committed = true;
            }
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
        CookieCodec.checkSerializable(cookie);
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
        checkHeaderText("Content-Type", type);
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
        checkHeaderText("Content-Type", charset);
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
                throw new UnsupportedEncodingException(characterEncoding);
            }
            // Resolve the charset (ISO-8859-1 by default) and lock it: the state now
            // reflects the charset actually used to write the body.
            if (characterEncoding == null) {
                characterEncoding = defaultCharacterEncoding != null ? defaultCharacterEncoding : "ISO-8859-1";
            }
            charsetLocked = true;
            writer = new ResponseWriter(new OutputStreamWriter(outputStream, charset()));
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

    /** The minimum capacity of the pipe that carries a committed body to chappe. */
    private static final int MIN_PIPE_CAPACITY = 8192;

    /**
     * The buffer threshold: content up to this size stays buffered (and can still be reset); the
     * write that would exceed it commits the response and the body goes to the client live.
     */
    private int bufferSize = 8192;

    /**
     * Sets the buffer threshold. A negative size is clamped to 0 (every write then commits), so
     * {@link #getBufferSize()} always reports the threshold actually enforced.
     */
    @Override public void setBufferSize(int size) {
        if (outputStream.hasContent() || headSent) throw new IllegalStateException("content already written");
        this.bufferSize = Math.max(0, size);
        outputStream.setLimit(bufferSize);
    }
    @Override public int getBufferSize() { return bufferSize; }
    @Override public void flushBuffer() throws IOException {
        var lock = outputStream.lock();
        lock.lock();
        try {
            if (!outputStream.writableByCurrentThread()) {
                throw new IOException("the async cycle ended: this thread may no longer flush the response");
            }
            drainWriter();
            flushToClient();
        } finally {
            lock.unlock();
        }
    }

    /**
     * An application flush: commits the response for real and pushes the buffered content to the
     * client. A response the container closed (sendError, sendRedirect, end of a forward) is only
     * committed logically: its buffered content is sent whole at the end of the request, so an
     * error page can still replace a sendError body.
     */
    private void flushToClient() throws IOException {
        if (!headSent) {
            if (outputStream.isDiscarding()) {
                committed = true;
                return;
            }
            commit();
        }
        outputStream.push();
    }

    /**
     * The single commit point. Status, headers and cookies are frozen from here on; when the
     * bridge bound a commit target, the response head is handed to chappe at once (the session
     * cookie included, attached by the target) and the body becomes live. A detached response
     * only records the commit. A target failure reaches the writer as an {@link IOException}.
     */
    void commit() throws IOException {
        if (headSent) return;
        committed = true;
        if (onCommit == null) return;
        headSent = true;
        onCommit.commit(this);
    }

    /** Receives the response at its first real commit and hands its head to chappe. */
    @FunctionalInterface
    interface CommitTarget {
        /**
         * Hands the head of {@code res} over. On failure the target settles the head itself
         * (chappe answers a plain 500) and throws, so the committing write fails.
         */
        void commit(HttpServletResponseImpl res) throws IOException;
    }

    /** Called by the bridge: {@code target} receives this response at its first real commit. */
    void bindCommitTarget(CommitTarget target) {
        this.onCommit = target;
    }

    /** Whether the head was handed to chappe at a commit: the body is then live. */
    boolean isStreaming() { return headSent; }

    /** The declared Content-Length, or -1. */
    long declaredContentLength() { return contentLength; }

    /** Connects the live body to a new pipe (called by the commit target) and returns it. */
    ResponsePipe startStreaming() {
        var pipe = new ResponsePipe(Math.max(MIN_PIPE_CAPACITY, bufferSize));
        outputStream.streamTo(pipe);
        return pipe;
    }

    /** The committed response carries no body on the wire (HEAD, 204, 304): drop every byte. */
    void suppressBody() { outputStream.suppress(); }

    /** End of the request for a live body: what is left is pushed, then the client sees EOF. */
    void finishBody() throws IOException {
        var lock = outputStream.lock();
        lock.lock();
        try {
            drainWriter();
            outputStream.finish();
        } finally {
            lock.unlock();
        }
    }

    /** Abnormal end of a live body: chappe drops the connection instead of ending the body. */
    void abortBody(Throwable cause) { outputStream.abort(cause); }
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
        // Side-effect free: commits happen where bytes are written (overflow, flush, declared
        // Content-Length reached — see StreamOwner) or where the response is closed.
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
                VidocqServletContext vctx) {
            String enc = vctx.encodingForLocale(loc);
            if (enc != null) {
                this.characterEncoding = enc;
                refreshContentTypeHeader();
            }
        }
    }
    @Override public Locale getLocale() { return locale; }

    // ---- URL encoding ----

    /**
     * URL rewriting (Servlet 6.1 section 7.1.3): appends {@code ;jsessionid=<id>} to the path of
     * {@code url} when the effective tracking modes contain {@code URL}, the request has a
     * session, the client does not already send its id in a cookie, and {@code url} targets this
     * application (a relative URL, or one whose path is under the context path and, when
     * absolute, on the request's scheme, host and port). Otherwise {@code url} is unchanged.
     */
    @Override public String encodeURL(String url) {
        if (url == null || boundRequest == null) return url;
        var ctx = boundRequest.getServletContext();
        if (ctx == null || !ctx.getEffectiveSessionTrackingModes()
                .contains(SessionTrackingMode.URL)) return url;
        var session = boundRequest.getSession(false);
        if (session == null || boundRequest.isRequestedSessionIdFromCookie()) return url;
        if (!targetsThisApplication(url)) return url;
        int end = url.length();
        int q = url.indexOf('?');
        if (q >= 0) end = q;
        int f = url.indexOf('#');
        if (f >= 0 && f < end) end = f;
        return url.substring(0, end) + ";jsessionid=" + session.getId() + url.substring(end);
    }

    /** Same rule as {@link #encodeURL}: Foy does not distinguish redirect targets. */
    @Override public String encodeRedirectURL(String url) { return encodeURL(url); }

    /** An RFC 3986 scheme followed by ':' at the start of a URL. */
    private static final Pattern SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*:");

    private boolean targetsThisApplication(String url) {
        if (url.isEmpty() || url.startsWith("#") || url.contains(";jsessionid=")) return false;
        String path;
        boolean absolute = url.startsWith("//") || SCHEME.matcher(url).lookingAt();
        if (absolute) {
            URI uri;
            try {
                uri = new URI(url.startsWith("//") ? boundRequest.getScheme() + ":" + url : url);
            } catch (URISyntaxException e) {
                return false;
            }
            if (uri.isOpaque() || uri.getHost() == null) return false;
            String scheme = uri.getScheme();
            if (!scheme.equalsIgnoreCase(boundRequest.getScheme())) return false;
            if (!uri.getHost().equalsIgnoreCase(boundRequest.getServerName())) return false;
            int port = uri.getPort() >= 0 ? uri.getPort()
                    : "https".equalsIgnoreCase(scheme) ? 443 : "http".equalsIgnoreCase(scheme) ? 80 : -1;
            if (port != boundRequest.getServerPort()) return false;
            path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        } else if (url.startsWith("/")) {
            path = url;
        } else {
            return true; // relative to the current request: same application
        }
        int end = path.length();
        for (char c : new char[] {'?', '#', ';'}) {
            int i = path.indexOf(c);
            if (i >= 0 && i < end) end = i;
        }
        path = path.substring(0, end);
        String cp = boundRequest.getContextPath();
        return cp == null || cp.isEmpty() || path.equals(cp) || path.startsWith(cp + "/");
    }

    // ---- Internal access for the Chappe bridge ----

    private void drainWriter() {
        if (writer == null) return;
        var lock = outputStream.lock();
        lock.lock();
        try {
            internalFlush = true;
            try { writer.flush(); } finally { internalFlush = false; }
        } finally {
            lock.unlock();
        }
    }

    // ---- Async cycles and threads (BUG-20261010-01) ----

    /** Fails the current async cycle when a pipe write fails; {@code null} outside a cycle. */
    private volatile Consumer<? super IOException> asyncWriteFailure;

    /**
     * A new async cycle started: any thread may write the response again, and a write failing
     * because the client is gone is reported to {@code writeFailure} (the cycle's {@code onError}).
     */
    void openOutput(Consumer<? super IOException> writeFailure) {
        this.asyncWriteFailure = writeFailure;
        outputStream.open();
    }

    /**
     * The async cycle ended and the pipeline thread (the caller) resumes: from now on only it may
     * write, flush or close the response. A write from any other thread, typically an async thread
     * still running after a timeout, fails with an {@link IOException} on the output stream, and is
     * discarded with {@link PrintWriter#checkError()} set on the writer. A write already in progress
     * completes under the stream's lock, which every output operation of the pipeline thread takes
     * too; the claim itself does not wait for it, so a writer blocked on a slow client cannot delay
     * an abort of the body.
     */
    void claimOutput() {
        claimOutput(List.of());
    }

    /**
     * {@link #claimOutput()}, and the threads {@code cycleThreads} (those the ended cycle's
     * {@code AsyncContext.start} created) are refused for good, even after a new cycle re-opens the
     * output with {@link #openOutput}.
     */
    void claimOutput(Collection<Thread> cycleThreads) {
        this.asyncWriteFailure = null;
        outputStream.claim(cycleThreads);
        // The cycle that allowed a WriteListener is over: the output is blocking again.
        outputStream.endNonBlocking();
    }

    /** The response side of {@link ServletOutputStreamImpl}. */
    private final class StreamOwner implements ServletOutputStreamImpl.Owner {
        @Override public long declaredLength() { return contentLength; }
        @Override public void overflow() throws IOException { commit(); }
        @Override public void flushRequested() throws IOException {
            if (!internalFlush) flushToClient();
        }
        @Override public void writeFailed(IOException failure) {
            var sink = asyncWriteFailure;
            if (sink != null) sink.accept(failure);
        }
        @Override public void contentLengthReached() throws IOException {
            // Servlet 6.1 section 5.6: the declared amount of content is written, the response is
            // committed and closed. A live body sends it at once; a buffered one at the end.
            committed = true;
            if (headSent) outputStream.push();
        }
    }

    /**
     * The response writer. The encoder's own buffer is drained into the output stream after every
     * write, so the stream's byte count is exact at all times: the buffer threshold and the declared
     * Content-Length are enforced on the bytes actually encoded, and {@link #isCommitted()} needs no
     * draining. Only an application {@link #flush()} commits.
     *
     * <p>Every operation holds the output stream's lock (taken before the writer's own monitor, as
     * {@link #drainWriter()} does), and a thread the stream refuses (see {@link #claimOutput()})
     * never reaches the encoder: its characters are discarded and {@link #checkError()} reports it,
     * so they cannot surface later in another thread's flush.</p>
     *
     * <p>In non-blocking mode (a {@code WriteListener} is set) an application operation is checked
     * once, before anything is encoded: while the stream is not ready it throws
     * {@link IllegalStateException} and no character reaches the encoder. An internal drain is
     * never refused.</p>
     */
    private final class ResponseWriter extends PrintWriter {
        ResponseWriter(Writer out) { super(out, false); }

        private void drain() {
            internalFlush = true;
            try { super.flush(); } finally { internalFlush = false; }
        }

        /** Whether the calling thread is refused; the refusal is recorded for {@link #checkError()}. */
        private boolean refused() {
            if (outputStream.writableByCurrentThread()) return false;
            setError();
            return true;
        }

        private void locked(Runnable action) {
            var lock = outputStream.lock();
            lock.lock();
            try {
                if (refused()) return;
                try {
                    outputStream.beginWriterOperation(!internalFlush);
                } catch (IOException e) {
                    setError();
                    return;
                }
                try {
                    action.run();
                } finally {
                    outputStream.endWriterOperation();
                }
            } finally {
                lock.unlock();
            }
        }

        @Override public void write(int c) { locked(() -> { super.write(c); drain(); }); }
        @Override public void write(char[] buf, int off, int len) { locked(() -> { super.write(buf, off, len); drain(); }); }
        @Override public void write(String s, int off, int len) { locked(() -> { super.write(s, off, len); drain(); }); }
        @Override public void println() { locked(() -> { super.println(); drain(); }); }
        @Override public void flush() { locked(super::flush); }
        @Override public void close() { locked(super::close); }

        // PrintWriter's println(x), printf and format hold the writer's monitor while they call
        // write(): taking the stream lock first here keeps one lock order everywhere (stream lock,
        // then monitor), as drainWriter() does; otherwise a println on one thread and a drain on
        // another would deadlock.
        @Override public void println(boolean x) { locked(() -> super.println(x)); }
        @Override public void println(char x) { locked(() -> super.println(x)); }
        @Override public void println(int x) { locked(() -> super.println(x)); }
        @Override public void println(long x) { locked(() -> super.println(x)); }
        @Override public void println(float x) { locked(() -> super.println(x)); }
        @Override public void println(double x) { locked(() -> super.println(x)); }
        @Override public void println(char[] x) { locked(() -> super.println(x)); }
        @Override public void println(String x) { locked(() -> super.println(x)); }
        @Override public void println(Object x) { locked(() -> super.println(x)); }
        @Override public PrintWriter format(String format, Object... args) {
            locked(() -> super.format(format, args));
            return this;
        }
        @Override public PrintWriter format(Locale l, String format, Object... args) {
            locked(() -> super.format(l, format, args));
            return this;
        }
        @Override public PrintWriter printf(String format, Object... args) { return format(format, args); }
        @Override public PrintWriter printf(Locale l, String format, Object... args) { return format(l, format, args); }
    }

    /**
     * Whether the body is already started: content buffered, or the writer obtained. The default
     * servlet declares a Content-Length only on a response whose body it writes alone, as bytes;
     * otherwise the declared length would truncate what a filter wrote first, or miscount a body
     * re-encoded through the writer.
     */
    public boolean bodyStarted() {
        drainWriter();
        return writer != null || headSent || outputStream.hasContent();
    }

    /** Whether any body byte was written since the last reset (the writer is drained per write). */
    boolean hasContent() {
        drainWriter();
        return outputStream.hasContent();
    }

    public byte[] bodyBytes() {
        drainWriter();
        byte[] all = outputStream.toByteArray();
        // Content beyond the declared Content-Length is never sent.
        if (contentLength >= 0 && all.length > contentLength) {
            return Arrays.copyOf(all, (int) contentLength);
        }
        return all;
    }

    public Map<String, List<String>> allHeaders() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (var e : headers.entrySet()) out.put(e.getKey(), List.copyOf(e.getValue()));
        for (Cookie c : cookies) {
            out.computeIfAbsent("Set-Cookie", _ -> new ArrayList<>())
                    .add(CookieCodec.serializeSetCookie(c));
        }
        return out;
    }

    public Set<String> headerNames() {
        return new HashSet<>(headers.keySet());
    }
}
