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

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.TestSecurity;
import io.vidocq.foy.internal.TestServerLauncherAccess;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.spi.jsp.JspEngine;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** JSP forwarding preserves application wrappers without extending INCLUDE response policy. */
class JspForwardDispatcherTest {
    private Server server;
    private int port;

    @AfterEach void stop() { if (server != null) server.stop(); }

    @FunctionalInterface
    interface Body { void run(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException; }

    private static ServletDispatcher.Mapping map(String path, Body body) {
        var servlet = new HttpServlet() {
            @Override protected void service(HttpServletRequest request, HttpServletResponse response)
                    throws IOException, ServletException { body.run(request, response); }
        };
        return new ServletDispatcher.Mapping(UrlPatternMatcher.of(path), servlet, path, true);
    }

    private void start(ServletDispatcher.Mapping... mappings) {
        var context = new VidocqServletContext("/");
        var bridge = new ChappeServletBridge(new ServletDispatcher(List.of(mappings)),
                new FilterRegistry(List.of()), TestSecurity.permitAll(context), null, "/");
        var running = TestServerLauncherAccess.start(bridge);
        server = running.server(); port = running.port();
    }

    private HttpResponse<String> get() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/caller"))
                    .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void forward(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        var target = request.getRequestDispatcher("/target");
        assertInstanceOf(JspEngine.ForwardDispatcher.class, target);
        ((JspEngine.ForwardDispatcher) target).forwardFromJsp(request, response);
    }

    /** A writer cached before forwarding, with observable output and header transformations. */
    private static final class ApplicationResponse extends HttpServletResponseWrapper {
        final PrintWriter writer;
        int resets;
        ApplicationResponse(HttpServletResponse response) throws IOException {
            super(response);
            PrintWriter downstream = response.getWriter();
            writer = new PrintWriter(new Writer() {
                @Override public void write(char[] chars, int offset, int length) {
                    ApplicationResponse.super.setHeader("X-Writer", "forwarded");
                    downstream.write(new String(chars, offset, length).toUpperCase(Locale.ROOT));
                }
                @Override public void flush() { downstream.flush(); }
                @Override public void close() { downstream.close(); }
            });
        }
        @Override public PrintWriter getWriter() { return writer; }
        @Override public void resetBuffer() { resets++; super.resetBuffer(); }
    }

    @Test
    void nestedIncludesKeepApplicationWrappersAndCachedWritersAndResetOnce() throws Exception {
        var first = new AtomicReference<HttpServletResponse>();
        var second = new AtomicReference<HttpServletResponse>();
        var outer = new AtomicReference<ApplicationResponse>();
        var child = new AtomicReference<ApplicationResponse>();
        var completed = new AtomicReference<Boolean>();
        start(map("/caller", (request, response) -> {
            response.getWriter().write("discard");
            request.getRequestDispatcher("/include-one").include(request, response);
            completed.set(response.isCommitted());
        }), map("/include-one", (request, response) -> {
            first.set(response); outer.set(new ApplicationResponse(response));
            request.getRequestDispatcher("/include-two").include(request, outer.get());
        }), map("/include-two", (request, response) -> {
            second.set(response); child.set(new ApplicationResponse(response));
            forward(request, child.get());
            assertSame(response, child.get().getResponse());
            response.setHeader("X-Include-After", "ignored");
        }), map("/target", (request, response) -> {
            assertTrue(response == child.get() || response instanceof ServletResponseWrapper wrapper && wrapper.isWrapperFor(child.get()));
            first.get().setHeader("X-Include-During", "ignored");
            second.get().setStatus(418);
            response.setStatus(207); response.setHeader("X-Forward", "kept");
            response.getWriter().write("target");
        }));
        var result = get();
        assertAll(() -> assertEquals(207, result.statusCode()), () -> assertEquals("TARGET", result.body()),
                () -> assertEquals("kept", result.headers().firstValue("X-Forward").orElse(null)),
                () -> assertEquals("forwarded", result.headers().firstValue("X-Writer").orElse(null)),
                () -> assertTrue(result.headers().firstValue("X-Include-During").isEmpty()),
                () -> assertTrue(result.headers().firstValue("X-Include-After").isEmpty()),
                () -> assertEquals(1, outer.get().resets), () -> assertEquals(1, child.get().resets),
                () -> assertEquals(Boolean.TRUE, completed.get()));
    }

    @Test
    void asyncRetainedForwardResponseAndWriterReenterOnlyOwnedIncludePolicy() throws Exception {
        var returned = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        start(map("/caller", (request, response) -> request.getRequestDispatcher("/included").include(request, response)),
                map("/included", (request, response) -> {
                    forward(request, new ApplicationResponse(response));
                    response.setHeader("X-Include-After", "ignored"); returned.countDown();
                }), map("/target", (request, response) -> {
                    var writer = response.getWriter();
                    var async = request.startAsync(request, response);
                    async.start(() -> {
                        try {
                            if (!returned.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("forward did not return");
                            response.setStatus(202); response.setHeader("X-Retained", "kept");
                            writer.write("later"); writer.flush();
                        } catch (Throwable error) { failure.set(error); }
                        finally { async.complete(); }
                    });
                }));
        var result = get();
        assertAll(() -> assertNull(failure.get()), () -> assertEquals(202, result.statusCode()),
                () -> assertEquals("LATER", result.body()),
                () -> assertEquals("kept", result.headers().firstValue("X-Retained").orElse(null)),
                () -> assertEquals("forwarded", result.headers().firstValue("X-Writer").orElse(null)),
                () -> assertTrue(result.headers().firstValue("X-Include-After").isEmpty()));
    }

    @Test
    void queuedAsyncDispatchIsNotClosedWhenIsAsyncStartedHasAlreadyBecomeFalse() throws Exception {
        start(map("/caller", (request, response) -> request.getRequestDispatcher("/included").include(request, response)),
                map("/included", (request, response) -> forward(request, new ApplicationResponse(response))),
                map("/target", (request, response) -> request.startAsync(request, response).dispatch("/after")),
                map("/after", (request, response) -> {
                    response.setStatus(202); response.setHeader("X-Async", "kept"); response.getWriter().write("after");
                }));
        var result = get();
        assertAll(() -> assertEquals(202, result.statusCode()), () -> assertEquals("AFTER", result.body()),
                () -> assertEquals("kept", result.headers().firstValue("X-Async").orElse(null)));
    }

    @Test
    void failureRestoresIncludePolicyAndPreservesTargetExceptionAndWrapperDelegate() throws Exception {
        var failure = new IOException("target failed");
        start(map("/caller", (request, response) -> {
            request.getRequestDispatcher("/included").include(request, response); response.getWriter().write("|after");
        }), map("/included", (request, response) -> {
            var application = new ApplicationResponse(response);
            assertSame(failure, assertThrows(IOException.class, () -> forward(request, application)));
            assertSame(response, application.getResponse());
            application.setHeader("X-After-Failure", "ignored"); application.getWriter().write("recovered");
        }), map("/target", (request, response) -> { response.setHeader("X-Target", "kept"); throw failure; }));
        var result = get();
        assertAll(() -> assertEquals("RECOVERED|after", result.body()),
                () -> assertEquals("kept", result.headers().firstValue("X-Target").orElse(null)),
                () -> assertTrue(result.headers().firstValue("X-After-Failure").isEmpty()));
    }

    @Test
    void aMissingJspForwardTargetUsesTypedResetAndErrorPolicy() throws Exception {
        start(map("/caller", (request, response) -> request.getRequestDispatcher("/included").include(request, response)),
                map("/included", (request, response) -> {
                    var application = new ApplicationResponse(response);
                    var missing = request.getRequestDispatcher("/missing");
                    assertInstanceOf(JspEngine.ForwardDispatcher.class, missing);
                    ((JspEngine.ForwardDispatcher) missing).forwardFromJsp(request, application);
                    assertEquals(1, application.resets);
                }));
        assertEquals(404, get().statusCode());
    }

    @Test
    void retainedForwardOutputStreamKeepsApplicationTransformAndForwardPolicyAfterCallback() throws Exception {
        var returned = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        start(map("/caller", (request, response) -> request.getRequestDispatcher("/included").include(request, response)),
                map("/included", (request, response) -> {
                    var downstream = response.getOutputStream();
                    var application = new HttpServletResponseWrapper(response) {
                        private final ServletOutputStream cached = new ServletOutputStream() {
                            @Override public void write(int value) throws IOException {
                                setHeader("X-Stream", "forwarded"); downstream.write(Character.toUpperCase(value));
                            }
                            @Override public void flush() throws IOException { downstream.flush(); }
                            @Override public void close() throws IOException { downstream.close(); }
                            @Override public boolean isReady() { return downstream.isReady(); }
                            @Override public void setWriteListener(WriteListener listener) { downstream.setWriteListener(listener); }
                        };
                        @Override public ServletOutputStream getOutputStream() { return cached; }
                    };
                    forward(request, application); response.setHeader("X-Include-After", "ignored"); returned.countDown();
                }), map("/target", (request, response) -> {
                    var stream = response.getOutputStream();
                    var async = request.startAsync(request, response);
                    async.start(() -> {
                        try {
                            if (!returned.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("forward did not return");
                            response.setStatus(202); stream.write("bytes".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); stream.flush();
                        } catch (Throwable error) { failure.set(error); }
                        finally { async.complete(); }
                    });
                }));
        var result = get();
        assertAll(() -> assertNull(failure.get()), () -> assertEquals(202, result.statusCode()),
                () -> assertEquals("BYTES", result.body()),
                () -> assertEquals("forwarded", result.headers().firstValue("X-Stream").orElse(null)),
                () -> assertTrue(result.headers().firstValue("X-Include-After").isEmpty()));
    }

    @Test
    void aForwardOperationDoesNotPromoteOriginalIncludeReferencesOnAnotherVirtualThread() throws Exception {
        var entered = new CountDownLatch(1);
        var originalFinished = new CountDownLatch(1);
        var original = new AtomicReference<HttpServletResponse>();
        var failure = new AtomicReference<Throwable>();
        start(map("/caller", (request, response) -> {
            var task = Thread.startVirtualThread(() -> {
                try {
                    if (!entered.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("forward operation did not enter");
                    original.get().setHeader("X-Leaked-Original", "must-be-ignored");
                } catch (Throwable error) { failure.set(error); }
                finally { originalFinished.countDown(); }
            });
            request.getRequestDispatcher("/included").include(request, response);
            try { task.join(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new ServletException(error); }
        }), map("/included", (request, response) -> {
            original.set(response);
            var application = new HttpServletResponseWrapper(response) {
                @Override public void setHeader(String name, String value) {
                    if (name.equals("X-Forward")) {
                        entered.countDown();
                        try {
                            if (!originalFinished.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("original operation did not finish");
                        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                    }
                    super.setHeader(name, value);
                }
            };
            forward(request, application);
        }), map("/target", (request, response) -> {
            response.setHeader("X-Forward", "kept"); response.getWriter().write("target");
        }));
        var result = get();
        assertAll(() -> assertNull(failure.get()), () -> assertEquals("target", result.body()),
                () -> assertEquals("kept", result.headers().firstValue("X-Forward").orElse(null)),
                () -> assertTrue(result.headers().firstValue("X-Leaked-Original").isEmpty()));
    }

    @Test
    void aMissingTargetWithAGenericApplicationResponseWrapperStillUsesTyped404() throws Exception {
        var resets = new java.util.concurrent.atomic.AtomicInteger();
        start(map("/caller", (request, response) -> request.getRequestDispatcher("/included").include(request, response)),
                map("/included", (request, response) -> {
                    var application = new ServletResponseWrapper(response) {
                        @Override public void resetBuffer() { resets.incrementAndGet(); super.resetBuffer(); }
                    };
                    var missing = (JspEngine.ForwardDispatcher) request.getRequestDispatcher("/missing");
                    missing.forwardFromJsp(request, application);
                    assertSame(response, application.getResponse());
                }));
        var result = get();
        assertAll(() -> assertEquals(404, result.statusCode()), () -> assertEquals(1, resets.get()));
    }

    @Test
    void aConflictingForwardOperationReleasesAnyEarlierOwnedFrames() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var lower = new AtomicReference<HttpServletResponse>();
        var failure = new AtomicReference<Throwable>();
        start(map("/caller", (request, response) -> {
            var blocking = new HttpServletResponseWrapper(response) {
                @Override public void setHeader(String name, String value) {
                    if (name.equals("X-Hold")) {
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("operation was not released");
                        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                    }
                    super.setHeader(name, value);
                }
            };
            request.getRequestDispatcher("/outer").include(request, blocking);
        }), map("/outer", (request, response) -> {
            lower.set(response); request.getRequestDispatcher("/inner").include(request, response);
        }), map("/inner", (request, response) -> {
            var worker = Thread.startVirtualThread(() -> {
                try { ((HttpServletResponse) PageForwardResponse.wrap(lower.get())).setHeader("X-Hold", "held"); }
                catch (Throwable error) { failure.set(error); }
            });
            try {
                if (!entered.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("operation did not enter");
                var forwarded = (HttpServletResponse) PageForwardResponse.wrap(response);
                assertThrows(IllegalStateException.class, () -> forwarded.setHeader("X-Conflict", "ignored"));
                response.setHeader("X-Original", "ignored");
                release.countDown(); worker.join();
                forwarded.setHeader("X-Recovered", "kept");
                response.setHeader("X-After", "ignored"); response.getWriter().write("target");
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new ServletException(error); }
            finally { release.countDown(); }
        }));
        var result = get();
        assertAll(() -> assertNull(failure.get()), () -> assertEquals("target", result.body()),
                () -> assertEquals("kept", result.headers().firstValue("X-Recovered").orElse(null)),
                () -> assertTrue(result.headers().firstValue("X-Original").isEmpty()),
                () -> assertTrue(result.headers().firstValue("X-After").isEmpty()),
                () -> assertTrue(result.headers().firstValue("X-Conflict").isEmpty()));
    }
}
