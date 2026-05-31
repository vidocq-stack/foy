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
    private Locale locale = Locale.getDefault();
    private final ServletOutputStreamImpl outputStream = new ServletOutputStreamImpl();
    { outputStream.setFlushListener(() -> committed = true); }
    private PrintWriter writer;
    private boolean streamAcquired;
    private boolean committed;
    private boolean errorTriggered;
    private String errorMessage;

    // ---- Status ----

    @Override public int getStatus() { return status; }
    @Override public void setStatus(int sc) { this.status = sc; }
    @Override public void sendError(int sc, String msg) throws IOException {
        if (committed) throw new IllegalStateException("response already committed");
        setStatus(sc);
        this.errorTriggered = true;
        this.errorMessage = msg;
        // Servlet 6.1 §5.8 : sendError vide le buffer — tout ce que le servlet
        // a écrit avant est jeté. On écrit ensuite le msg par défaut en bytes
        // bruts directement dans le buffer interne pour éviter le conflit
        // getWriter()/getOutputStream() (IllegalStateException).
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
    }
    @Override public void sendError(int sc) throws IOException { sendError(sc, null); }

    public boolean isErrorTriggered() { return errorTriggered; }
    public String errorMessage() { return errorMessage; }
    public void clearErrorState() {
        this.errorTriggered = false;
        this.errorMessage = null;
        this.committed = false;
    }
    @Override public void sendRedirect(String location) throws IOException {
        if (committed) throw new IllegalStateException("response already committed");
        setStatus(SC_FOUND);
        setHeader("Location", toAbsoluteRedirectUrl(location));
        committed = true;
    }
    @Override public void sendRedirect(String location, int sc, boolean clearBuffer) throws IOException {
        if (clearBuffer) resetBuffer();
        setStatus(sc);
        setHeader("Location", toAbsoluteRedirectUrl(location));
        committed = true;
    }

    /** Servlet 6.1 §5.8.2 — sendRedirect must produce an absolute URL. */
    private String toAbsoluteRedirectUrl(String location) {
        if (location == null) return null;
        // Déjà absolu.
        if (location.regionMatches(true, 0, "http://", 0, 7)
                || location.regionMatches(true, 0, "https://", 0, 8)) return location;
        if (boundRequest == null) return location;
        String scheme = boundRequest.getScheme();
        String host = boundRequest.getServerName();
        int port = boundRequest.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        var sb = new StringBuilder(scheme).append("://").append(host);
        if (!defaultPort) sb.append(':').append(port);
        if (location.startsWith("/")) {
            sb.append(location);
        } else {
            // Chemin relatif — résolu par rapport à l'URI de la requête.
            String uri = boundRequest.getRequestURI();
            int slash = uri.lastIndexOf('/');
            sb.append(slash >= 0 ? uri.substring(0, slash + 1) : "/").append(location);
        }
        return sb.toString();
    }

    private jakarta.servlet.http.HttpServletRequest boundRequest;
    public void bindRequest(jakarta.servlet.http.HttpServletRequest req) { this.boundRequest = req; }

    // ---- Headers ----

    @Override public void setHeader(String name, String value) {
        List<String> list = new ArrayList<>();
        list.add(value);
        headers.put(name, list);
        interceptSpecialHeader(name, value);
    }
    @Override public void addHeader(String name, String value) {
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
        return java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.ofEpochMilli(dateMillis));
    }
    @Override public boolean containsHeader(String name) { return headers.containsKey(name); }
    @Override public String getHeader(String name) {
        List<String> list = headers.get(name);
        return list == null || list.isEmpty() ? null : list.get(0);
    }
    @Override public Collection<String> getHeaders(String name) {
        List<String> list = headers.get(name);
        return list == null ? List.of() : List.copyOf(list);
    }
    @Override public Collection<String> getHeaderNames() { return new HashSet<>(headers.keySet()); }

    private void interceptSpecialHeader(String name, String value) {
        if ("Content-Type".equalsIgnoreCase(name)) {
            this.contentType = value;
            int idx = value == null ? -1 : value.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (idx >= 0) {
                this.characterEncoding = value.substring(idx + 8).trim();
            }
        }
    }

    // ---- Cookies ----

    @Override public void addCookie(Cookie cookie) { cookies.add(cookie); }
    public List<Cookie> cookies() { return cookies; }

    // ---- Content-Type / charset ----

    /** "Raw" MIME type (without the charset) derived from setContentType. */
    private String mediaType;
    private boolean charsetExplicit;
    /** The charset is locked after getWriter() (Servlet 6.1 §5.4). */
    private boolean charsetLocked;

    @Override public String getContentType() {
        if (contentType == null) return null;
        if (contentType.toLowerCase(Locale.ROOT).contains("charset=") || characterEncoding == null) {
            return contentType;
        }
        return mediaType + ";charset=" + characterEncoding;
    }
    @Override public void setContentType(String type) {
        // Servlet 6.1 §5.4 : si la réponse est déjà committed, setContentType est silencieusement ignoré.
        if (committed) return;
        this.contentType = type;
        if (type == null) return;
        int idx = type.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (idx >= 0) {
            this.mediaType = type.substring(0, idx).replaceAll(";\\s*$", "").trim();
            // Le charset du contentType n'est accepté que si pas encore verrouillé par getWriter().
            if (!charsetLocked) {
                this.characterEncoding = type.substring(idx + 8).trim();
                this.charsetExplicit = true;
            }
        } else {
            this.mediaType = type;
        }
        refreshContentTypeHeader();
    }
    @Override public String getCharacterEncoding() {
        // Servlet 6.1 §5.4 : null si aucun encoding n'a été explicitement setté.
        return characterEncoding;
    }
    @Override public void setCharacterEncoding(String charset) {
        if (committed || charsetLocked) return;
        this.characterEncoding = charset;
        this.charsetExplicit = (charset != null);
        refreshContentTypeHeader();
    }
    @Override public void setCharacterEncoding(Charset encoding) {
        if (committed || charsetLocked) return;
        this.characterEncoding = encoding == null ? null : encoding.name();
        this.charsetExplicit = (encoding != null);
        refreshContentTypeHeader();
    }

    /** Recalculates the {@code Content-Type} header by combining mediaType + charset. */
    private void refreshContentTypeHeader() {
        if (mediaType == null) return;
        // Pour les types text/*, on inclut toujours le charset (explicite ou défaut
        // "ISO-8859-1", cf. Servlet 6.1 §5.4) afin que le header Content-Type final
        // reflète l'encodage réellement utilisé par getWriter().
        boolean isText = mediaType.toLowerCase(Locale.ROOT).startsWith("text/");
        String enc = characterEncoding != null ? characterEncoding
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
            // Servlet 6.1 §5.4 : si le charset a été explicitement setté et qu'il n'est
            // pas supporté par la JVM, getWriter doit throw UnsupportedEncodingException.
            if (characterEncoding != null && !Charset.isSupported(characterEncoding)) {
                throw new java.io.UnsupportedEncodingException(characterEncoding);
            }
            // Résout le charset (ISO-8859-1 par défaut) et verrouille — le state
            // reflète désormais le charset réellement utilisé pour écrire le body.
            if (characterEncoding == null) characterEncoding = "ISO-8859-1";
            charsetLocked = true;
            writer = new PrintWriter(new java.io.OutputStreamWriter(outputStream, charset()), false);
            refreshContentTypeHeader();
        }
        return writer;
    }

    private Charset charset() {
        if (characterEncoding == null) return StandardCharsets.ISO_8859_1;
        try { return Charset.forName(characterEncoding); }
        catch (RuntimeException e) { return StandardCharsets.ISO_8859_1; }
    }

    // ---- Buffer / commit ----

    // Taille de buffer nominale exposée au servlet — on bufferise tout en mémoire,
    // donc la capacité effective est illimitée, mais on expose une valeur usuelle
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
    @Override public void resetBuffer() {
        if (committed) throw new IllegalStateException("committed");
        outputStream.resetBuffer();
        writer = null;
        streamAcquired = false;
    }
    @Override public boolean isCommitted() { return committed; }
    @Override public void reset() {
        if (committed) throw new IllegalStateException("committed");
        status = 200;
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
        if (committed || loc == null) { this.locale = loc; return; }
        this.locale = loc;
        // Servlet 6.1 §5.4 : setLocale définit Content-Language (tag BCP 47).
        setHeader("Content-Language", loc.toLanguageTag());
        // Si le charset n'est pas explicite, résout via locale-encoding-mapping-list du web.xml.
        if (!charsetExplicit && boundRequest != null
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

    // ---- Accès interne pour le bridge Chappe ----

    public byte[] bodyBytes() {
        if (writer != null) writer.flush();
        return outputStream.toByteArray();
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
