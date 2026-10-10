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

import io.vidocq.chappe.api.UpgradedConnection;
import io.vidocq.foy.internal.container.VidocqServletContext;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.WebConnection;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The {@link WebConnection} handed to {@link HttpUpgradeHandler#init} (Servlet 6.1 section 2.3.3.5)
 * over chappe's {@link UpgradedConnection}.
 *
 * <p>The input is a {@link ServletInputStreamImpl} over the connection's raw input (bytes already
 * buffered past the request head come first), the output an {@link UpgradedOutputStream} writing
 * straight to the connection. Both accept a listener (the connection is upgraded); their callbacks
 * share one {@link CallbackSerializer}, held while {@code init} runs, so no callback overlaps it.</p>
 *
 * <p>The connection closes when:</p>
 * <ul>
 *   <li>the application calls {@link #close()};</li>
 *   <li>the application closed the output stream and is done with the input: it closed the input
 *       stream, a blocking read returned {@code -1}, or (with a {@code ReadListener})
 *       {@code onAllDataRead} returned. Neither direction carries anything more then;</li>
 *   <li>a read or a write fails, or a listener callback throws: {@code onError} first;</li>
 *   <li>{@code init} throws;</li>
 *   <li>the context is undeployed ({@link VidocqServletContext#closeUpgradedConnections()}).</li>
 * </ul>
 * <p>Closing never waits on the peer: unless it runs inside a listener callback, the connection is
 * closed first, which wakes a read or a write blocked on it (chappe has no write timeout after an
 * upgrade); then callbacks stop (the one in progress returns) and {@link HttpUpgradeHandler#destroy()}
 * runs once. From a callback, callbacks stop first, then {@code destroy()}, then the connection
 * closes. {@code destroy()} only runs once {@code init} returned: a close during {@code init} (an
 * undeploy, or {@code init} itself) closes the connection at once and leaves {@code destroy()} to
 * the end of {@code init}; a connection whose context was undeployed before {@code init} only
 * closes.</p>
 *
 * <p>Chappe keeps an upgraded connection open until it is closed (no idle timeout, a half-close does
 * not close it). A handler that never reads nor closes keeps the connection, and its thread, until a
 * write fails or the context is undeployed.</p>
 */
final class WebConnectionImpl implements WebConnection {

    private static final System.Logger LOG = System.getLogger(WebConnectionImpl.class.getName());

    private final UpgradedConnection connection;
    private final HttpUpgradeHandler handler;
    private final VidocqServletContext context;
    private final ClassLoader applicationLoader;
    private final CallbackSerializer callbacks;
    private final ServletInputStreamImpl input;
    private final UpgradedOutputStream output;
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Registered with the context: undeploy closes the connection. */
    private final AutoCloseable undeployHook = this::close;

    /** Guards the fields below. */
    private final Object state = new Object();
    private boolean inputDone;
    private boolean outputClosed;
    private boolean initRunning;
    /** A close happened while {@code init} ran: {@code destroy()} is due once it returns. */
    private boolean destroyDue;

    /**
     * @param context the context tracking the connection for undeploy, or {@code null}
     */
    WebConnectionImpl(UpgradedConnection connection, HttpUpgradeHandler handler, VidocqServletContext context,
                      ClassLoader applicationLoader) {
        this.connection = connection;
        this.handler = handler;
        this.context = context;
        this.applicationLoader = applicationLoader;
        this.callbacks = new CallbackSerializer(applicationLoader);
        NonBlockingHost host = new NonBlockingHost() {
            @Override public boolean nonBlockingAllowed() { return true; }
            @Override public CallbackSerializer callbacks() { return callbacks; }
            @Override public void failed(Throwable t) { close(); }
            @Override public void inputDone() { WebConnectionImpl.this.inputDone(); }
        };
        this.input = new ServletInputStreamImpl(connection.input(), host);
        this.output = new UpgradedOutputStream(connection.output(), host, this::outputClosed);
    }

    /**
     * Called by chappe on the connection thread once the upgrade head is on the wire: calls
     * {@code init} with the application's class loader, listener callbacks held until it returns.
     */
    void start() {
        if (context != null && !context.registerUpgradedConnection(undeployHook)) {
            // Undeployed meanwhile: the handler never starts, so it is not destroyed either.
            closed.set(true);
            closeConnection();
            return;
        }
        synchronized (state) {
            initRunning = true;
        }
        callbacks.hold();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(applicationLoader);
        try {
            handler.init(this);
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "HttpUpgradeHandler.init failed; connection closed", t);
            close();
        } finally {
            thread.setContextClassLoader(previous);
            callbacks.release();
            boolean destroyNow;
            synchronized (state) {
                initRunning = false;
                destroyNow = destroyDue;
            }
            if (destroyNow) destroy();
        }
    }

    @Override
    public ServletInputStream getInputStream() {
        return input;
    }

    @Override
    public ServletOutputStream getOutputStream() {
        return output;
    }

    /**
     * Closes the connection once (see the class description for the order); {@code destroy()} runs
     * once, after {@code init} returned. Idempotent, from any thread.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        // Outside a callback, nothing may wait on the peer: the connection goes first.
        boolean inCallback = callbacks.isCallbackThread();
        if (!inCallback) closeConnection();
        input.endNonBlocking(false);
        output.end();
        callbacks.shutdown();
        boolean destroyNow;
        synchronized (state) {
            destroyNow = !initRunning;
            if (!destroyNow) destroyDue = true;
        }
        // During init the connection is already closed (init is never a callback); destroy() waits for init.
        if (destroyNow) destroy();
    }

    /** {@code destroy()} with the application's class loader, then the connection closes. */
    private void destroy() {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(applicationLoader);
        try {
            handler.destroy();
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "HttpUpgradeHandler.destroy failed", t);
        } finally {
            thread.setContextClassLoader(previous);
            closeConnection();
            if (context != null) context.unregisterUpgradedConnection(undeployHook);
        }
    }

    private void closeConnection() {
        try {
            connection.close();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "closing the upgraded connection failed", e);
        }
    }

    private void inputDone() {
        boolean both;
        synchronized (state) {
            inputDone = true;
            both = outputClosed;
        }
        if (both) close();
    }

    private void outputClosed() {
        boolean both;
        synchronized (state) {
            outputClosed = true;
            both = inputDone;
        }
        if (both) close();
    }
}
