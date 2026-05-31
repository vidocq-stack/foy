package io.vidocq.foy.internal.async;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link AsyncContext} Jakarta Servlet 6.1.
 *
 * <p>Contract: the bridge calls {@link #awaitCompletion()} after the return of
 * service() of the servlet, which blocks the Chappe thread until:</p>
 * <ul>
 *   <li>{@link #complete()} — the response is materialized as </li>
 *   <li>{@link #dispatch(String)} — a redispatch {@link jakarta.servlet.DispatcherType#ASYNC} is planned</li>
 *   <li>timeout of {@link #setTimeout timeout} — triggers onTimeout</li>
 * </ul>
 *
 * <p>The {@link AsyncListener} receive onStartAsync events (not emitted on the first
 * startAsync, only if {@code startAsync} is re-called after a dispatch, cf. §2.3.3.3),
 * onComplete, onTimeout, onError.</p>
 */
public final class AsyncContextImpl implements AsyncContext {

    public static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private static final ExecutorService VIRTUAL_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final ServletRequest request;
    private final ServletResponse response;
    private final ServletContext servletContext;
    private final boolean originalRequestAndResponse;
    private final List<ListenerRegistration> listeners = new ArrayList<>();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private volatile long timeoutMs = DEFAULT_TIMEOUT_MS;
    private volatile String dispatchPath;
    private volatile ServletContext dispatchContext;
    private volatile boolean completed;
    private volatile boolean timedOut;

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
            uri = h.getRequestURI();
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
        if (completed) throw new IllegalStateException("async already completed");
        this.dispatchPath = path;
        this.dispatchContext = ctx;
        completeInternal(false);
    }

    @Override public void complete() {
        if (completed) return;
        completeInternal(false);
    }

    @Override public void start(Runnable run) {
        VIRTUAL_EXECUTOR.execute(() -> {
            try { run.run(); }
            catch (Throwable t) { fireOnError(t); }
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
        // §2.3.3.4 : createListener doit throw ServletException en cas d'échec
        // d'instanciation (le TCK asyncListenerTest1/6 en dépend avec ACListenerBad).
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null
                    ? ite.getCause() : e;
            throw new jakarta.servlet.ServletException(
                    "cannot instantiate listener " + clazz.getName() + ": " + cause.getMessage(), cause);
        }
    }

    @Override public void setTimeout(long timeout) { this.timeoutMs = timeout; }
    @Override public long getTimeout() { return timeoutMs; }

    /** Blocks the calling thread until complete(), dispatch() or timesout. */
    public void awaitCompletion() {
        try {
            if (timeoutMs <= 0) {
                completion.get();
            } else {
                completion.get(timeoutMs, TimeUnit.MILLISECONDS);
            }
        } catch (TimeoutException e) {
            timedOut = true;
            fireOnTimeout();
            completeInternal(true);
        } catch (ExecutionException e) {
            fireOnError(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean hasDispatch() { return dispatchPath != null; }
    public String dispatchPath() { return dispatchPath; }
    /** Target context of a cross-context dispatch — null for an intra-context dispatch. */
    public ServletContext dispatchContext() { return dispatchContext; }
    public boolean timedOut() { return timedOut; }
    public boolean isCompleted() { return completed; }

    private void completeInternal(boolean fromTimeout) {
        if (completed) return;
        completed = true;
        if (!fromTimeout) fireOnComplete();
        completion.complete(null);
    }

    private void fireOnComplete() {
        for (var reg : listeners) {
            try { reg.listener().onComplete(newEvent(reg, null)); }
            catch (IOException ignored) {}
        }
    }

    private void fireOnTimeout() {
        for (var reg : listeners) {
            try { reg.listener().onTimeout(newEvent(reg, null)); }
            catch (IOException ignored) {}
        }
    }

    private void fireOnError(Throwable t) {
        for (var reg : listeners) {
            try { reg.listener().onError(newEvent(reg, t)); }
            catch (IOException ignored) {}
        }
    }

    private AsyncEvent newEvent(ListenerRegistration reg, Throwable cause) {
        return new AsyncEvent(this, reg.suppliedReq(), reg.suppliedRes(), cause);
    }
}
