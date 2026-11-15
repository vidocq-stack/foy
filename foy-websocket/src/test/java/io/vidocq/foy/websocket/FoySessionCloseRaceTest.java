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
import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Endpoint.onClose} hears the reason of the Close that went out first (Jakarta WebSocket 2.2 section 2.1.5),
 * also when a close of the session races a Close of the peer or of the undeploy: the session gives up its own reason
 * only when the transport answers that another Close came first, or reports a Close of the peer while its close has
 * not answered, and {@code onClose} waits a bounded time for an answer that is under way (BUG-20261115-08, its former
 * "Known window"). A cut keeps the reason of a close of this side that was under way: it closed first.
 */
class FoySessionCloseRaceTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    /** The bound of {@code onClose}'s wait for a close in flight, long enough that no test reaches it by chance. */
    private static final Duration NO_BOUND_REACHED = Duration.ofSeconds(30);
    /** The bound of {@code onClose}'s wait for the tests of what happens once it is reached. */
    private static final Duration SHORT_BOUND = Duration.ofMillis(50);
    /** The reason of the undeploy's close, which the transport reports. */
    private static final String STOPPING = "the application is stopping";
    private static final String PEER_LEAVES = "the peer leaves";
    private static final String IDLE_TIMEOUT = "idle timeout";

    private final FakePeer peer = new FakePeer();
    private final Recorder endpoint = new Recorder();
    private final FoySession session = TestSessions.open(endpoint, peer, NO_BOUND_REACHED);

    /** Who closes the session, and what {@code onClose} hears when that Close went out first. */
    enum Closer {
        /** {@code Session.close(...)}: the application's own code and reason. */
        APPLICATION(4000, "bye") {
            @Override
            void close(FoySession session) throws IOException {
                session.close(new CloseReason(CloseCodes.getCloseCode(4000), "bye"));
            }
        },
        /** The idle timeout: 1001 on the wire, 1006 with its reason to the endpoint. */
        CONTAINER(CloseCodes.CLOSED_ABNORMALLY.getCode(), IDLE_TIMEOUT) {
            @Override
            void close(FoySession session) {
                session.closeByContainer(new CloseReason(CloseCodes.GOING_AWAY, IDLE_TIMEOUT));
            }
        };

        private final int heardCode;
        private final String heardReason;

        Closer(int heardCode, String heardReason) {
            this.heardCode = heardCode;
            this.heardReason = heardReason;
        }

        abstract void close(FoySession session) throws IOException;
    }

    /** The peer's Close came between the session's check and its send: the endpoint hears the peer's. */
    @ParameterizedTest
    @EnumSource(Closer.class)
    void aCloseThatThePeersCloseCameBeforeHearsThePeersClose(Closer closer) throws IOException {
        peer.anotherCloseCameFirst();

        closer.close(session);
        session.onClose(CloseCodes.GOING_AWAY.getCode(), PEER_LEAVES);

        assertEquals(-1, peer.closeCode(), "the session's Close never went out");
        assertHeardOnce(CloseCodes.GOING_AWAY.getCode(), PEER_LEAVES);
    }

    /**
     * The peer's Close came first, and the transport reports it while the session's close has not answered yet:
     * {@code onClose} waits for that answer, then the endpoint hears the peer's Close, once.
     */
    @ParameterizedTest
    @EnumSource(Closer.class)
    void thePeersCloseReportedWhileTheSessionsCloseIsInFlightIsHeardOnce(Closer closer) throws Exception {
        peer.anotherCloseCameFirst();

        raceTheReportWithTheAnswer(closer, CloseCodes.GOING_AWAY.getCode(), PEER_LEAVES);

        assertHeardOnce(CloseCodes.GOING_AWAY.getCode(), PEER_LEAVES);
    }

    /**
     * The session's Close went out first, and the peer's answer is reported before the session's close answered:
     * the endpoint hears the session's own reason, not the peer's answer.
     */
    @ParameterizedTest
    @EnumSource(Closer.class)
    void theSessionsCloseThatWentOutFirstIsHeardWhenThePeersAnswerIsReportedFirst(Closer closer) throws Exception {
        raceTheReportWithTheAnswer(closer, CloseCodes.NORMAL_CLOSURE.getCode(), "the peer's answer");

        assertHeardOnce(closer.heardCode, closer.heardReason);
    }

    /**
     * The undeploy's Close went out between the session's check and its send: the session's Close never went
     * out, and the endpoint hears the undeploy's close, which the transport reports.
     */
    @Test
    void aSessionCloseAfterTheUndeploysCloseWentOutHearsTheUndeploy() {
        peer.anotherCloseCameFirst();

        session.closeByContainer(new CloseReason(CloseCodes.GOING_AWAY, IDLE_TIMEOUT));
        session.onClose(CloseCodes.CLOSED_ABNORMALLY.getCode(), STOPPING);

        assertEquals(-1, peer.closeCode(), "the session's Close never went out");
        TestSessions.assertClosedByTheContainer(endpoint, STOPPING);
    }

    /**
     * The connection is cut while the session's close waits to write its frame, behind a send the peer does not
     * read (a send timeout, an idle timeout): no Close went out, the peer still reads as open, and {@code onClose}
     * does not wait for a close that may never answer. This side closed first, so the endpoint hears its reason
     * at once, as the table of the close codes says, not the cut's empty one.
     */
    @ParameterizedTest
    @EnumSource(Closer.class)
    void aCutWhileTheSessionsCloseWaitsForItsFrameIsHeardAtOnceWithItsReason(Closer closer) throws Exception {
        peer.holdCloseFrames();
        Thread closing = Thread.ofVirtual().start(() -> closeQuietly(closer));
        assertTrue(peer.closeHeld.await(WAIT.toSeconds(), SECONDS), "the close must start");

        Thread ending = Thread.ofVirtual().start(() -> session.onClose(CloseCodes.CLOSED_ABNORMALLY.getCode(), ""));

        assertTrue(ending.join(WAIT), "onClose waited for a close whose frame had not gone out");
        assertHeardOnce(closer.heardCode, closer.heardReason);
        peer.releaseClose();
        assertTrue(closing.join(WAIT), "the close must return");
        assertEquals(1, endpoint.closes.size(), "onClose runs once");
    }

    /**
     * The write of the session's Close frame fails, the connection broken: this side closed first, so a cut
     * reported afterwards gives the endpoint this side's reason, as the undeploy's close that fails does.
     */
    @ParameterizedTest
    @EnumSource(Closer.class)
    void aCloseWhoseWriteFailedKeepsItsReasonWhenTheCutIsReported(Closer closer) {
        peer.failCloses();

        closeQuietly(closer);
        session.onClose(CloseCodes.CLOSED_ABNORMALLY.getCode(), "");

        assertHeardOnce(closer.heardCode, closer.heardReason);
    }

    /**
     * A close on an interrupted thread never started: the transport wrote nothing and threw, and the thread is still
     * interrupted. It leaves no reason behind, so a later close still sends its frame, and the endpoint hears that one.
     */
    @Test
    void anInterruptedCloseLeavesTheSessionFreeToCloseAgain() throws Exception {
        var thrown = new CompletableFuture<IOException>();
        var stillInterrupted = new CompletableFuture<Boolean>();
        Thread interrupted = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            try {
                Closer.APPLICATION.close(session);
                thrown.complete(null);
            } catch (IOException e) {
                thrown.complete(e);
            }
            stillInterrupted.complete(Thread.currentThread().isInterrupted());
        });
        assertTrue(interrupted.join(WAIT), "the interrupted close must return");
        assertInstanceOf(InterruptedIOException.class, thrown.getNow(null), "the interrupted close throws");
        assertTrue(stillInterrupted.getNow(false), "the interrupt status stays set");
        assertEquals(-1, peer.closeCode(), "the interrupted close sent nothing");

        session.close(new CloseReason(CloseCodes.getCloseCode(4001), "again"));
        session.onClose(CloseCodes.NORMAL_CLOSURE.getCode(), "the peer's answer");

        assertEquals(4001, peer.closeCode(), "the second close sends its frame");
        assertHeardOnce(4001, "again");
    }

    /**
     * The session's close fails in its write, and the transport reports a close it names (chappe's Close on a
     * protocol error, which failed too on the broken connection) before that close settled: {@code onClose} waits
     * for it, and the session's close, which came first, gives the reason.
     */
    @Test
    void aNamedCloseReportedBeforeTheSessionsFailedCloseSettledHearsTheSession() throws Exception {
        peer.holdCloseFrames();
        peer.failCloses();
        Thread closing = Thread.ofVirtual().start(() -> closeQuietly(Closer.CONTAINER));
        assertTrue(peer.closeHeld.await(WAIT.toSeconds(), SECONDS), "the close must start");

        Thread ending = Thread.ofVirtual().start(
                () -> session.onClose(CloseCodes.CLOSED_ABNORMALLY.getCode(), "protocol error: bad frame"));
        awaitParkedOrDone(ending);
        assertEquals(List.of(), endpoint.closes, "onClose reported before the session's close settled");

        peer.releaseClose();
        assertTrue(closing.join(WAIT), "the close must return");
        assertTrue(ending.join(WAIT), "onClose must return");
        assertHeardOnce(Closer.CONTAINER.heardCode, Closer.CONTAINER.heardReason);
    }

    /** A close whose write failed yields to a Close of the peer the transport read: the peer closed first. */
    @Test
    void aCloseWhoseWriteFailedYieldsToThePeersCloseRead() {
        peer.failCloses();

        closeQuietly(Closer.CONTAINER);
        session.onClose(CloseCodes.GOING_AWAY.getCode(), PEER_LEAVES);

        assertHeardOnce(CloseCodes.GOING_AWAY.getCode(), PEER_LEAVES);
    }

    /**
     * A close that took effect but never answers holds {@code onClose} back only until its bound: once it is over,
     * a cut gives the endpoint this side's reason, the close being under way, once.
     */
    @ParameterizedTest
    @EnumSource(Closer.class)
    void aCloseThatNeverAnswersKeepsItsReasonAgainstACutOnceTheWaitIsOver(Closer closer) throws Exception {
        assertHeardOnceTheWaitIsOver(new FakePeer(), closer, CloseCodes.CLOSED_ABNORMALLY.getCode(), "",
                closer.heardCode, closer.heardReason);
    }

    /**
     * The undeploy's Close went out, then a write that is not the session's (chappe's automatic Pong of a Ping the
     * peer sent before its Close) holds the transport on a peer that does not read, with the session's close queued
     * behind it: the undeploy ends the session meanwhile. {@code onClose} waits for that close only until its
     * bound, and the endpoint hears the undeploy's close, which the transport names, not the session's own reason.
     */
    @Test
    void aCloseHeldBehindAWriteOnceTheUndeploysCloseWentOutHearsTheUndeployWithinTheBound() throws Exception {
        var bounded = new FakePeer();
        bounded.anotherCloseCameFirst();
        assertHeardOnceTheWaitIsOver(bounded, Closer.CONTAINER, CloseCodes.CLOSED_ABNORMALLY.getCode(), STOPPING,
                CloseCodes.CLOSED_ABNORMALLY.getCode(), STOPPING);
    }

    /**
     * An interrupt of the thread that reports the end stops {@code onClose}'s wait for a close in flight at once and
     * stays set; the rule of a close not answered applies: against a cut, this side's reason.
     */
    @Test
    void anInterruptEndsTheWaitForACloseInFlightAndStaysSet() throws Exception {
        peer.holdCloseAnswers();
        Thread closing = Thread.ofVirtual().start(() -> closeQuietly(Closer.CONTAINER));
        assertTrue(peer.closeHeld.await(WAIT.toSeconds(), SECONDS), "the close must start");
        var stillInterrupted = new CompletableFuture<Boolean>();
        try {
            Thread ending = Thread.ofVirtual().start(() -> {
                Thread.currentThread().interrupt();
                session.onClose(CloseCodes.CLOSED_ABNORMALLY.getCode(), "");
                stillInterrupted.complete(Thread.currentThread().isInterrupted());
            });

            assertTrue(ending.join(WAIT), "onClose kept waiting once interrupted");
            assertTrue(stillInterrupted.getNow(false), "onClose cleared the interrupt");
            assertHeardOnce(Closer.CONTAINER.heardCode, Closer.CONTAINER.heardReason);
        } finally {
            peer.releaseClose();
            assertTrue(closing.join(WAIT), "the close must return");
        }
    }

    /** Once the wait for a close that never answers is over, a Close of the peer the transport read is heard. */
    @Test
    void aCloseThatNeverAnswersYieldsToThePeersCloseOnceTheWaitIsOver() throws Exception {
        int peerCode = CloseCodes.GOING_AWAY.getCode();
        assertHeardOnceTheWaitIsOver(new FakePeer(), Closer.APPLICATION, peerCode, PEER_LEAVES, peerCode, PEER_LEAVES);
    }

    /**
     * A close while another is in flight, its frame not out yet, or after it went out, sends nothing and does not
     * wait for the first: the session gives the transport one close, and the first one's reason is heard.
     */
    @Test
    void aSecondCloseSendsNothingWhileTheFirstIsInFlightNorAfterIt() throws Exception {
        peer.holdCloseFrames();
        Thread closing = Thread.ofVirtual().start(() -> closeQuietly(Closer.APPLICATION));
        assertTrue(peer.closeHeld.await(WAIT.toSeconds(), SECONDS), "the close must start");

        Thread second = Thread.ofVirtual().start(() -> closeQuietly(Closer.CONTAINER));
        assertTrue(second.join(WAIT), "a second close waited for the first");
        peer.releaseClose();
        assertTrue(closing.join(WAIT), "the close must return");
        Closer.CONTAINER.close(session);
        session.onClose(CloseCodes.NORMAL_CLOSURE.getCode(), "");

        assertEquals(4000, peer.closeCode(), "the application's Close went out");
        assertHeardOnce(Closer.APPLICATION.heardCode, Closer.APPLICATION.heardReason);
    }

    /**
     * Holds the answer of {@code closer}'s close once it took effect, delivers the transport's report from
     * another thread meanwhile, checks that the endpoint heard nothing before the answer, then lets it come.
     */
    private void raceTheReportWithTheAnswer(Closer closer, int code, String reason) throws Exception {
        peer.holdCloseAnswers();
        Thread closing = Thread.ofVirtual().start(() -> closeQuietly(closer));
        assertTrue(peer.closeHeld.await(WAIT.toSeconds(), SECONDS), "the close must start");

        Thread ending = Thread.ofVirtual().start(() -> session.onClose(code, reason));
        awaitParkedOrDone(ending);
        assertEquals(List.of(), endpoint.closes, "onClose reported a close whose answer it had not heard");

        peer.releaseClose();
        assertTrue(closing.join(WAIT), "the close must return");
        assertTrue(ending.join(WAIT), "onClose must return");
    }

    /**
     * Opens a session on {@code bounded} whose {@code onClose} waits {@link #SHORT_BOUND} at most, starts
     * {@code closer}'s close and holds its answer for good, reports {@code code} and {@code reason}, and checks that
     * {@code onClose} returned within the test's wait, with {@code heardCode} and {@code heardReason}, once.
     */
    private static void assertHeardOnceTheWaitIsOver(FakePeer bounded, Closer closer, int code, String reason,
                                                     int heardCode, String heardReason) throws Exception {
        var heard = new Recorder();
        var boundedSession = TestSessions.open(heard, bounded, SHORT_BOUND);
        bounded.holdCloseAnswers();
        Thread closing = Thread.ofVirtual().start(() -> closeQuietly(closer, boundedSession));
        assertTrue(bounded.closeHeld.await(WAIT.toSeconds(), SECONDS), "the close must start");
        try {
            Thread ending = Thread.ofVirtual().start(() -> boundedSession.onClose(code, reason));

            assertTrue(ending.join(WAIT), "onClose waited past its bound for a close that never answers");
            assertEquals(1, heard.closes.size(), heard.closes::toString);
            assertEquals(heardCode, heard.closes.getFirst().getCloseCode().getCode(), heard.closes::toString);
            assertEquals(heardReason, heard.closes.getFirst().getReasonPhrase());
        } finally {
            bounded.releaseClose();
            assertTrue(closing.join(WAIT), "the close must return");
        }
        assertEquals(1, heard.closes.size(), "onClose runs once");
    }

    private void closeQuietly(Closer closer) {
        closeQuietly(closer, session);
    }

    private static void closeQuietly(Closer closer, FoySession closed) {
        try {
            closer.close(closed);
        } catch (IOException _) {
            // the endpoint's onClose tells what happened
        }
    }

    private void assertHeardOnce(int code, String reason) {
        assertEquals(1, endpoint.closes.size(), endpoint.closes::toString);
        CloseReason heard = endpoint.closes.getFirst();
        assertEquals(code, heard.getCloseCode().getCode(), heard::toString);
        assertEquals(reason, heard.getReasonPhrase());
    }

    /** Waits until {@code thread} parks, or ends: an {@code onClose} that does not wait ends at once. */
    private static void awaitParkedOrDone(Thread thread) throws InterruptedException {
        var never = new CountDownLatch(1); // never counted down: each wait on it is a pause
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!parkedOrDone(thread.getState())) {
            assertTrue(System.nanoTime() - deadline < 0, "onClose neither waited nor returned");
            never.await(1, MILLISECONDS);
        }
    }

    private static boolean parkedOrDone(Thread.State state) {
        return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING
                || state == Thread.State.TERMINATED;
    }
}
