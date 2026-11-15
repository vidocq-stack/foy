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

import io.vidocq.chappe.api.WebSocket;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.spi.websocket.WebSocketListener;
import io.vidocq.foy.spi.websocket.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The undeploy found the connection open and sends its 1001 Close, and chappe reports the end of the connection
 * before that Close answered: the listener hears the close whose Close went out first (Jakarta WebSocket 2.2
 * section 2.1.5). The undeploy's, 1006 {@code "the application is stopping"}, when chappe answers that its Close
 * went out; the one chappe reports when another Close came first, the peer's here (BUG-20261115-08, its former
 * "Known window"). The takeover waits for that answer before it reports the end. The hook's grace is long here, so
 * the cut at its end never answers in the test's place.
 */
class WebSocketTakeoverUndeployRaceTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    /** The grace of the undeploy hook and how long the held Close waits for the test: far past any wait of a test. */
    private static final Duration NO_BOUND_REACHED = Duration.ofSeconds(30);
    private static final int PEER_CODE = 4001;
    private static final String PEER_REASON = "the peer leaves";

    private final HeldCloseWebSocket ws = new HeldCloseWebSocket();
    private final Listener listener = new Listener();
    private final AtomicInteger destroys = new AtomicInteger();
    private final VidocqServletContext context = new VidocqServletContext("/");
    private Thread undeploy;

    @AfterEach
    void releaseTheClose() throws InterruptedException {
        ws.answer(false);
        if (undeploy != null) undeploy.join(WAIT);
    }

    @Test
    void thePeersCloseThatCameBeforeTheUndeploysIsHeard() throws Exception {
        raceThePeersReportWithTheUndeploysAnswer(false);

        assertEquals(List.of(PEER_CODE + " " + PEER_REASON), listener.closes, "the peer's Close went out first");
        assertEquals(1, destroys.get());
    }

    @Test
    void theUndeploysCloseThatWentOutFirstIsHeardWhateverThePeerAnswers() throws Exception {
        raceThePeersReportWithTheUndeploysAnswer(true);

        assertEquals(List.of("1006 the application is stopping"), listener.closes, "the undeploy's Close went first");
        assertEquals(1, destroys.get());
    }

    /**
     * The undeploy hook stops before its Close answered (an interrupt here, while it waits for that Close): it
     * settles the close as the undeploy's, so an event of the transport does not wait for an answer no one waits
     * for any more, and the listener hears the undeploy's close, once.
     */
    @Test
    void anUndeployHookThatStopsBeforeItsCloseAnsweredLeavesNoEventWaiting() throws Exception {
        var takeover = openTakeover();
        AutoCloseable hook = takeover.undeployHook();
        undeploy = Thread.ofVirtual().start(() -> runQuietly(hook));
        assertTrue(ws.closing.await(WAIT.toSeconds(), SECONDS), "the undeploy must send its Close");
        awaitParkedOrDone(undeploy);

        undeploy.interrupt();
        assertTrue(undeploy.join(WAIT), "the interrupted hook must stop");
        Thread report = Thread.ofVirtual().start(() -> takeover.onClose(ws, PEER_CODE, PEER_REASON));

        assertTrue(report.join(WAIT), "the report waited for a Close the stopped hook no longer waits for");
        assertEquals(List.of("1006 the application is stopping"), listener.closes, "the undeploy closed it");
        assertEquals(1, destroys.get());
    }

    private WebSocketTakeover openTakeover() throws IOException {
        var takeover = new WebSocketTakeover(new Handler(), context, getClass().getClassLoader(), NO_BOUND_REACHED);
        takeover.onOpen(ws, null);
        return takeover;
    }

    /** Runs the undeploy hook; what it throws, the interrupt here, is what the test makes it stop on. */
    private static void runQuietly(AutoCloseable hook) {
        try {
            hook.close();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } catch (Exception _) {
            // the listener tells what happened
        }
    }

    /**
     * Opens the takeover, starts the undeploy, holds its Close until chappe reported the end of the connection
     * from another thread, checks that the listener heard nothing before the Close answered, then answers.
     */
    private void raceThePeersReportWithTheUndeploysAnswer(boolean undeploysCloseSent) throws Exception {
        var takeover = openTakeover();
        undeploy = Thread.ofVirtual().start(() -> context.closeUpgradedConnections(System.nanoTime()));
        assertTrue(ws.closing.await(WAIT.toSeconds(), SECONDS), "the undeploy must send its Close");

        Thread report = Thread.ofVirtual().start(() -> takeover.onClose(ws, PEER_CODE, PEER_REASON));
        awaitParkedOrDone(report);
        assertEquals(List.of(), listener.closes, "the end was reported before the undeploy's Close answered");

        ws.answer(undeploysCloseSent);
        assertTrue(report.join(WAIT), "chappe's onClose must return");
        assertTrue(undeploy.join(WAIT), "the undeploy must end");
        assertEquals(List.of("1001 the application is stopping"), ws.closes, "the undeploy's Close");
    }

    /** Waits until {@code thread} parks, or ends: a report that does not wait ends at once. */
    private static void awaitParkedOrDone(Thread thread) throws InterruptedException {
        var never = new CountDownLatch(1); // never counted down: each wait on it is a pause
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING
                && thread.getState() != Thread.State.TERMINATED) {
            assertTrue(System.nanoTime() - deadline < 0, "the report neither waited nor returned");
            never.await(1, MILLISECONDS);
        }
    }

    private final class Handler implements WebSocketUpgradeHandler {
        @Override
        public String subprotocol() {
            return null;
        }

        @Override
        public WebSocketListener listener() {
            return listener;
        }

        @Override
        public void destroy() {
            destroys.incrementAndGet();
        }
    }

    private static final class Listener implements WebSocketListener {
        final List<String> closes = new CopyOnWriteArrayList<>();

        @Override
        public void onClose(int code, String reason) {
            closes.add(code + " " + reason);
        }
    }

    /**
     * Chappe's connection, whose close is held once it took effect, until the test {@linkplain #answer answers}
     * whether it sent the Close frame; an abort answers it {@code false}, as a cut write does.
     */
    private static final class HeldCloseWebSocket implements WebSocket {
        final CountDownLatch closing = new CountDownLatch(1);
        final List<String> closes = new CopyOnWriteArrayList<>();
        private final CountDownLatch answered = new CountDownLatch(1);
        private volatile boolean sent;
        private volatile boolean open = true;

        void answer(boolean closeSent) {
            if (answered.getCount() > 0) sent = closeSent;
            answered.countDown();
        }

        @Override
        public void close(int code, String reason) throws IOException {
            sendClose(code, reason);
        }

        @Override
        public boolean sendClose(int code, String reason) throws IOException {
            closes.add(code + " " + reason);
            open = false;
            closing.countDown();
            try {
                if (!answered.await(NO_BOUND_REACHED.toSeconds(), SECONDS)) throw new IOException("never answered");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            return sent;
        }

        @Override
        public void abort() {
            open = false;
            answer(false);
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override public void sendText(String message) { /* nothing is sent */ }
        @Override public void sendBinary(ByteBuffer payload) { /* nothing is sent */ }
        @Override public void sendPing(ByteBuffer payload) { /* nothing is sent */ }
        @Override public void sendPong(ByteBuffer payload) { /* nothing is sent */ }
        @Override public InetSocketAddress remoteAddress() { return null; }
        @Override public boolean isSecure() { return false; }
        @Override public String subprotocol() { return null; }
        @Override public Object attribute(String key) { return null; }
        @Override public WebSocket attribute(String key, Object value) { return this; }
    }
}
