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
package io.vidocq.foy.internal.session;

import io.vidocq.foy.internal.LogCapture;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.listener.ListenerRegistry;
import io.vidocq.foy.internal.session.SessionManager.SessionImport;
import jakarta.servlet.http.HttpSessionActivationListener;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The live sessions of a {@link SessionManager} leave with {@link SessionManager#closeExporting()} and come back in
 * another manager, through another class loader, with {@link SessionManager#importSessions}: the Foy half of a dev
 * swap (design of record: {@code docs/superpowers/specs/2026-11-16-session-survives-swap-design.md}).
 */
class SessionTransferTest {

    private static final String FIXTURE = TransferFixture.class.getName();
    private static final String CART = "cart";
    private static final String ITEMS = "3 items";
    private static final String PROBE = "probe";
    private static final String WILL_PASSIVATE = "willPassivate";
    private static final String DID_ACTIVATE = "didActivate";
    private static final String SESSION_DESTROYED = "sessionDestroyed";
    private static final SessionImport NOTHING = new SessionImport(0, 0, 0);
    private static final SessionImport ONE = new SessionImport(1, 0, 0);
    /** Bound by the import scope of a test: what the deserialization saw inside it. */
    private static final ScopedValue<List<Object>> INSIDE_THE_SCOPE = ScopedValue.newInstance();

    private final ClassLoader testLoader = SessionTransferTest.class.getClassLoader();
    private final List<SessionManager> managers = new ArrayList<>();

    @AfterEach
    void closeManagers() {
        managers.forEach(SessionManager::close);
    }

    // ---- round trip ----

    @Test
    void aValueMovesToTheClassOfTheImportingLoader() throws Exception {
        var loaderA = new ChildFirstLoader("a", Set.of(FIXTURE), Set.of());
        var loaderB = new ChildFirstLoader("b", Set.of(FIXTURE), Set.of());
        Class<?> classA = loaderA.loadClass(FIXTURE);
        Object valueA = classA.getConstructor(String.class, int.class).newInstance("kept", 3);
        var source = manager();
        var session = source.createNew();
        session.setAttribute("value", valueA);

        var target = manager();
        assertEquals(ONE, target.importSessions(source.closeExporting(), loaderB));

        Object valueB = stored(target, session.getId()).getAttribute("value");
        assertNotSame(classA, loaderB.loadClass(FIXTURE));
        assertSame(loaderB.loadClass(FIXTURE), valueB.getClass());
        assertEquals(valueA.toString(), valueB.toString());
    }

    @Test
    void arraysAndPrimitiveClassesResolveThroughTheImportingLoader() throws Exception {
        var loaderA = new ChildFirstLoader("a", Set.of(FIXTURE), Set.of());
        var loaderB = new ChildFirstLoader("b", Set.of(FIXTURE), Set.of());
        Object fixtures = java.lang.reflect.Array.newInstance(loaderA.loadClass(FIXTURE), 2);
        var source = manager();
        var session = source.createNew();
        session.setAttribute("fixtures", fixtures);
        session.setAttribute("grid", new String[][] {{"a", "b"}, {"c"}});
        session.setAttribute("numbers", new long[] {4L, 2L});
        session.setAttribute("types", new Class<?>[] {int.class, void.class, double[].class, String[].class});

        var target = manager();
        assertEquals(ONE, target.importSessions(source.closeExporting(), loaderB));

        var moved = stored(target, session.getId());
        assertSame(loaderB.loadClass(FIXTURE).arrayType(), moved.getAttribute("fixtures").getClass());
        assertArrayEquals(new String[][] {{"a", "b"}, {"c"}}, (Object[]) moved.getAttribute("grid"));
        assertArrayEquals(new long[] {4L, 2L}, (long[]) moved.getAttribute("numbers"));
        assertArrayEquals(new Class<?>[] {int.class, void.class, double[].class, String[].class},
                (Object[]) moved.getAttribute("types"));
    }

    @Test
    void aMovedSessionKeepsItsIdTimesAndInterval() {
        var source = manager();
        var session = source.createNew();
        session.setMaxInactiveInterval(600);
        session.beginAccess();
        nextMillisecond();
        session.endAccess();
        String id = session.getId();
        long created = session.getCreationTime();
        long lastAccessed = session.getLastAccessedTime();
        assertNotEquals(created, lastAccessed);

        var target = manager();
        target.importSessions(source.closeExporting(), testLoader);

        var moved = stored(target, id);
        assertEquals(id, moved.getId());
        assertEquals(created, moved.getCreationTime());
        assertEquals(lastAccessed, moved.getLastAccessedTime());
        assertEquals(600, moved.getMaxInactiveInterval());
        assertFalse(moved.isNew());
        assertSame(moved, target.find(id));
    }

    // ---- listeners ----

    @Test
    void anActivationListenerIsPassivatedOnExportAndActivatedOnImport() {
        var source = manager();
        var session = source.createNew();
        var probe = new ListenerProbe(new ArrayList<>());
        session.setAttribute(PROBE, probe);
        probe.events.clear();

        var target = manager();
        target.importSessions(source.closeExporting(), testLoader);

        var moved = assertInstanceOf(ListenerProbe.class, stored(target, session.getId()).getAttribute(PROBE));
        assertEquals(List.of(WILL_PASSIVATE), probe.events);
        assertEquals(List.of(WILL_PASSIVATE, DID_ACTIVATE), moved.events);
    }

    @Test
    void aMovedSessionFiresNoLifecycleAttributeOrBindingEvent() {
        List<String> events = new ArrayList<>();
        var source = manager();
        source.setListenerRegistry(recording(events));
        var session = source.createNew();
        var probe = new ListenerProbe(new ArrayList<>());
        session.setAttribute(PROBE, probe);
        session.setAttribute(CART, ITEMS);
        events.clear();
        probe.events.clear();

        var target = manager();
        target.setListenerRegistry(recording(events));
        assertEquals(ONE, target.importSessions(source.closeExporting(), testLoader));

        var moved = assertInstanceOf(ListenerProbe.class, stored(target, session.getId()).getAttribute(PROBE));
        assertEquals(List.of(), events, "no sessionCreated, sessionDestroyed or attribute event");
        assertEquals(List.of(WILL_PASSIVATE), probe.events, "no valueUnbound on the old value");
        assertEquals(List.of(WILL_PASSIVATE, DID_ACTIVATE), moved.events, "no valueBound on the new value");
    }

    @Test
    void whatAPassivationListenerChangesIsWritten() {
        var source = manager();
        var session = source.createNew();
        session.setAttribute("state", "dirty");
        session.setAttribute("flusher", new FlushOnPassivate());

        var target = manager();
        assertEquals(ONE, target.importSessions(source.closeExporting(), testLoader));

        assertEquals("flushed", stored(target, session.getId()).getAttribute("state"));
    }

    @Test
    void theExportRunsTheListenersUnderTheApplicationLoader() {
        var applicationLoader = new ClassLoader("application", testLoader) { };
        var context = new VidocqServletContext("/");
        context.setApplicationClassLoader(applicationLoader);
        var source = new SessionManager(new InMemorySessionStore(), context, 1800);
        managers.add(source);
        var probe = new LoaderProbe();
        source.createNew().setAttribute(PROBE, probe);
        var caller = new ClassLoader("caller", testLoader) { };

        Thread current = Thread.currentThread();
        ClassLoader before = current.getContextClassLoader();
        current.setContextClassLoader(caller);
        try {
            source.closeExporting();
            assertSame(caller, current.getContextClassLoader(), "the export gives the caller its loader back");
        } finally {
            current.setContextClassLoader(before);
        }
        assertEquals(List.of(applicationLoader), probe.seen);
    }

    @Test
    void aFailingActivationListenerIsLoggedAndTheSessionStillMoves() {
        var source = manager();
        var session = source.createNew();
        session.setAttribute(PROBE, new FailingActivation());
        var target = manager();
        List<String> warnings;
        try (var log = LogCapture.of(SessionManager.class.getName())) {
            assertEquals(ONE, target.importSessions(source.closeExporting(), testLoader));
            warnings = log.warnings();
        }
        assertEquals(2, warnings.size(), warnings::toString);
        assertTrue(warnings.get(0).contains("sessionWillPassivate"), warnings::toString);
        assertTrue(warnings.get(1).contains("sessionDidActivate"), warnings::toString);
        warnings.forEach(w -> assertFalse(w.contains(session.getId()), w));
    }

    @Test
    void anErrorFromAPassivationListenerIsLoggedAndTheExportGoesOn() {
        var source = manager();
        var failing = source.createNew();
        failing.setAttribute(PROBE, ErrorInListener.onPassivate());
        var other = source.createNew();
        other.setAttribute(CART, ITEMS);

        byte[] export;
        List<String> warnings;
        List<Throwable> thrown;
        try (var log = LogCapture.of(SessionManager.class.getName())) {
            export = source.closeExporting();
            warnings = log.warnings();
            thrown = log.thrown();
        }

        var target = manager();
        assertEquals(new SessionImport(2, 0, 0), target.importSessions(export, testLoader));
        assertEquals(ITEMS, stored(target, other.getId()).getAttribute(CART));
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().contains("sessionWillPassivate"), warnings::toString);
        assertInstanceOf(ExceptionInInitializerError.class, thrown.getFirst());
    }

    @Test
    void anErrorFromAnActivationListenerIsLoggedAndTheImportGoesOn() {
        var source = manager();
        var failing = source.createNew();
        failing.setAttribute(PROBE, ErrorInListener.onActivate());
        var other = source.createNew();
        other.setAttribute(CART, ITEMS);
        byte[] export = source.closeExporting();

        var target = manager();
        SessionImport imported;
        List<String> warnings;
        List<Throwable> thrown;
        try (var log = LogCapture.of(SessionManager.class.getName())) {
            imported = target.importSessions(export, testLoader);
            warnings = log.warnings();
            thrown = log.thrown();
        }

        assertEquals(new SessionImport(2, 0, 0), imported);
        assertEquals(ITEMS, stored(target, other.getId()).getAttribute(CART));
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().contains("sessionDidActivate"), warnings::toString);
        assertInstanceOf(ExceptionInInitializerError.class, thrown.getFirst());
    }

    @Test
    void aVirtualMachineErrorFromAnActivationListenerIsNotSwallowed() {
        var source = manager();
        source.createNew().setAttribute(PROBE, ErrorInListener.fatalOnActivate());
        byte[] export = source.closeExporting();

        var target = manager();
        assertThrows(OutOfMemoryError.class, () -> target.importSessions(export, testLoader));
    }

    // ---- what cannot move ----

    @Test
    void aSessionWithAnUnwritableAttributeStaysBehindAndEnds() {
        List<String> events = new ArrayList<>();
        var source = manager();
        source.setListenerRegistry(recording(events));
        var stuck = source.createNew();
        stuck.setAttribute(PROBE, new ListenerProbe(events));
        stuck.setAttribute("socket", new Unwritable());
        var mover = source.createNew();
        mover.setAttribute(CART, ITEMS);
        events.clear();

        byte[] export;
        List<String> warnings;
        try (var log = LogCapture.of(SessionManager.class.getName())) {
            export = source.closeExporting();
            warnings = log.warnings();
        }

        assertEquals(List.of(WILL_PASSIVATE, DID_ACTIVATE, SESSION_DESTROYED), events.subList(0, 3), events::toString);
        assertTrue(events.contains("valueUnbound"), events::toString);
        assertTrue(stuck.isInvalidated());
        assertEquals(1, warnings.size(), warnings::toString);
        String warning = warnings.getFirst();
        assertTrue(warning.contains("socket"), warning);
        assertTrue(warning.contains(Unwritable.class.getName()), warning);
        assertTrue(warning.contains(stuck.getId().substring(0, 8)), warning);
        assertFalse(warning.contains(stuck.getId()), warning);

        var target = manager();
        assertEquals(ONE, target.importSessions(export, testLoader));
        assertEquals(ITEMS, stored(target, mover.getId()).getAttribute(CART));
        assertTrue(target.store().get(stuck.getId()).isEmpty());
    }

    @Test
    void aSessionWithAnUnreadableAttributeIsDroppedAtImport() {
        var source = manager();
        var lost = source.createNew();
        lost.setAttribute("value", new TransferFixture("lost", 1));
        var kept = source.createNew();
        kept.setAttribute(CART, ITEMS);
        byte[] export = source.closeExporting();

        var target = manager();
        var withoutTheClass = new ChildFirstLoader("b", Set.of(), Set.of(FIXTURE));
        SessionImport result;
        List<String> warnings;
        try (var log = LogCapture.of(SessionManager.class.getName())) {
            result = target.importSessions(export, withoutTheClass);
            warnings = log.warnings();
        }

        assertEquals(new SessionImport(1, 0, 1), result);
        assertTrue(target.store().get(lost.getId()).isEmpty());
        assertEquals(ITEMS, stored(target, kept.getId()).getAttribute(CART));
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().endsWith("first cause: class " + FIXTURE + ": the class is missing"),
                warnings::toString);
    }

    @Test
    void aValueThatIsNotASerializationStreamDropsItsSessionWithTheStreamsMessage() {
        byte[] planted = SessionExport.write(List.of(new SessionExport.ExportedSession(
                "0123456789abcdef0123456789abcdef", 0, 0, System.currentTimeMillis(), 600,
                Map.of("value", new byte[] {1, 2, 3, 4}), Map.of())));

        var target = manager();
        List<String> warnings;
        try (var log = LogCapture.of(SessionManager.class.getName())) {
            assertEquals(new SessionImport(0, 0, 1), target.importSessions(planted, testLoader));
            warnings = log.warnings();
        }
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().endsWith("first cause: an unreadable value: invalid stream header: 01020304"),
                warnings::toString);
    }

    @Test
    void aValueWhoseReadObjectThrowsDropsItsSession() {
        var source = manager();
        source.createNew().setAttribute("bomb", new ExplodingOnRead());
        byte[] export = source.closeExporting();

        var target = manager();
        List<String> warnings;
        try (var log = LogCapture.of(SessionManager.class.getName())) {
            assertEquals(new SessionImport(0, 0, 1), target.importSessions(export, testLoader));
            warnings = log.warnings();
        }
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().endsWith(
                "first cause: class " + ExplodingOnRead.class.getName() + ": cannot read"), warnings::toString);
    }

    @Test
    void aValueWhoseReadObjectThrowsWithoutAMessageDropsItsSessionWithWhatTheExceptionIs() {
        var source = manager();
        source.createNew().setAttribute("bomb", new SilentOnRead());
        byte[] export = source.closeExporting();

        var target = manager();
        List<String> warnings;
        try (var log = LogCapture.of(SessionManager.class.getName())) {
            assertEquals(new SessionImport(0, 0, 1), target.importSessions(export, testLoader));
            warnings = log.warnings();
        }
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().endsWith("first cause: class " + SilentOnRead.class.getName()
                + ": java.lang.IllegalStateException"), warnings::toString);
    }

    @Test
    void anAttributeThatReadsAsNullDropsItsSessionAndTheOthersMove() {
        var source = manager();
        var lost = source.createNew();
        lost.setAttribute("ghost", new NullOnRead());
        var kept = source.createNew();
        kept.setAttribute(CART, ITEMS);
        byte[] export = source.closeExporting();

        var target = manager();
        SessionImport result;
        List<String> warnings;
        try (var log = LogCapture.of(SessionManager.class.getName())) {
            result = target.importSessions(export, testLoader);
            warnings = log.warnings();
        }

        assertEquals(new SessionImport(1, 0, 1), result);
        assertTrue(target.store().get(lost.getId()).isEmpty());
        assertEquals(ITEMS, stored(target, kept.getId()).getAttribute(CART));
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().contains(NullOnRead.class.getName()), warnings::toString);
    }

    @Test
    void aNoteThatReadsAsNullIsLeftOutAlone() {
        var source = manager();
        var session = source.createNew();
        session.setAttribute(CART, ITEMS);
        session.setNote("ghost", new NullOnRead());

        var target = manager();
        assertEquals(ONE, target.importSessions(source.closeExporting(), testLoader));

        var moved = stored(target, session.getId());
        assertEquals(ITEMS, moved.getAttribute(CART));
        assertNull(moved.note("ghost"));
    }

    @Test
    void aStackOverflowWhileWritingKeepsThatSessionBehindAndTheOthersMove() {
        List<String> events = new ArrayList<>();
        var source = manager();
        source.setListenerRegistry(recording(events));
        var deep = source.createNew();
        deep.setAttribute("graph", new OverflowOnWrite());
        List<HttpSessionImpl> movers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var mover = source.createNew();
            mover.setAttribute(CART, ITEMS);
            movers.add(mover);
        }
        movers.getFirst().setNote("graph", new OverflowOnWrite());
        events.clear();

        byte[] export;
        List<String> warnings;
        try (var log = LogCapture.of(SessionManager.class.getName())) {
            export = source.closeExporting();
            warnings = log.warnings();
        }

        assertTrue(deep.isInvalidated());
        assertEquals(List.of(SESSION_DESTROYED, "attributeRemoved"), events, "the session that stays behind ends");
        assertEquals(0, source.store().size());
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().contains(OverflowOnWrite.class.getName()), warnings::toString);
        var target = manager();
        assertEquals(new SessionImport(3, 0, 0), target.importSessions(export, testLoader));
        movers.forEach(mover -> assertEquals(ITEMS, stored(target, mover.getId()).getAttribute(CART)));
        assertNull(stored(target, movers.getFirst().getId()).note("graph"), "the note that failed is left out");
    }

    @Test
    void anErrorWhileReadingDropsThatSessionAndTheOthersMove() {
        var source = manager();
        var broken = source.createNew();
        broken.setAttribute("init", new ErrorOnRead());
        var kept = source.createNew();
        kept.setAttribute(CART, ITEMS);
        kept.setNote("init", new ErrorOnRead());
        byte[] export = source.closeExporting();

        var target = manager();
        SessionImport result;
        List<String> warnings;
        try (var log = LogCapture.of(SessionManager.class.getName())) {
            result = target.importSessions(export, testLoader);
            warnings = log.warnings();
        }

        assertEquals(new SessionImport(1, 0, 1), result);
        assertTrue(target.store().get(broken.getId()).isEmpty());
        var moved = stored(target, kept.getId());
        assertEquals(ITEMS, moved.getAttribute(CART));
        assertNull(moved.note("init"));
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().contains(ErrorOnRead.class.getName()), warnings::toString);
    }

    @Test
    void closeWaitsForAnImportInProgress() throws InterruptedException {
        var source = manager();
        source.createNew().setAttribute(CART, ITEMS);
        byte[] export = source.closeExporting();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        var target = manager();
        target.setListenerRegistry(recording(events));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        // The import holds before it stores anything: a close that did not wait would find an empty store.
        target.importScope(new SessionImportScope() {
            @Override
            public <T> T around(Supplier<T> importer) {
                entered.countDown();
                awaitOrFail(release);
                return importer.get();
            }
        });

        Thread importing = Thread.ofVirtual().start(() -> target.importSessions(export, testLoader));
        awaitOrFail(entered);
        Thread closing = Thread.ofVirtual().start(target::close);
        waitUntilParked(closing);
        release.countDown();

        assertTrue(importing.join(Duration.ofSeconds(10)));
        assertTrue(closing.join(Duration.ofSeconds(10)));
        assertEquals(List.of(SESSION_DESTROYED, "attributeRemoved"), events, "the close ended the imported session");
        assertEquals(0, target.store().size());
    }

    @Test
    void anIdAlreadyInTheStoreIsDropped() {
        var source = manager();
        var session = source.createNew();
        session.setAttribute(CART, ITEMS);
        byte[] export = source.closeExporting();
        var target = manager();
        assertEquals(ONE, target.importSessions(export, testLoader));
        var first = stored(target, session.getId());

        assertEquals(new SessionImport(0, 0, 1), target.importSessions(export, testLoader));
        assertSame(first, stored(target, session.getId()));
    }

    // ---- expiry ----

    @Test
    void anExpiredSessionEndsAtExportAndOneThatExpiresDuringTheSwapIsCounted() {
        List<String> events = new ArrayList<>();
        var source = manager();
        source.setListenerRegistry(recording(events));
        var expired = source.createNew();
        expired.setMaxInactiveInterval(60);
        var expiring = source.createNew();
        expiring.setMaxInactiveInterval(600);
        long now = System.currentTimeMillis();
        events.clear();

        // Five minutes on: beyond the first interval, well within the second.
        source.clock(() -> now + 300_000);
        byte[] export = source.closeExporting();

        assertEquals(List.of(SESSION_DESTROYED), events);
        assertTrue(expired.isInvalidated());
        var target = manager();
        // An hour on: beyond the second interval too.
        target.clock(() -> now + 3_600_000);
        assertEquals(new SessionImport(0, 1, 0), target.importSessions(export, testLoader));
        assertTrue(target.store().get(expiring.getId()).isEmpty());
    }

    @Test
    void aSessionInUseAtTheExportIsIdleFromTheExport() {
        var source = manager();
        var busy = source.createNew();
        busy.setMaxInactiveInterval(60);
        busy.beginAccess();
        long exportTime = System.currentTimeMillis() + 3_600_000;
        // An hour on, the request that uses the session still runs, as a server-sent event stream does.
        source.clock(() -> exportTime);
        byte[] export = source.closeExporting();

        var withinTheInterval = manager();
        withinTheInterval.clock(() -> exportTime + 59_000);
        assertEquals(ONE, withinTheInterval.importSessions(export, testLoader),
                "idle since the export, not since the running request began");
        var beyondIt = manager();
        beyondIt.clock(() -> exportTime + 60_000);
        assertEquals(new SessionImport(0, 1, 0), beyondIt.importSessions(export, testLoader));
    }

    @Test
    void aSessionInUseAtTheExportKeepsTheLastAccessedTimeOfItsLastCompletedRequest() {
        long now = System.currentTimeMillis();
        String id = "0123456789abcdef0123456789abcdef";
        // A session last left by a request 100 s ago, with an interval of 60 s.
        byte[] planted = SessionExport.write(List.of(new SessionExport.ExportedSession(
                id, now - 200_000, now - 100_000, now - 100_000, 60, Map.of(), Map.of())));
        var source = manager();
        source.clock(() -> now - 90_000);
        assertEquals(ONE, source.importSessions(planted, testLoader));
        // A request takes it and still runs at the export.
        stored(source, id).beginAccess();
        source.clock(() -> now);

        var target = manager();
        target.clock(() -> now + 30_000);
        assertEquals(ONE, target.importSessions(source.closeExporting(), testLoader));
        assertEquals(now - 100_000, stored(target, id).getLastAccessedTime(),
                "getLastAccessedTime() is still the end of the last request that completed");
    }

    // ---- after the export ----

    @Test
    void afterTheExportTheManagerIsEmptyAndClosed() {
        var source = manager();
        var stale = source.createNew();
        stale.setAttribute(CART, ITEMS);
        source.closeExporting();

        assertEquals(0, source.store().size());
        assertThrows(IllegalStateException.class, () -> stale.getAttribute(CART));
        var target = manager();
        assertEquals(NOTHING, target.importSessions(source.closeExporting(), testLoader));
        assertThrows(IllegalStateException.class, source::createNew);
    }

    @Test
    void aManagerClosedByCloseExportsNoSession() {
        var source = manager();
        source.createNew().setAttribute(CART, ITEMS);
        source.close();

        var target = manager();
        assertEquals(NOTHING, target.importSessions(source.closeExporting(), testLoader));
    }

    // ---- notes ----

    @Test
    void notesMoveAndAnUnwritableNoteIsLeftOutAlone() {
        var source = manager();
        var session = source.createNew();
        session.setAttribute(CART, ITEMS);
        session.setNote("savedTarget", "/orders?page=2");
        session.setNote("socket", new Unwritable());
        byte[] export;
        List<String> infos;
        try (var log = LogCapture.of(SessionManager.class.getName())) {
            export = source.closeExporting();
            infos = log.infos();
        }

        var target = manager();
        assertEquals(ONE, target.importSessions(export, testLoader));
        var moved = stored(target, session.getId());
        assertEquals(ITEMS, moved.getAttribute(CART));
        assertEquals("/orders?page=2", moved.note("savedTarget"));
        assertNull(moved.note("socket"));
        assertEquals(1, infos.size(), infos::toString);
        assertTrue(infos.getFirst().contains(Unwritable.class.getName()), infos::toString);
        assertTrue(infos.getFirst().contains(session.getId().substring(0, 8)), infos::toString);
        assertFalse(infos.getFirst().contains(session.getId()), infos::toString);
    }

    @Test
    void aNoteThatCannotBeReadIsLeftOutAlone() {
        var source = manager();
        var session = source.createNew();
        session.setAttribute(CART, ITEMS);
        session.setNote("fixture", new TransferFixture("note", 2));
        byte[] export = source.closeExporting();

        var target = manager();
        var withoutTheClass = new ChildFirstLoader("b", Set.of(), Set.of(FIXTURE));
        assertEquals(ONE, target.importSessions(export, withoutTheClass));
        var moved = stored(target, session.getId());
        assertEquals(ITEMS, moved.getAttribute(CART));
        assertNull(moved.note("fixture"));
    }

    // ---- format ----

    @Test
    void anExportWithAnotherMagicIsRejected() {
        byte[] export = manager().closeExporting();
        export[0] = 'X';
        var target = manager();
        assertThrows(IllegalArgumentException.class, () -> target.importSessions(export, testLoader));
    }

    @Test
    void theExportIsOfVersion2AndRoundTrips() {
        var source = manager();
        var session = source.createNew();
        session.setAttribute(CART, ITEMS);
        byte[] export = source.closeExporting();

        assertArrayEquals(new byte[] {'F', 'O', 'Y', 'S', 0, 0, 0, 2}, Arrays.copyOf(export, 8));
        var target = manager();
        assertEquals(ONE, target.importSessions(export, testLoader));
        assertEquals(ITEMS, stored(target, session.getId()).getAttribute(CART));
    }

    @Test
    void anExportOfVersion1IsRefusedAsAnUnknownVersion() {
        byte[] export = manager().closeExporting();
        export[7] = 1;
        var target = manager();
        var refused = assertThrows(IllegalArgumentException.class, () -> target.importSessions(export, testLoader));
        assertEquals("unknown version 1 of a Foy session export, this Foy reads version 2", refused.getMessage());
    }

    @Test
    void aTruncatedExportIsRejected() {
        var source = manager();
        source.createNew().setAttribute(CART, ITEMS);
        byte[] whole = source.closeExporting();
        byte[] truncated = Arrays.copyOf(whole, whole.length - 1);
        var target = manager();
        assertThrows(IllegalArgumentException.class, () -> target.importSessions(truncated, testLoader));
    }

    @Test
    void anExportWithTrailingBytesIsRejected() {
        byte[] whole = manager().closeExporting();
        byte[] longer = Arrays.copyOf(whole, whole.length + 1);
        var target = manager();
        assertThrows(IllegalArgumentException.class, () -> target.importSessions(longer, testLoader));
    }

    @Test
    void anExportWithANegativeCountIsRejected() {
        byte[] export = manager().closeExporting();
        Arrays.fill(export, 8, 12, (byte) 0xFF);
        var target = manager();
        assertThrows(IllegalArgumentException.class, () -> target.importSessions(export, testLoader));
    }

    @Test
    void aClosedManagerRefusesAnImport() {
        byte[] export = manager().closeExporting();
        var target = manager();
        target.close();
        assertThrows(IllegalStateException.class, () -> target.importSessions(export, testLoader));
    }

    // ---- import scope ----

    @Test
    void theImportScopeWrapsTheImportAndItsDeserialization() {
        var source = manager();
        var session = source.createNew();
        session.setAttribute(PROBE, new ScopeProbe());
        byte[] export = source.closeExporting();
        var applicationLoader = new ClassLoader("application", testLoader) { };
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        List<Object> seen = new ArrayList<>();

        var target = manager();
        target.importScope(new SessionImportScope() {
            @Override
            public <T> T around(Supplier<T> importer) {
                seen.add(Thread.currentThread().getContextClassLoader());
                return ScopedValue.where(INSIDE_THE_SCOPE, seen).call(importer::get);
            }
        });
        assertEquals(ONE, target.importSessions(export, applicationLoader));

        assertEquals(List.of(applicationLoader, applicationLoader), seen,
                "the scope ran under the application loader, then the deserialization inside it");
        assertSame(before, Thread.currentThread().getContextClassLoader());
    }

    // ---- helpers ----

    private SessionManager manager() {
        var manager = new SessionManager(new InMemorySessionStore(), new VidocqServletContext("/"), 1800);
        managers.add(manager);
        return manager;
    }

    private static HttpSessionImpl stored(SessionManager manager, String id) {
        return assertInstanceOf(HttpSessionImpl.class, manager.store().get(id).orElse(null));
    }

    private static ListenerRegistry recording(List<String> events) {
        var registry = new ListenerRegistry();
        registry.register(new Recorder(events));
        return registry;
    }

    /** Waits up to 10 s for {@code latch}, and fails past that. */
    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) fail("timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
    }

    /** Waits until {@code thread} is parked, 10 s at most. */
    private static void waitUntilParked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.WAITING) {
            if (System.nanoTime() > deadline) fail("the thread never waited");
            Thread.onSpinWait();
        }
    }

    /** Waits, without sleeping, until the clock shows the next millisecond. */
    private static void nextMillisecond() {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() == start) Thread.onSpinWait();
    }

    /** Records the session lifecycle and attribute events of a registry. */
    private record Recorder(List<String> events) implements HttpSessionListener, HttpSessionAttributeListener {
        @Override public void sessionCreated(HttpSessionEvent se) { events.add("sessionCreated"); }
        @Override public void sessionDestroyed(HttpSessionEvent se) { events.add(SESSION_DESTROYED); }
        @Override public void attributeAdded(HttpSessionBindingEvent event) { events.add("attributeAdded"); }
        @Override public void attributeRemoved(HttpSessionBindingEvent event) { events.add("attributeRemoved"); }
        @Override public void attributeReplaced(HttpSessionBindingEvent event) { events.add("attributeReplaced"); }
    }

    /** An attribute that hears its passivation, its activation and its binding. */
    static final class ListenerProbe implements Serializable, HttpSessionActivationListener, HttpSessionBindingListener {
        final List<String> events;

        ListenerProbe(List<String> events) {
            this.events = events;
        }

        @Override public void sessionWillPassivate(HttpSessionEvent se) { events.add(WILL_PASSIVATE); }
        @Override public void sessionDidActivate(HttpSessionEvent se) { events.add(DID_ACTIVATE); }
        @Override public void valueBound(HttpSessionBindingEvent event) { events.add("valueBound"); }
        @Override public void valueUnbound(HttpSessionBindingEvent event) { events.add("valueUnbound"); }
    }

    /** An activation listener that fails both ways. */
    static final class FailingActivation implements Serializable, HttpSessionActivationListener {
        @Override public void sessionWillPassivate(HttpSessionEvent se) {
            throw new IllegalStateException("cannot passivate");
        }
        @Override public void sessionDidActivate(HttpSessionEvent se) {
            throw new IllegalStateException("cannot activate");
        }
    }

    /**
     * An activation listener that fails with an error on one event: an {@link ExceptionInInitializerError}, as a
     * class of a layer whose static initializer fails, or, when fatal, an {@link OutOfMemoryError}.
     */
    static final class ErrorInListener implements Serializable, HttpSessionActivationListener {
        private final String failingEvent;
        private final boolean fatal;

        private ErrorInListener(String failingEvent, boolean fatal) {
            this.failingEvent = failingEvent;
            this.fatal = fatal;
        }

        static ErrorInListener onPassivate() { return new ErrorInListener(WILL_PASSIVATE, false); }

        static ErrorInListener onActivate() { return new ErrorInListener(DID_ACTIVATE, false); }

        static ErrorInListener fatalOnActivate() { return new ErrorInListener(DID_ACTIVATE, true); }

        @Override public void sessionWillPassivate(HttpSessionEvent se) { failOn(WILL_PASSIVATE); }

        @Override public void sessionDidActivate(HttpSessionEvent se) { failOn(DID_ACTIVATE); }

        private void failOn(String event) {
            if (!event.equals(failingEvent)) return;
            if (fatal) throw new OutOfMemoryError("the virtual machine ran out of memory");
            throw new ExceptionInInitializerError("the static initializer of a class failed");
        }
    }

    /** A value that cannot be written: it is not {@link Serializable}. */
    static final class Unwritable {
    }

    /** A value that is written but fails to read. */
    static final class ExplodingOnRead implements Serializable {
        @java.io.Serial
        private void readObject(ObjectInputStream in) {
            throw new IllegalStateException("cannot read");
        }
    }

    /** A value whose {@code readObject} throws an exception that has no message. */
    static final class SilentOnRead implements Serializable {
        @java.io.Serial
        private void readObject(ObjectInputStream in) {
            throw new IllegalStateException();
        }
    }

    /** A value that reads back as {@code null}. */
    static final class NullOnRead implements Serializable {
        @java.io.Serial
        private Object readResolve() {
            return null;
        }
    }

    /** A value whose graph is too deep to write. */
    static final class OverflowOnWrite implements Serializable {
        @java.io.Serial
        private void writeObject(ObjectOutputStream out) {
            throw new StackOverflowError("a graph too deep to write");
        }
    }

    /** A value whose reading fails with an error of the class loading kind. */
    static final class ErrorOnRead implements Serializable {
        @java.io.Serial
        private void readObject(ObjectInputStream in) {
            throw new ExceptionInInitializerError("a static initializer failed");
        }
    }

    /** An activation listener that flushes the state of its session as it is passivated. */
    static final class FlushOnPassivate implements Serializable, HttpSessionActivationListener {
        @Override public void sessionWillPassivate(HttpSessionEvent se) {
            se.getSession().setAttribute("state", "flushed");
        }
    }

    /** An activation listener that records the context loader its passivation sees. */
    static final class LoaderProbe implements Serializable, HttpSessionActivationListener {
        final transient List<ClassLoader> seen = new ArrayList<>();

        @Override public void sessionWillPassivate(HttpSessionEvent se) {
            seen.add(Thread.currentThread().getContextClassLoader());
        }
    }

    /** A value that records, as it is read, the context loader it sees inside the import scope. */
    static final class ScopeProbe implements Serializable {
        @java.io.Serial
        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            if (INSIDE_THE_SCOPE.isBound()) INSIDE_THE_SCOPE.get().add(Thread.currentThread().getContextClassLoader());
        }
    }

    /**
     * A loader that defines the classes named in {@code own} itself, from the bytes of the test classes, hides those
     * named in {@code hidden}, and delegates every other name to the test classes' loader. Two such loaders give two
     * different classes of one name, as two application layers do.
     */
    static final class ChildFirstLoader extends ClassLoader {
        private final Set<String> own;
        private final Set<String> hidden;

        ChildFirstLoader(String name, Set<String> own, Set<String> hidden) {
            super(name, SessionTransferTest.class.getClassLoader());
            this.own = own;
            this.hidden = hidden;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (hidden.contains(name)) throw new ClassNotFoundException(name);
            if (!own.contains(name)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                return loaded != null ? loaded : defineOwn(name);
            }
        }

        private Class<?> defineOwn(String name) throws ClassNotFoundException {
            try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (in == null) throw new ClassNotFoundException(name);
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }
}
