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

import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.UpgradedConnection;
import io.vidocq.foy.internal.container.VidocqServletContext;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.WebConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The lifecycle of {@link WebConnectionImpl}: {@code destroy()} only once {@code init} ran. */
@Timeout(30)
class WebConnectionImplTest {

    /** A connection whose input ends at once; records its closes. */
    static final class FakeConnection implements UpgradedConnection {
        final AtomicInteger closes = new AtomicInteger();
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        @Override public InputStream input() { return InputStream.nullInputStream(); }
        @Override public OutputStream output() { return written; }
        @Override public Request request() { return null; }
        @Override public void close() { closes.incrementAndGet(); }
    }

    /** Records init and destroy; init may wait on {@link #release}. */
    static final class RecordingHandler implements HttpUpgradeHandler {
        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch inInit = new CountDownLatch(1);
        final CountDownLatch release;
        final CountDownLatch destroyed = new CountDownLatch(1);

        RecordingHandler(boolean blockInit) {
            release = new CountDownLatch(blockInit ? 1 : 0);
        }

        @Override public void init(WebConnection wc) {
            events.add("init");
            inInit.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            events.add("init returned");
        }

        @Override public void destroy() {
            events.add("destroy");
            destroyed.countDown();
        }
    }

    @Test
    void undeployedBeforeStartNeverCallsInitNorDestroy() {
        var context = new VidocqServletContext("/");
        context.closeUpgradedConnections();
        var conn = new FakeConnection();
        var handler = new RecordingHandler(false);
        new WebConnectionImpl(conn, handler, context, getClass().getClassLoader()).start();
        assertEquals(List.of(), handler.events);
        assertEquals(1, conn.closes.get());
    }

    @Test
    void undeployDuringInitClosesAtOnceAndDestroysAfterInitReturns() throws Exception {
        var context = new VidocqServletContext("/");
        var conn = new FakeConnection();
        var handler = new RecordingHandler(true);
        var wc = new WebConnectionImpl(conn, handler, context, getClass().getClassLoader());
        Thread starter = Thread.ofVirtual().start(wc::start);
        assertTrue(handler.inInit.await(5, TimeUnit.SECONDS));
        // Bounded: undeploy does not wait for init.
        assertTimeoutPreemptively(Duration.ofSeconds(5), context::closeUpgradedConnections);
        assertTrue(conn.closes.get() >= 1, "the connection is closed at once");
        assertEquals(List.of("init"), handler.events, "no destroy while init runs");
        handler.release.countDown();
        assertTrue(handler.destroyed.await(5, TimeUnit.SECONDS));
        starter.join();
        assertEquals(List.of("init", "init returned", "destroy"), handler.events);
    }
}
