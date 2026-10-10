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
package io.vidocq.foy.internal.async;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link AsyncContext} Jakarta Servlet 6.1: one async cycle (section 2.3.3.3).
 *
 * <p>Lifecycle. The bridge's pipeline thread calls {@link #awaitCycleEnd} once the dispatch that
 * called {@code startAsync} has returned: the timeout clock starts there (a new cycle, started by
 * a new {@code startAsync}, has its own context and its own clock; {@code timeout <= 0} means
 * none). The wait ends on the first of:</p>
 * <ul>
 *   <li>{@link #complete()} &rarr; {@link CycleEnd#COMPLETE};</li>
 *   <li>{@link #dispatch(String)} &rarr; {@link CycleEnd#DISPATCH} (an ASYNC dispatch follows);</li>
 *   <li>the timeout &rarr; {@code onTimeout} on every listener, then {@link CycleEnd#TIMEOUT}
 *       unless a listener called {@code complete()} or {@code dispatch()};</li>
 *   <li>{@link #fail} (the {@link #start} runnable threw, a write failed because the client is
 *       gone) &rarr; {@code onError} on every listener, then {@link CycleEnd#ERROR} unless a
 *       listener called {@code complete()} or {@code dispatch()}.</li>
 * </ul>
 * <p>On {@code TIMEOUT} and {@code ERROR} the container completes the cycle itself (a later
 * {@code complete()} is a no-op, a later {@code dispatch()} an {@link IllegalStateException}) and
 * the bridge performs the error dispatch. {@code onComplete} fires once, at the end of the whole
 * request ({@link #endCycle()}), never inside {@code complete()} or {@code dispatch()}. When a new
 * {@code startAsync} opens another cycle, {@link #handOverTo} fires {@code onStartAsync} and drops
 * the listeners (they must re-register). Listener exceptions are logged and never stop the other
 * listeners.</p>
 *
 * <p>Every way a cycle ends goes through {@link #awaitCycleEnd}: the single place where a later
 * read or write listener (non-blocking I/O) keeps a cycle open until complete, timeout or error.</p>
 */
public final class AsyncContextImpl implements AsyncContext {

    public static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private static final System.Logger LOG = System.getLogger(AsyncContextImpl.class.getName());

    private static final ExecutorService VIRTUAL_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    /** How an async cycle ended, as {@link #awaitCycleEnd} reports it. */
    public enum CycleEnd {
        /** {@code complete()} was called. */
        COMPLETE,
        /** {@code dispatch()} was called: the bridge runs an ASYNC dispatch. */
        DISPATCH,
        /** The timeout expired and no listener completed or dispatched: error dispatch, status 500. */
        TIMEOUT,
        /** {@link #fail} was called and no listener completed or dispatched: error dispatch. */
        ERROR
    }

    private final ServletRequest request;
    private final ServletResponse response;
    private final ServletContext servletContext;
    private final boolean originalRequestAndResponse;
    private final List<ListenerRegistration> listeners = new CopyOnWriteArrayList<>();
    /** Completed by complete(), dispatch() or fail(): wakes {@link #awaitCycleEnd}. */
    private final CompletableFuture<Void> signal = new CompletableFuture<>();
    private volatile long timeoutMs = DEFAULT_TIMEOUT_MS;
    private volatile String dispatchPath;
    private volatile ServletContext dispatchContext;
    /** complete() or dispatch() called, or the container completed the cycle. Guarded by {@code this}. */
    private volatile boolean completed;
    private volatile boolean timedOut;
    /** The failure reported by {@link #fail}. Guarded by {@code this}. */
    private Throwable error;
    /** onComplete fired, or the listeners were handed over to a new cycle. Guarded by {@code this}. */
    private boolean ended;

    private record ListenerRegistration(AsyncListener listener,
                                        ServletRequest suppliedReq,
                                        ServletResponse suppliedRes) {}

    public AsyncContextImpl(ServletRequest request, ServletResponse response,
                            ServletContext servletContext, boolean originalRequestAndResponse) {
        this.request = request;
        this.response = response;
        this.servletContext = servletContext;
        this.originalRequestAndResponse = originalRequestAndResponse;
    }

    @Override public ServletRequest getRequest() { return request; }
    @Override public ServletResponse getResponse() { return response; }
    @Override public boolean hasOriginalRequestAndResponse() { return originalRequestAndResponse; }

    @Override public void dispatch() {
        // §2.3.3.3 : zero-arg dispatch => URI originale (avec queryString) de la request
        // qui a appelé startAsync. Le TCK peut wrapper la request via ServletRequestWrapper
        // (non-Http), on doit unwrap jusqu'au HttpServletRequest sous-jacent.
        jakarta.servlet.http.HttpServletRequest h = unwrapHttp(request);
        String uri = null;
        if (h != null) {
            // The container request's URI is raw (encoded, path parameters kept); the dispatch path
            // must be its canonical, context-relative form (section 3.5.2), found through any
            // application wrapper.
            uri = io.vidocq.foy.internal.bridge.HttpServletRequestImpl.canonicalDispatchPath(request);
            String qs = h.getQueryString();
            if (qs != null && !qs.isEmpty()) uri = uri + "?" + qs;
        }
        dispatch(uri);
    }

    private static jakarta.servlet.http.HttpServletRequest unwrapHttp(ServletRequest r) {
        while (r != null) {
            if (r instanceof jakarta.servlet.http.HttpServletRequest h) return h;
            if (r instanceof jakarta.servlet.ServletRequestWrapper w) r = w.getRequest();
            else return null;
        }
        return null;
    }

    @Override public void dispatch(String path) { dispatch(servletContext, path); }

    @Override public void dispatch(ServletContext ctx, String path) {
        synchronized (this) {
            if (completed) throw new IllegalStateException("async already completed");
            this.dispatchPath = path;
            this.dispatchContext = ctx;
            completed = true;
        }
        signal.complete(null);
    }

    @Override public void complete() {
        synchronized (this) {
            if (completed) return;
            completed = true;
        }
        signal.complete(null);
    }

    /**
     * Reports a failure of the cycle: the {@link #start} runnable threw, or a write failed because
     * the client is gone. Ignored once the cycle is completed or already failed.
     */
    public void fail(Throwable cause) {
        synchronized (this) {
            if (completed || error != null) return;
            error = cause;
        }
        signal.complete(null);
    }

    @Override public void start(Runnable run) {
        VIRTUAL_EXECUTOR.execute(() -> {
            try { run.run(); }
            catch (Throwable t) { fail(t); }
        });
    }

    @Override public void addListener(AsyncListener listener) {
        addListener(listener, request, response);
    }

    @Override public void addListener(AsyncListener listener, ServletRequest req, ServletResponse res) {
        listeners.add(new ListenerRegistration(listener, req, res));
    }

    @Override public <T extends AsyncListener> T createListener(Class<T> clazz)
            throws jakarta.servlet.ServletException {
        // §2.3.3.4: createListener must throw ServletException when instantiation fails
        // (TCK asyncListenerTest1/6 relies on it with ACListenerBad).
        var factory = servletContext instanceof io.vidocq.foy.internal.container.VidocqServletContext v
                ? v.componentFactory()
                : io.vidocq.foy.internal.gen.RegistryComponentFactory.forClassLoader(clazz.getClassLoader());
        return factory.newInstance(clazz);
    }

    @Override public void setTimeout(long timeout) { this.timeoutMs = timeout; }
    @Override public long getTimeout() { return timeoutMs; }

    /**
     * Blocks the pipeline thread until the cycle ends (see the class description) and reports how.
     * {@code onResume} runs as soon as the wait is over, before any listener: the bridge claims the
     * response output there, so a thread of this cycle still writing cannot race the listeners or
     * the error dispatch (BUG-20261010-01).
     */
    public CycleEnd awaitCycleEnd(Runnable onResume) {
        boolean signalled = waitForSignal();
        onResume.run();
        if (!signalled) {
            timedOut = true;
            fireOnTimeout();
        } else {
            Throwable failure;
            synchronized (this) { failure = error; }
            if (failure != null) fireOnError(failure);
        }
        synchronized (this) {
            if (dispatchPath != null) return CycleEnd.DISPATCH;
            if (completed) return CycleEnd.COMPLETE;
            completed = true; // the container completes the cycle
            return timedOut ? CycleEnd.TIMEOUT : CycleEnd.ERROR;
        }
    }

    /** Waits for complete(), dispatch() or fail(); {@code false} when the timeout expired first. */
    private boolean waitForSignal() {
        long timeout = timeoutMs;
        try {
            if (timeout <= 0) signal.get();
            else signal.get(timeout, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
            return true;
        } catch (ExecutionException e) {
            fail(e.getCause()); // not reachable: the signal never completes exceptionally
            return true;
        }
    }

    /** The failure reported by {@link #fail}, or {@code null}. */
    public synchronized Throwable error() { return error; }

    /**
     * The end of the whole async processing of the request: {@code onComplete} on every listener
     * of this cycle, once. No-op when the listeners were handed over to a new cycle.
     */
    public void endCycle() {
        synchronized (this) {
            if (ended) return;
            ended = true;
        }
        for (var reg : listeners) {
            try { reg.listener().onComplete(newEvent(reg, null)); }
            catch (IOException | RuntimeException e) { logListenerFailure("onComplete", e); }
        }
    }

    /**
     * A new {@code startAsync} opened {@code next} on the same request: {@code onStartAsync} on
     * every listener of this cycle, with {@code next} as the event's context; the listeners are not
     * carried over (section 2.3.3.3: they re-register if they want further events).
     */
    public void handOverTo(AsyncContextImpl next) {
        List<ListenerRegistration> previous;
        synchronized (this) {
            if (ended) return;
            ended = true;
            previous = List.copyOf(listeners);
            listeners.clear();
        }
        for (var reg : previous) {
            try { reg.listener().onStartAsync(new AsyncEvent(next, reg.suppliedReq(), reg.suppliedRes())); }
            catch (IOException | RuntimeException e) { logListenerFailure("onStartAsync", e); }
        }
    }

    public boolean hasDispatch() { return dispatchPath != null; }
    public String dispatchPath() { return dispatchPath; }
    /** Target context of a cross-context dispatch — null for an intra-context dispatch. */
    public ServletContext dispatchContext() { return dispatchContext; }
    public boolean timedOut() { return timedOut; }
    public boolean isCompleted() { return completed; }

    private void fireOnTimeout() {
        for (var reg : listeners) {
            try { reg.listener().onTimeout(newEvent(reg, null)); }
            catch (IOException | RuntimeException e) { logListenerFailure("onTimeout", e); }
        }
    }

    private void fireOnError(Throwable t) {
        for (var reg : listeners) {
            try { reg.listener().onError(newEvent(reg, t)); }
            catch (IOException | RuntimeException e) { logListenerFailure("onError", e); }
        }
    }

    private static void logListenerFailure(String event, Throwable e) {
        LOG.log(System.Logger.Level.WARNING, "AsyncListener." + event + " threw", e);
    }

    private AsyncEvent newEvent(ListenerRegistration reg, Throwable cause) {
        return new AsyncEvent(this, reg.suppliedReq(), reg.suppliedRes(), cause);
    }
}
