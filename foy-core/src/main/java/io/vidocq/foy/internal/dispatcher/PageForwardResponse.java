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
package io.vidocq.foy.internal.dispatcher;

import io.vidocq.foy.internal.bridge.IncludedResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.function.Supplier;

/** Forward-specific references leave original INCLUDE references and application delegates intact. */
final class PageForwardResponse extends ServletResponseWrapper {
    private final Policy policy;
    private PrintWriter writer;
    private ServletOutputStream stream;

    private PageForwardResponse(ServletResponse response, Policy policy) { super(response); this.policy = policy; }

    static ServletResponse wrap(ServletResponse response) {
        var policy = new Policy(response);
        return response instanceof HttpServletResponse http ? new Http(http, policy)
                : new PageForwardResponse(response, policy);
    }

    /** HTTP error policy for a generic application wrapper whose reset/writer remain intact. */
    static void sendError(ServletResponse response, HttpServletResponse http, int status, String message) throws IOException {
        if (response instanceof PageForwardResponse generic)
            generic.policy.io(() -> { http.sendError(status, message); return null; });
        else ((HttpServletResponse) response).sendError(status, message);
    }

    @Override public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            var downstream = policy.io(() -> super.getWriter());
            writer = new PrintWriter(new Writer() {
                @Override public void write(char[] chars, int offset, int length) throws IOException {
                    policy.io(() -> { downstream.write(chars, offset, length); return null; });
                }
                @Override public void flush() throws IOException { policy.io(() -> { downstream.flush(); return null; }); }
                @Override public void close() throws IOException { policy.io(() -> { downstream.close(); return null; }); }
            }) {
                @Override public boolean checkError() { return policy.call(() -> super.checkError() || downstream.checkError()); }
            };
        }
        return writer;
    }

    @Override public ServletOutputStream getOutputStream() throws IOException {
        if (stream == null) {
            var downstream = policy.io(() -> super.getOutputStream());
            stream = new ServletOutputStream() {
                @Override public void write(int value) throws IOException { policy.io(() -> { downstream.write(value); return null; }); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    policy.io(() -> { downstream.write(bytes, offset, length); return null; });
                }
                @Override public void flush() throws IOException { policy.io(() -> { downstream.flush(); return null; }); }
                @Override public void close() throws IOException { policy.io(() -> { downstream.close(); return null; }); }
                @Override public boolean isReady() { return policy.call(downstream::isReady); }
                @Override public void setWriteListener(WriteListener listener) { policy.run(() -> downstream.setWriteListener(listener)); }
            };
        }
        return stream;
    }

    @Override public void setCharacterEncoding(String charset) { policy.run(() -> super.setCharacterEncoding(charset)); }
    @Override public void setCharacterEncoding(Charset charset) { policy.run(() -> super.setCharacterEncoding(charset)); }
    @Override public void setContentLength(int length) { policy.run(() -> super.setContentLength(length)); }
    @Override public void setContentLengthLong(long length) { policy.run(() -> super.setContentLengthLong(length)); }
    @Override public void setContentType(String type) { policy.run(() -> super.setContentType(type)); }
    @Override public void setBufferSize(int size) { policy.run(() -> super.setBufferSize(size)); }
    @Override public void resetBuffer() { policy.run(() -> super.resetBuffer()); }
    @Override public void reset() { policy.run(() -> super.reset()); }
    @Override public void setLocale(Locale locale) { policy.run(() -> super.setLocale(locale)); }
    @Override public void flushBuffer() throws IOException { policy.io(() -> { super.flushBuffer(); return null; }); }

    /** All generic operations share the same retained writer/stream helpers. */
    private static final class Http extends HttpServletResponseWrapper {
        private final Policy policy;
        private final PageForwardResponse generic;
        Http(HttpServletResponse response, Policy policy) { super(response); this.policy = policy; generic = new PageForwardResponse(response, policy); }
        @Override public PrintWriter getWriter() throws IOException { return generic.getWriter(); }
        @Override public ServletOutputStream getOutputStream() throws IOException { return generic.getOutputStream(); }
        @Override public void setCharacterEncoding(String charset) { generic.setCharacterEncoding(charset); }
        @Override public void setCharacterEncoding(Charset charset) { generic.setCharacterEncoding(charset); }
        @Override public void setContentLength(int length) { generic.setContentLength(length); }
        @Override public void setContentLengthLong(long length) { generic.setContentLengthLong(length); }
        @Override public void setContentType(String type) { generic.setContentType(type); }
        @Override public void setBufferSize(int size) { generic.setBufferSize(size); }
        @Override public void resetBuffer() { generic.resetBuffer(); }
        @Override public void reset() { generic.reset(); }
        @Override public void setLocale(Locale locale) { generic.setLocale(locale); }
        @Override public void flushBuffer() throws IOException { generic.flushBuffer(); }
        @Override public void setStatus(int status) { policy.run(() -> super.setStatus(status)); }
        @Override public void sendError(int status) throws IOException { policy.io(() -> { super.sendError(status); return null; }); }
        @Override public void sendError(int status, String message) throws IOException { policy.io(() -> { super.sendError(status, message); return null; }); }
        @Override public void sendRedirect(String location) throws IOException { policy.io(() -> { super.sendRedirect(location); return null; }); }
        @Override public void sendRedirect(String location, int status, boolean clearBuffer) throws IOException {
            policy.io(() -> { super.sendRedirect(location, status, clearBuffer); return null; });
        }
        @Override public void sendRedirect(String location, boolean clearBuffer) throws IOException {
            policy.io(() -> { super.sendRedirect(location, clearBuffer); return null; });
        }
        @Override public void sendRedirect(String location, int status) throws IOException {
            policy.io(() -> { super.sendRedirect(location, status); return null; });
        }
        @Override public void setHeader(String name, String value) { policy.run(() -> super.setHeader(name, value)); }
        @Override public void addHeader(String name, String value) { policy.run(() -> super.addHeader(name, value)); }
        @Override public void setDateHeader(String name, long value) { policy.run(() -> super.setDateHeader(name, value)); }
        @Override public void addDateHeader(String name, long value) { policy.run(() -> super.addDateHeader(name, value)); }
        @Override public void setIntHeader(String name, int value) { policy.run(() -> super.setIntHeader(name, value)); }
        @Override public void addIntHeader(String name, int value) { policy.run(() -> super.addIntHeader(name, value)); }
        @Override public void addCookie(Cookie cookie) { policy.run(() -> super.addCookie(cookie)); }
        @Override public void setTrailerFields(Supplier<Map<String, String>> supplier) { policy.run(() -> super.setTrailerFields(supplier)); }
    }

    /** Only Foy-owned frames are entered, for the duration of a specific response/handle operation. */
    private static final class Policy {
        private final List<IncludedResponse> frames;
        Policy(ServletResponse response) {
            var owned = new ArrayList<IncludedResponse>();
            while (response instanceof ServletResponseWrapper wrapper) {
                if (response instanceof IncludedResponse included) owned.add(included);
                response = wrapper.getResponse();
            }
            frames = List.copyOf(owned);
        }
        private void enter() {
            int entered = 0;
            try {
                for (var frame : frames) { frame.beginJspForwardOperation(); entered++; }
            } catch (RuntimeException | Error failure) {
                for (int index = entered - 1; index >= 0; index--) frames.get(index).endJspForwardOperation();
                throw failure;
            }
        }
        private void leave() { for (int index = frames.size() - 1; index >= 0; index--) frames.get(index).endJspForwardOperation(); }
        <T> T call(Supplier<T> action) { enter(); try { return action.get(); } finally { leave(); } }
        void run(Runnable action) { call(() -> { action.run(); return null; }); }
        @FunctionalInterface private interface IOAction<T> { T run() throws IOException; }
        <T> T io(IOAction<T> action) throws IOException { enter(); try { return action.run(); } finally { leave(); } }
    }
}
