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
package io.vidocq.foy.websocket;

import io.vidocq.foy.websocket.TestSessions.Recorder;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.EncodeException;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncRemoteTest {

    private static final long WAIT = 10;
    private static final int THREADS = 8;
    private static final int PER_THREAD = 125;

    private final FakePeer peer = new FakePeer();
    private final Recorder endpoint = new Recorder();
    private FoySession session;

    @AfterEach
    void closeTheSession() {
        peer.releaseWrites();
        if (session != null) {
            session.onClose(CloseCodes.NORMAL_CLOSURE.getCode(), "");
        }
    }

    @Test
    void asyncSendsKeepTheirOrder() throws InterruptedException {
        session = TestSessions.open(endpoint, peer);
        RemoteEndpoint.Async async = session.getAsyncRemote();
        var results = new CountDownLatch(THREADS * PER_THREAD);
        var failures = new CopyOnWriteArrayList<SendResult>();

        try (var senders = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < THREADS; t++) {
                int thread = t;
                senders.submit(() -> {
                    for (int n = 0; n < PER_THREAD; n++) {
                        async.sendText(thread + ":" + n, result -> {
                            if (!result.isOK()) {
                                failures.add(result);
                            }
                            results.countDown();
                        });
                    }
                });
            }
        }

        assertTrue(results.await(WAIT, SECONDS));
        assertEquals(List.of(), failures);
        assertEquals(THREADS * PER_THREAD, peer.messages.size());
        assertFalse(peer.overlapped(), "one send at a time");
        Map<String, Integer> last = new ConcurrentHashMap<>();
        for (String message : peer.texts()) {
            String[] parts = message.split(":");
            int n = Integer.parseInt(parts[1]);
            assertEquals(last.getOrDefault(parts[0], -1) + 1, n, "the order of sender " + parts[0]);
            last.put(parts[0], n);
        }
    }

    @Test
    void concurrentBasicAndAsyncSendsKeepEachMessageWhole() throws InterruptedException {
        session = TestSessions.open(endpoint, peer);
        RemoteEndpoint.Async async = session.getAsyncRemote();
        RemoteEndpoint.Basic basic = session.getBasicRemote();
        var results = new CountDownLatch(2 * PER_THREAD);
        var expected = ConcurrentHashMap.<String>newKeySet();

        try (var senders = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < 2; t++) {
                String prefix = "async" + t + "-";
                senders.submit(() -> {
                    for (int n = 0; n < PER_THREAD; n++) {
                        expected.add(prefix + n);
                        async.sendText(prefix + n, result -> results.countDown());
                    }
                });
            }
            senders.submit(() -> {
                for (int n = 0; n < PER_THREAD; n++) {
                    String message = "basic-" + n;
                    expected.add(message);
                    sendQuietly(() -> basic.sendText(message));
                }
            });
            senders.submit(() -> {
                for (int n = 0; n < PER_THREAD; n++) {
                    expected.add("partial-" + n + "-end");
                    int part = n;
                    sendQuietly(() -> {
                        basic.sendText("partial-", false);
                        basic.sendText(part + "-", false);
                        basic.sendText("end", true);
                    });
                }
            });
        }

        assertTrue(results.await(WAIT, SECONDS));
        assertEquals(expected, new HashSet<>(peer.texts()), "every message arrives whole");
        assertEquals(expected.size(), peer.messages.size(), "and once");
    }

    @Test
    void aSendPastItsTimeoutFailsAndClosesTheSession() throws InterruptedException {
        session = TestSessions.open(endpoint, peer);
        RemoteEndpoint.Async async = session.getAsyncRemote();
        async.setSendTimeout(100);
        peer.blockWrites();
        var result = new CompletableResult();

        async.sendText("never written", result);

        assertTrue(result.done.await(WAIT, SECONDS));
        assertFalse(result.value.isOK());
        assertInstanceOf(SocketTimeoutException.class, result.value.getException());
        assertSame(session, result.value.getSession());
        assertTrue(peer.closed.await(WAIT, SECONDS));
        assertEquals(CloseCodes.GOING_AWAY.getCode(), peer.closeCode());
    }

    /** A send handler that throws an {@code Error} at its send timeout does not keep the session open. */
    @Test
    void anErrorFromASendHandlerAtItsTimeoutStillClosesTheSession() throws InterruptedException {
        session = TestSessions.open(endpoint, peer);
        RemoteEndpoint.Async async = session.getAsyncRemote();
        async.setSendTimeout(100);
        peer.blockWrites();
        var timedOut = new CountDownLatch(1);

        async.sendText("never written", result -> {
            timedOut.countDown();
            throw new AssertionError("a send handler that fails an assertion");
        });

        assertTrue(timedOut.await(WAIT, SECONDS));
        assertTrue(peer.closed.await(WAIT, SECONDS), "the send timeout did not close the session");
        assertEquals(CloseCodes.GOING_AWAY.getCode(), peer.closeCode());
    }

    @Test
    void queuedSendsFailWhenThePeerVanishes() throws InterruptedException {
        session = TestSessions.open(endpoint, peer);
        RemoteEndpoint.Async async = session.getAsyncRemote();
        peer.blockWrites();
        List<CompletableResult> results = new ArrayList<>();
        for (int n = 0; n < 5; n++) {
            var result = new CompletableResult();
            results.add(result);
            async.sendText("message " + n, result);
        }
        assertTrue(peer.writing.await(WAIT, SECONDS), "the first send is being written");

        peer.vanish();
        session.onClose(CloseCodes.CLOSED_ABNORMALLY.getCode(), "");

        for (CompletableResult result : results) {
            assertTrue(result.done.await(WAIT, SECONDS));
            assertFalse(result.value.isOK());
            assertInstanceOf(IOException.class, result.value.getException());
        }
        assertEquals(1, endpoint.closes.size());
        assertEquals(CloseCodes.CLOSED_ABNORMALLY, endpoint.closes.getFirst().getCloseCode());
        Thread drainer = results.getFirst().thread;
        assertTrue(drainer.join(Duration.ofSeconds(WAIT)), "the drainer thread ended");
        var late = new CompletableResult();
        async.sendText("after the close", late);
        assertTrue(late.done.await(WAIT, SECONDS));
        assertFalse(late.value.isOK());
    }

    @Test
    void theFuturesCompleteFromTheirSend() throws InterruptedException, ExecutionException, TimeoutException {
        session = TestSessions.open(endpoint, peer);
        RemoteEndpoint.Async async = session.getAsyncRemote();
        Object unknown = new Object();

        Future<Void> text = async.sendText("text");
        Future<Void> binary = async.sendBinary(ByteBuffer.wrap(new byte[] {1}));
        Future<Void> object = async.sendObject(7);
        Future<Void> refused = async.sendObject(unknown);

        assertNull(text.get(WAIT, SECONDS));
        assertNull(binary.get(WAIT, SECONDS));
        assertNull(object.get(WAIT, SECONDS));
        var thrown = assertThrows(ExecutionException.class, () -> refused.get(WAIT, SECONDS));
        assertInstanceOf(EncodeException.class, thrown.getCause());
        assertEquals(List.of("text", "7"), peer.texts());
    }

    @Test
    void aSendHandlerRunsWithTheApplicationLoader() throws InterruptedException {
        var applicationLoader = new ClassLoader(getClass().getClassLoader()) { };
        Thread opener = Thread.ofVirtual().unstarted(() -> session = TestSessions.open(endpoint, peer));
        opener.setContextClassLoader(applicationLoader);
        opener.start();
        assertTrue(opener.join(Duration.ofSeconds(WAIT)));
        var loaders = new CopyOnWriteArrayList<ClassLoader>();
        var done = new CountDownLatch(1);

        session.getAsyncRemote().sendBinary(ByteBuffer.wrap(new byte[] {1}), result -> {
            loaders.add(Thread.currentThread().getContextClassLoader());
            done.countDown();
        });

        assertTrue(done.await(WAIT, SECONDS));
        assertEquals(List.of(applicationLoader), loaders);
        assertEquals(1, peer.messages.size());
    }

    @Test
    void anEncodeExceptionAndAThrowingHandlerLeaveTheQueueRunning() throws InterruptedException {
        session = TestSessions.open(endpoint, peer);
        RemoteEndpoint.Async async = session.getAsyncRemote();
        var refused = new CompletableResult();
        var next = new CompletableResult();

        async.sendObject(new Object(), refused);
        async.sendText("first", result -> {
            throw new IllegalStateException("a broken send handler");
        });
        async.sendObject("second", next);

        assertTrue(refused.done.await(WAIT, SECONDS));
        assertInstanceOf(EncodeException.class, refused.value.getException());
        assertTrue(next.done.await(WAIT, SECONDS));
        assertTrue(next.value.isOK());
        assertEquals(List.of("first", "second"), peer.texts());
    }

    /**
     * An {@code Error} from a send handler ends its drainer, not the queue: the send queued behind it
     * and a send queued afterwards still complete.
     */
    @Test
    void anErrorFromASendHandlerLeavesTheQueueRunning() throws InterruptedException {
        session = TestSessions.open(endpoint, peer);
        RemoteEndpoint.Async async = session.getAsyncRemote();
        var queued = new CountDownLatch(1);
        var behind = new CompletableResult();
        var later = new CompletableResult();

        async.sendText("first", result -> {
            awaitQuietly(queued);
            throw new AssertionError("a send handler that fails an assertion");
        });
        async.sendText("second", behind);
        queued.countDown();

        assertTrue(behind.done.await(WAIT, SECONDS), "the send queued behind the Error never completed");
        assertTrue(behind.value.isOK());
        async.sendText("third", later);
        assertTrue(later.done.await(WAIT, SECONDS), "a send queued after the Error never completed");
        assertEquals(List.of("first", "second", "third"), peer.texts());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            assertTrue(latch.await(WAIT, SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A send handler that keeps its result and the thread it ran on. */
    private static final class CompletableResult implements SendHandler {
        final CountDownLatch done = new CountDownLatch(1);
        volatile SendResult value;
        volatile Thread thread;

        @Override
        public void onResult(SendResult result) {
            value = result;
            thread = Thread.currentThread();
            done.countDown();
        }
    }

    @FunctionalInterface
    private interface Send {
        void run() throws IOException;
    }

    private static void sendQuietly(Send send) {
        try {
            send.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
