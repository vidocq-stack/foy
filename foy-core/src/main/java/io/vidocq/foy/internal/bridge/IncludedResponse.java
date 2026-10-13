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

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.Charset;
import java.io.IOException;

/**
 * {@link HttpServletResponseWrapper} used during an include (Servlet spec 6.1 §9.3).
 * <p>
 * The status setters (status, headers, cookies, content-type, buffer, redirect/error) are
 * no-op: only the primary response controls the HTTP header. Only {@code getWriter}/
 * {@code getOutputStream} remain connected so included content is appended to the body.
 * </p>
 */
public final class IncludedResponse extends HttpServletResponseWrapper {
    private record ForwardOperation(Thread owner, int depth) { }
    private final AtomicReference<ForwardOperation> forwardOperation = new AtomicReference<>();

    public IncludedResponse(HttpServletResponse primary) {
        super(primary);
    }

    /** Enables forwarding for one container-owned operation, never an entire target invocation. */
    public void beginJspForwardOperation() {
        var thread = Thread.currentThread();
        while (true) {
            var operation = forwardOperation.get();
            if (operation != null && operation.owner() != thread)
                throw new IllegalStateException("Concurrent forwarded response operations require application coordination");
            var next = new ForwardOperation(thread, operation == null ? 1 : Math.incrementExact(operation.depth()));
            if (forwardOperation.compareAndSet(operation, next)) return;
        }
    }
    public void endJspForwardOperation() {
        while (true) {
            var operation = forwardOperation.get();
            if (operation == null || operation.owner() != Thread.currentThread())
                throw new IllegalStateException("Forward response operation is not owned by this thread");
            var next = operation.depth() == 1 ? null : new ForwardOperation(operation.owner(), operation.depth() - 1);
            if (forwardOperation.compareAndSet(operation, next)) return;
        }
    }
    private boolean forwarding() {
        var operation = forwardOperation.get();
        return operation != null && operation.owner() == Thread.currentThread();
    }

    @Override public void setStatus(int sc) { if (forwarding()) super.setStatus(sc); }
    @Override public void sendError(int sc) throws IOException { if (forwarding()) super.sendError(sc); }
    @Override public void sendError(int sc, String msg) throws IOException { if (forwarding()) super.sendError(sc, msg); }
    @Override public void sendRedirect(String location) throws IOException { if (forwarding()) super.sendRedirect(location); }
    @Override public void sendRedirect(String location, int sc, boolean clearBuffer) throws IOException {
        if (forwarding()) super.sendRedirect(location, sc, clearBuffer);
    }
    @Override public void sendRedirect(String location, boolean clearBuffer) throws IOException {
        if (forwarding()) super.sendRedirect(location, clearBuffer);
    }
    @Override public void sendRedirect(String location, int sc) throws IOException { if (forwarding()) super.sendRedirect(location, sc); }
    @Override public void setHeader(String name, String value) { if (forwarding()) super.setHeader(name, value); }
    @Override public void addHeader(String name, String value) { if (forwarding()) super.addHeader(name, value); }
    @Override public void setDateHeader(String name, long date) { if (forwarding()) super.setDateHeader(name, date); }
    @Override public void addDateHeader(String name, long date) { if (forwarding()) super.addDateHeader(name, date); }
    @Override public void setIntHeader(String name, int value) { if (forwarding()) super.setIntHeader(name, value); }
    @Override public void addIntHeader(String name, int value) { if (forwarding()) super.addIntHeader(name, value); }
    @Override public void addCookie(Cookie cookie) { if (forwarding()) super.addCookie(cookie); }
    @Override public void setContentType(String type) { if (forwarding()) super.setContentType(type); }
    @Override public void setContentLength(int len) { if (forwarding()) super.setContentLength(len); }
    @Override public void setContentLengthLong(long len) { if (forwarding()) super.setContentLengthLong(len); }
    @Override public void setCharacterEncoding(String charset) { if (forwarding()) super.setCharacterEncoding(charset); }
    @Override public void setCharacterEncoding(Charset charset) { if (forwarding()) super.setCharacterEncoding(charset); }
    @Override public void setLocale(Locale loc) { if (forwarding()) super.setLocale(loc); }
    @Override public void setBufferSize(int size) { if (forwarding()) super.setBufferSize(size); }
    @Override public void resetBuffer() { if (forwarding()) super.resetBuffer(); }
    @Override public void reset() { if (forwarding()) super.reset(); }
    @Override public void setTrailerFields(Supplier<Map<String, String>> supplier) { if (forwarding()) super.setTrailerFields(supplier); }
}
