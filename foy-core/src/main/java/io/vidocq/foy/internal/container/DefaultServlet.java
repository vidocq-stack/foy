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

import io.vidocq.foy.internal.bridge.HttpServletResponseImpl;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.GenericServlet;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletResponseWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The container default servlet (Servlet 6.1 section 12.2): serves the static resources of the
 * application ({@link jakarta.servlet.ServletContext#getResource}) for every path no application
 * servlet maps. It is mapped to {@code /} under the name {@value #NAME} only when the application
 * maps nothing to {@code /}, and it is not an application registration.
 *
 * <p><b>Path.</b> {@code servletPath + pathInfo}, or the {@code jakarta.servlet.include.*} paths
 * on an include. Whatever the resource provider, a path with a dot or empty segment, a backslash,
 * a NUL or an encoded separator is 404 ({@link ResourcePaths#isDispatchable}); a client request
 * additionally cannot reach {@code WEB-INF/} or {@code META-INF/} ({@link ResourcePaths#isServable}),
 * which forward, include, async and error dispatches may serve (section 10.5). A directory is 404
 * (no listing).</p>
 *
 * <p><b>Methods.</b> On a request (or async) dispatch: {@code GET} and {@code HEAD} (same headers,
 * no body), {@code OPTIONS} (an {@code Allow} header), anything else 405. A forward, include or
 * error dispatch is the container's or the application's own decision and serves the resource
 * whatever the method (Tomcat does the same).</p>
 *
 * <p><b>Validators.</b> {@code Last-Modified} (file mtime or jar entry time, when known) and a weak
 * {@code ETag} {@code W/"<length>-<lastModified>"}. {@code If-None-Match} (weak comparison) wins
 * over {@code If-Modified-Since} (second precision); either answers 304. Conditional and range
 * processing is skipped on include and error dispatches.</p>
 *
 * <p><b>Ranges.</b> One {@code bytes=a-b}, {@code a-} or {@code -n} range answers 206 with
 * {@code Content-Range}; an unsatisfiable range answers 416 with {@code Content-Range: bytes *&#47;len}.
 * Several ranges answer 200 with the whole body (a documented simplification: no
 * {@code multipart/byteranges}). {@code If-Range} is honoured by date (second precision); an
 * entity-tag {@code If-Range} needs a strong comparison (RFC 9110 section 13.1.5), which the weak
 * {@code ETag} never satisfies, so it yields the whole body.</p>
 *
 * <p><b>Body.</b> When the provider gives the resource's metadata or a {@code file:} URL, the
 * resource is copied through a small buffer; otherwise its length is only known once read, so it
 * is read whole first and the declared length is that of the bytes read. The Foy response itself
 * still buffers the full body until the bridge sends it (streaming to the connection is Phase 5).
 * When a filter or an including servlet already uses the writer, the resource is decoded with the
 * response's character encoding and written through it. A Content-Length is declared only when
 * this servlet writes the body alone, as bytes.</p>
 */
public final class DefaultServlet extends GenericServlet {

    /** Servlet name of the container default servlet, chosen so that it cannot clash with an application name. */
    public static final String NAME = "foy.default";

    static final String ALLOW = "GET, HEAD, OPTIONS";

    /** A located resource: its length is known, its last-modified time is {@code -1} when unknown. */
    private record Resource(InputStream stream, long length, long lastModified) {
        String etag() { return lastModified < 0 ? null : "W/\"" + length + "-" + lastModified + "\""; }
    }

    @Override
    public void service(ServletRequest sreq, ServletResponse sres) throws ServletException, IOException {
        if (!(sreq instanceof HttpServletRequest req) || !(sres instanceof HttpServletResponse res)) {
            throw new ServletException("non-HTTP request");
        }
        DispatcherType type = req.getDispatcherType();
        boolean dispatched = type == DispatcherType.FORWARD || type == DispatcherType.INCLUDE
                || type == DispatcherType.ERROR;
        String method = req.getMethod();
        if (!dispatched) {
            if ("OPTIONS".equals(method)) {
                res.setHeader("Allow", ALLOW);
                return;
            }
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                res.setHeader("Allow", ALLOW);
                res.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                return;
            }
        }
        String path = resourcePath(req, type);
        Resource r = locate(path, type);
        if (r == null) {
            // Tomcat behaviour: an include of a missing resource fails the including servlet.
            if (type == DispatcherType.INCLUDE) throw new FileNotFoundException(path);
            res.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        try (InputStream in = r.stream()) {
            if (type == DispatcherType.INCLUDE) {
                // An include cannot set headers: only the body is written.
                copy(in, -1, res);
                return;
            }
            serve(req, res, path, r, in, type != DispatcherType.ERROR, "HEAD".equals(method));
        }
    }

    private void serve(HttpServletRequest req, HttpServletResponse res, String path, Resource r, InputStream in,
                       boolean conditional, boolean head) throws IOException {
        long length = r.length();
        long lastModified = r.lastModified();
        String etag = r.etag();
        if (lastModified >= 0) {
            res.setDateHeader("Last-Modified", lastModified);
            res.setHeader("ETag", etag);
        }
        if (conditional && notModified(req, etag, lastModified)) {
            res.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }
        String mime = getServletContext().getMimeType(path);
        if (mime != null) res.setContentType(mime);
        boolean clean = isClean(res);
        long start = 0;
        long count = length;
        if (conditional && clean) {
            res.setHeader("Accept-Ranges", "bytes");
            String range = req.getHeader("Range");
            if (range != null && ifRangeAllows(req, lastModified)) {
                long[] span = parseRange(range, length);
                if (span == UNSATISFIABLE) {
                    res.setHeader("Content-Range", "bytes */" + length);
                    res.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                    return;
                }
                if (span != null) {
                    start = span[0];
                    count = span[1] - span[0] + 1;
                    res.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                    res.setHeader("Content-Range", "bytes " + span[0] + "-" + span[1] + "/" + length);
                }
            }
        }
        if (clean) res.setContentLengthLong(count);
        if (head) return;
        if (start > 0) in.skipNBytes(start);
        copy(in, count == length && start == 0 ? -1 : count, res);
    }

    /** {@code If-None-Match} (weak comparison) wins; else {@code If-Modified-Since}, second precision. */
    private static boolean notModified(HttpServletRequest req, String etag, long lastModified) {
        String inm = req.getHeader("If-None-Match");
        if (inm != null) {
            for (String tag : inm.split(",")) {
                String t = tag.trim();
                if (t.equals("*")) return true;
                if (etag != null && opaque(t).equals(opaque(etag))) return true;
            }
            return false;
        }
        if (lastModified < 0) return false;
        long since = dateHeader(req, "If-Modified-Since");
        return since >= 0 && lastModified / 1000 <= since / 1000;
    }

    private static String opaque(String tag) {
        return tag.startsWith("W/") ? tag.substring(2) : tag;
    }

    /** An entity-tag If-Range needs a strong match the weak ETag never gives; a date must match exactly. */
    private static boolean ifRangeAllows(HttpServletRequest req, long lastModified) {
        String ifRange = req.getHeader("If-Range");
        if (ifRange == null) return true;
        String v = ifRange.trim();
        if (v.startsWith("\"") || v.startsWith("W/")) return false;
        long date = dateHeader(req, "If-Range");
        return date >= 0 && lastModified >= 0 && date / 1000 == lastModified / 1000;
    }

    private static long dateHeader(HttpServletRequest req, String name) {
        try {
            return req.getDateHeader(name);
        } catch (IllegalArgumentException unparsable) {
            return -1;
        }
    }

    private static final long[] UNSATISFIABLE = new long[0];

    /**
     * The single byte range {@code [first, last]} of {@code header}; {@code null} when the header is
     * to be ignored (malformed, another unit, several ranges); {@link #UNSATISFIABLE}.
     */
    static long[] parseRange(String header, long length) {
        String h = header.trim();
        if (!h.regionMatches(true, 0, "bytes=", 0, 6)) return null;
        String spec = h.substring(6).trim();
        if (spec.indexOf(',') >= 0) return null;
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        long first = number(spec.substring(0, dash).trim());
        String lastText = spec.substring(dash + 1).trim();
        if (spec.substring(0, dash).isBlank()) {
            long suffix = number(lastText);
            if (suffix < 0) return null;
            if (suffix == 0 || length == 0) return UNSATISFIABLE;
            return new long[] {Math.max(0, length - suffix), length - 1};
        }
        if (first < 0) return null;
        long last = lastText.isEmpty() ? length - 1 : number(lastText);
        if (last < 0 && !lastText.isEmpty()) return null;
        if (!lastText.isEmpty() && last < first) return null;
        if (first >= length) return UNSATISFIABLE;
        return new long[] {first, Math.min(last, length - 1)};
    }

    /** A non-negative decimal number, {@code -1} otherwise. */
    private static long number(String s) {
        if (s.isEmpty() || s.length() > 18) return -1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return -1;
        return Long.parseLong(s);
    }

    private static String resourcePath(HttpServletRequest req, DispatcherType type) {
        String servletPath;
        String pathInfo;
        Object includePath = type == DispatcherType.INCLUDE
                ? req.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH) : null;
        if (includePath != null) {
            servletPath = includePath.toString();
            Object info = req.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO);
            pathInfo = info == null ? null : info.toString();
        } else {
            servletPath = req.getServletPath();
            pathInfo = req.getPathInfo();
        }
        String path = (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
        return path.isEmpty() ? "/" : path;
    }

    /**
     * The resource behind {@code path}, or {@code null} when refused, missing or a directory. A
     * client request may not reach {@code WEB-INF/} or {@code META-INF/}; a dispatch may
     * (section 10.5). Metadata comes from the provider ({@link VidocqServletContext.ResourceProvider#metadata})
     * or the file system, never from a URL connection: a {@code jar:} connection opened only for
     * its headers leaks a jar handle per request.
     */
    private Resource locate(String path, DispatcherType type) throws IOException {
        boolean allowed = type == DispatcherType.REQUEST
                ? ResourcePaths.isServable(path) : ResourcePaths.isDispatchable(path);
        if (!allowed || path.endsWith("/")) return null;
        var ctx = getServletContext();
        long length = -1;
        long lastModified = -1;
        URL url = null;
        var meta = ctx instanceof VidocqServletContext v ? v.resourceMetadata(path) : null;
        if (meta != null) {
            length = meta.length();
            lastModified = meta.lastModified() > 0 ? meta.lastModified() : -1;
        } else {
            try {
                url = ctx.getResource(path);
            } catch (java.net.MalformedURLException e) {
                return null;
            }
            if (url != null && "file".equalsIgnoreCase(url.getProtocol())) {
                Path file;
                try {
                    file = Path.of(url.toURI());
                } catch (java.net.URISyntaxException | IllegalArgumentException e) {
                    return null;
                }
                if (!Files.isRegularFile(file)) return null;
                length = Files.size(file);
                lastModified = Files.getLastModifiedTime(file).toMillis();
            } else if (url != null && url.toString().endsWith("/")) {
                return null; // a directory of some other scheme
            }
        }
        // The provider's own stream (it releases jars on close), else the URL's; the caller closes it.
        InputStream in = ctx.getResourceAsStream(path);
        if (in == null && url != null) {
            try {
                in = url.openStream();
            } catch (IOException e) {
                return null;
            }
        }
        if (in == null) return null;
        if (length < 0) {
            // Neither provider metadata nor a file: the length is that of the bytes actually read.
            byte[] bytes;
            try (InputStream all = in) {
                bytes = all.readAllBytes();
            }
            return new Resource(new ByteArrayInputStream(bytes), bytes.length, lastModified);
        }
        return new Resource(in, length, lastModified);
    }

    /** Whether this servlet writes the body alone, as bytes, so that a Content-Length and a range describe it. */
    private static boolean isClean(ServletResponse res) {
        ServletResponse r = res;
        while (r instanceof ServletResponseWrapper w) r = w.getResponse();
        return r instanceof HttpServletResponseImpl impl && !res.isCommitted() && !impl.bodyStarted();
    }

    private static java.nio.charset.Charset responseCharset(ServletResponse res) {
        String enc = res.getCharacterEncoding();
        try {
            return enc == null ? StandardCharsets.ISO_8859_1 : java.nio.charset.Charset.forName(enc);
        } catch (RuntimeException unsupported) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    /** Copies {@code count} bytes ({@code -1}: all) through the stream, or the writer when it is in use. */
    private static void copy(InputStream in, long count, ServletResponse res) throws IOException {
        OutputStream out;
        try {
            out = res.getOutputStream();
        } catch (IllegalStateException writerInUse) {
            InputStream bounded = count < 0 ? in : new java.io.FilterInputStream(in) {
                private long left = count;
                @Override public int read() throws IOException {
                    if (left <= 0) return -1;
                    int b = super.read();
                    if (b >= 0) left--;
                    return b;
                }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (left <= 0) return -1;
                    int n = super.read(b, off, (int) Math.min(len, left));
                    if (n > 0) left -= n;
                    return n;
                }
            };
            // The writer encodes with the response's charset: decode the resource with the same one.
            new InputStreamReader(bounded, responseCharset(res)).transferTo(res.getWriter());
            return;
        }
        if (count < 0) {
            in.transferTo(out);
            return;
        }
        byte[] buffer = new byte[8192];
        long left = count;
        while (left > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (n < 0) break;
            out.write(buffer, 0, n);
            left -= n;
        }
    }
}
