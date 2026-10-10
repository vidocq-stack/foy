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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
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
 * <p>The connection closes, calling {@link HttpUpgradeHandler#destroy()} once first, when:</p>
 * <ul>
 *   <li>the application calls {@link #close()};</li>
 *   <li>the application closed the output stream and the input is done: closed by the
 *       application, or at its end (the peer half-closed: after {@code onAllDataRead}, or after a
 *       blocking read returned {@code -1}). Neither direction can carry anything more;</li>
 *   <li>a read or a write fails, or a listener callback throws: {@code onError} first;</li>
 *   <li>{@code init} throws;</li>
 *   <li>the context is undeployed ({@link VidocqServletContext#closeUpgradedConnections()}): the
 *       connection is closed first, which wakes any read or write blocked on it.</li>
 * </ul>
 * <p>Chappe keeps an upgraded connection open until it is closed (no idle timeout, a half-close does
 * not close it), so one of these must happen for the connection and its thread to go.</p>
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
    /** Registered with the context: undeploy closes the connection before destroying the handler. */
    private final AutoCloseable undeployHook = () -> close(true);

    /** Guards {@link #inputDone} and {@link #outputClosed}, so exactly one side sees both. */
    private final Object ends = new Object();
    private boolean inputDone;
    private boolean outputClosed;

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
            @Override public void failed(Throwable t) { close(false); }
            @Override public void inputClosed() { inputDone(); }
        };
        this.input = new ServletInputStreamImpl(new EndTracking(connection.input()), host);
        this.output = new UpgradedOutputStream(connection.output(), host, this::outputClosed);
    }

    /**
     * Called by chappe on the connection thread once the upgrade head is on the wire: calls
     * {@code init} with the application's class loader, listener callbacks held until it returns.
     */
    void start() {
        if (context != null && !context.registerUpgradedConnection(undeployHook)) {
            // Undeployed meanwhile: the handler never starts.
            close(true);
            return;
        }
        callbacks.hold();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(applicationLoader);
        try {
            handler.init(this);
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "HttpUpgradeHandler.init failed; connection closed", t);
            close(false);
        } finally {
            thread.setContextClassLoader(previous);
            callbacks.release();
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

    /** Calls {@link HttpUpgradeHandler#destroy()} once, then closes the connection. Idempotent. */
    @Override
    public void close() {
        close(false);
    }

    /**
     * Closes once: listener callbacks stop (the one in progress finishes first, unless this runs in
     * it), {@code destroy()}, then the connection. {@code abort} (undeploy) closes the connection
     * first, so a callback blocked on the connection returns.
     */
    private void close(boolean abort) {
        if (!closed.compareAndSet(false, true)) return;
        if (abort) closeConnection();
        input.endNonBlocking(false);
        output.end();
        callbacks.shutdown();
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
        synchronized (ends) {
            inputDone = true;
            both = outputClosed;
        }
        if (both) close(false);
    }

    private void outputClosed() {
        boolean both;
        synchronized (ends) {
            outputClosed = true;
            both = inputDone;
        }
        if (both) close(false);
    }

    /** The connection's input, reporting its end ({@code -1}) as the input being done. */
    private final class EndTracking extends FilterInputStream {
        EndTracking(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b < 0) ended();
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n < 0) ended();
            return n;
        }

        /**
         * The peer half-closed. With a ReadListener, onAllDataRead comes next (the output is normally
         * still open then); an output already closed ends the connection now.
         */
        private void ended() {
            inputDone();
        }
    }
}
