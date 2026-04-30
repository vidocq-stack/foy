package io.vidocq.foy.internal.async;

import io.vidocq.foy.internal.container.VidocqServletContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AsyncContextImplTest {

    @Test
    void completeReleasesAwait() throws Exception {
        var ctx = newAsync();
        CompletableFuture<Void> future = CompletableFuture.runAsync(ctx::awaitCompletion);
        Thread.sleep(20);
        assertFalse(future.isDone());
        ctx.complete();
        future.get(1, TimeUnit.SECONDS);
        assertTrue(ctx.isCompleted());
    }

    @Test
    void dispatchRecordsPathAndReleases() throws Exception {
        var ctx = newAsync();
        CompletableFuture<Void> future = CompletableFuture.runAsync(ctx::awaitCompletion);
        ctx.dispatch("/target");
        future.get(1, TimeUnit.SECONDS);
        assertTrue(ctx.hasDispatch());
        assertEquals("/target", ctx.dispatchPath());
    }

    @Test
    void timeoutTriggersOnTimeoutAndReleases() {
        var ctx = newAsync();
        ctx.setTimeout(50);
        AtomicBoolean fired = new AtomicBoolean();
        ctx.addListener(new AsyncListener() {
            @Override public void onComplete(AsyncEvent event) {}
            @Override public void onTimeout(AsyncEvent event) { fired.set(true); }
            @Override public void onError(AsyncEvent event) {}
            @Override public void onStartAsync(AsyncEvent event) {}
        });
        long start = System.currentTimeMillis();
        ctx.awaitCompletion();
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 45, "elapsed=" + elapsed);
        assertTrue(ctx.timedOut());
        assertTrue(fired.get());
    }

    @Test
    void onCompleteFiresOnlyForExplicitCompleteNotTimeout() {
        var ctx = newAsync();
        ctx.setTimeout(30);
        AtomicInteger completeCount = new AtomicInteger();
        ctx.addListener(new AsyncListener() {
            @Override public void onComplete(AsyncEvent event) { completeCount.incrementAndGet(); }
            @Override public void onTimeout(AsyncEvent event) {}
            @Override public void onError(AsyncEvent event) {}
            @Override public void onStartAsync(AsyncEvent event) {}
        });
        ctx.awaitCompletion();
        assertEquals(0, completeCount.get());
    }

    @Test
    void startRunsRunnableOnVirtualThread() throws Exception {
        var ctx = newAsync();
        CompletableFuture<Thread> captured = new CompletableFuture<>();
        ctx.start(() -> captured.complete(Thread.currentThread()));
        Thread t = captured.get(1, TimeUnit.SECONDS);
        assertTrue(t.isVirtual(), "expected virtual thread: " + t);
    }

    @Test
    void setTimeoutZeroOrNegativeBlocksUntilComplete() throws Exception {
        var ctx = newAsync();
        ctx.setTimeout(0);
        CompletableFuture<Void> future = CompletableFuture.runAsync(ctx::awaitCompletion);
        Thread.sleep(30);
        assertFalse(future.isDone());
        ctx.complete();
        future.get(1, TimeUnit.SECONDS);
    }

    private static AsyncContextImpl newAsync() {
        return new AsyncContextImpl(null, null, new VidocqServletContext("/"), true);
    }
}
