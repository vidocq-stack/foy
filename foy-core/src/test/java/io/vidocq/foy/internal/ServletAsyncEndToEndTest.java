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
package io.vidocq.foy.internal;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.dispatcher.FilterRegistry;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
import io.vidocq.foy.internal.error.ErrorPageRegistry;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ServletAsyncEndToEndTest {

    private Server server;
    private int port;

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

    @Test
    void asyncWriteFromAnotherThread() throws Exception {
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                AsyncContext ac = req.startAsync();
                ac.start(() -> {
                    try {
                        Thread.sleep(30);
                        HttpServletResponse r = (HttpServletResponse) ac.getResponse();
                        r.setContentType("text/plain");
                        r.getWriter().write("async-ok");
                        ac.complete();
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
            }
        };
        start(s, "/a");

        HttpResponse<String> r = get("/a");
        assertEquals(200, r.statusCode());
        assertEquals("async-ok", r.body());
    }

    @Test
    void asyncDispatchRedirectsInternally() throws Exception {
        HttpServlet origin = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                AsyncContext ac = req.startAsync();
                ac.dispatch("/target");
            }
        };
        HttpServlet target = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setContentType("text/plain");
                resp.getWriter().write("target:" + req.getDispatcherType());
            }
        };
        startMany(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/origin"), origin, "O"),
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/target"), target, "T")
        ));

        HttpResponse<String> r = get("/origin");
        assertEquals(200, r.statusCode());
        assertEquals("target:ASYNC", r.body());
    }

    @Test
    void isAsyncStartedReportsTrue() throws Exception {
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                AsyncContext ac = req.startAsync();
                resp.setContentType("text/plain");
                resp.getWriter().write(Boolean.toString(req.isAsyncStarted())
                        + "/" + Boolean.toString(req.isAsyncSupported()));
                ac.complete();
            }
        };
        start(s, "/status");

        HttpResponse<String> r = get("/status");
        assertEquals("true/true", r.body());
    }

    // ---- Async lifecycle (Servlet 6.1 section 2.3.3.3) ----

    /** Records the listener events of one async cycle, in order. */
    private static class Recorder implements AsyncListener {
        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch completed = new CountDownLatch(1);
        @Override public void onComplete(AsyncEvent event) { events.add("onComplete"); completed.countDown(); }
        @Override public void onTimeout(AsyncEvent event) throws IOException { events.add("onTimeout"); }
        @Override public void onError(AsyncEvent event) { events.add("onError"); }
        @Override public void onStartAsync(AsyncEvent event) { events.add("onStartAsync"); }
    }

    /** An error page echoing the status code the container published. */
    private static final HttpServlet ERROR_PAGE = new HttpServlet() {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("err:" + req.getAttribute("jakarta.servlet.error.status_code"));
        }
    };

    @Test
    void timeoutDispatchesTheErrorPage() throws Exception {
        var recorder = new Recorder();
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                AsyncContext ac = req.startAsync();
                ac.setTimeout(50);
                ac.addListener(recorder);
            }
        };
        startWithErrorPage(s, "/timeout");

        HttpResponse<String> r = get("/timeout");
        assertEquals(500, r.statusCode());
        assertEquals("err:500", r.body());
        assertEquals(List.of("onTimeout", "onComplete"), recorder.events);
    }

    @Test
    void timeoutWithoutErrorPageYields500() throws Exception {
        var recorder = new Recorder();
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                AsyncContext ac = req.startAsync();
                ac.setTimeout(50);
                ac.addListener(recorder);
            }
        };
        start(s, "/timeout");

        HttpResponse<String> r = get("/timeout");
        assertEquals(500, r.statusCode());
        assertEquals(List.of("onTimeout", "onComplete"), recorder.events);
    }

    @Test
    void listenerCompletingInOnTimeoutPreventsTheErrorDispatch() throws Exception {
        var seen = new CopyOnWriteArrayList<String>();
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                AsyncContext ac = req.startAsync();
                ac.setTimeout(50);
                ac.addListener(new Recorder() {
                    @Override public void onTimeout(AsyncEvent event) throws IOException {
                        seen.add("onTimeout");
                        event.getAsyncContext().getResponse().getWriter().write("timed-out");
                        event.getAsyncContext().complete();
                    }
                    @Override public void onComplete(AsyncEvent event) { seen.add("onComplete"); }
                });
            }
        };
        startWithErrorPage(s, "/timeout");

        HttpResponse<String> r = get("/timeout");
        assertEquals(200, r.statusCode());
        assertEquals("timed-out", r.body());
        assertEquals(List.of("onTimeout", "onComplete"), seen);
    }

    @Test
    void onCompleteFiresAfterTheAsyncDispatchNotBefore() throws Exception {
        var recorder = new Recorder();
        HttpServlet origin = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                AsyncContext ac = req.startAsync();
                ac.addListener(recorder);
                ac.dispatch("/target");
            }
        };
        HttpServlet target = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getWriter().write("completedAlready=" + recorder.events.contains("onComplete"));
            }
        };
        startMany(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/origin"), origin, "O"),
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/target"), target, "T")));

        HttpResponse<String> r = get("/origin");
        assertEquals("completedAlready=false", r.body());
        assertTrue(recorder.completed.await(2, TimeUnit.SECONDS), "onComplete never fired");
        assertEquals(List.of("onComplete"), recorder.events);
    }

    @Test
    void onStartAsyncOnSecondCycle() throws Exception {
        var recorder = new Recorder();
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                if (req.getDispatcherType() == DispatcherType.REQUEST) {
                    AsyncContext ac = req.startAsync();
                    ac.addListener(recorder);
                    ac.dispatch();
                } else {
                    // A new cycle: the first cycle's listener hears onStartAsync and is not carried over.
                    AsyncContext ac = req.startAsync();
                    resp.getWriter().write("second");
                    ac.complete();
                }
            }
        };
        start(s, "/again");

        HttpResponse<String> r = get("/again");
        assertEquals("second", r.body());
        assertEquals(List.of("onStartAsync"), recorder.events);
    }

    @Test
    void startRunnableExceptionFiresOnError() throws Exception {
        var recorder = new Recorder();
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                AsyncContext ac = req.startAsync();
                ac.addListener(recorder);
                ac.start(() -> { throw new IllegalStateException("boom"); });
            }
        };
        start(s, "/boom");

        HttpResponse<String> r = get("/boom");
        assertEquals(500, r.statusCode());
        assertEquals(List.of("onError", "onComplete"), recorder.events);
    }

    /**
     * BUG-20261010-01: an async thread still writing when the timeout fires must not race the
     * pipeline thread. From the timeout on, the stale thread's writes fail; the error response is
     * clean.
     */
    @Test
    void lateAsyncWritesFailOnceTheTimeoutFired() throws Exception {
        var timedOut = new CountDownLatch(1);
        var writerDone = new CompletableFuture<Throwable>();
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                resp.setBufferSize(1 << 20);
                AsyncContext ac = req.startAsync();
                ac.setTimeout(50);
                ac.addListener(new Recorder() {
                    @Override public void onTimeout(AsyncEvent event) { timedOut.countDown(); }
                });
                ac.start(() -> writeUntilFailure(ac, timedOut, writerDone, false));
            }
        };
        start(s, "/late");

        HttpResponse<String> r = get("/late");
        assertEquals(500, r.statusCode());
        assertFalse(r.body().contains("x"), "a stale async write reached the error response: " + r.body());
        assertInstanceOf(IOException.class, writerDone.get(5, TimeUnit.SECONDS));
    }

    /** BUG-20261010-01, committed variant: the live body is aborted and the stale writer stops. */
    @Test
    void aCommittedResponseTimingOutStopsTheStaleWriter() throws Exception {
        var timedOut = new CountDownLatch(1);
        var writerDone = new CompletableFuture<Throwable>();
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                AsyncContext ac = req.startAsync();
                ac.setTimeout(50);
                ac.addListener(new Recorder() {
                    @Override public void onTimeout(AsyncEvent event) { timedOut.countDown(); }
                });
                ac.start(() -> writeUntilFailure(ac, timedOut, writerDone, true));
            }
        };
        start(s, "/late");

        assertThrows(IOException.class, () -> get("/late"));
        assertInstanceOf(IOException.class, writerDone.get(5, TimeUnit.SECONDS));
    }

    /**
     * Writes one byte at a time (flushing each when {@code flush}) until a write fails, at most
     * 1000 writes after the timeout fired; completes {@code done} with the failure, or with
     * {@code null} when every write succeeded.
     */
    private static void writeUntilFailure(AsyncContext ac, CountDownLatch timedOut,
                                          CompletableFuture<Throwable> done, boolean flush) {
        try {
            var out = ac.getResponse().getOutputStream();
            int afterTimeout = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (afterTimeout < 1000 && System.nanoTime() < deadline) {
                out.write('x');
                if (flush) out.flush();
                if (timedOut.getCount() == 0) afterTimeout++;
                else Thread.sleep(1);
            }
            done.complete(null);
        } catch (Throwable t) {
            done.complete(t);
        }
    }

    private void startWithErrorPage(HttpServlet servlet, String pattern) {
        var ctx = new VidocqServletContext("/");
        ctx.setErrorPages(new ErrorPageRegistry().register(500, "/err"));
        startMany(ctx, List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of(pattern), servlet, "S"),
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/err"), ERROR_PAGE, "E")));
    }

    private void start(HttpServlet servlet, String pattern) {
        startMany(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of(pattern), servlet, "S")));
    }

    private void startMany(List<ServletDispatcher.Mapping> mappings) {
        startMany(new VidocqServletContext("/"), mappings);
    }

    private void startMany(VidocqServletContext ctx, List<ServletDispatcher.Mapping> mappings) {
        var bridge = new ChappeServletBridge(new ServletDispatcher(mappings),
                new FilterRegistry(List.of()), ctx, null, "/");
        var r = TestServerLauncher.start(bridge);
        this.server = r.server;
        this.port = r.port;
    }

    private HttpResponse<String> get(String p) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + p))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
    }
}
