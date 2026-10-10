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
package io.vidocq.foy.internal.listener;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextAttributeEvent;
import jakarta.servlet.ServletContextAttributeListener;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestAttributeEvent;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EventListener;
import java.util.List;

/**
 * Typed register of Servlet listeners discovered at startup.
 * <p>
 * A listener can implement multiple interfaces; it is recorded in
 * each corresponding list.
 * </p>
 */
public final class ListenerRegistry {

    private final List<ServletContextListener> contextListeners = new ArrayList<>();
    private final List<ServletContextAttributeListener> contextAttrListeners = new ArrayList<>();
    private final List<ServletRequestListener> requestListeners = new ArrayList<>();
    private final List<ServletRequestAttributeListener> requestAttrListeners = new ArrayList<>();
    private final List<HttpSessionListener> sessionListeners = new ArrayList<>();
    private final List<HttpSessionAttributeListener> sessionAttrListeners = new ArrayList<>();
    private final List<HttpSessionIdListener> sessionIdListeners = new ArrayList<>();

    /** Tracks listeners added programmatically (ctx.addListener...) —
     *  Servlet 6.1 §4.4.3: they do not have access to dynamic configuration APIs. */
    private final java.util.IdentityHashMap<EventListener, Boolean> programmatic =
            new java.util.IdentityHashMap<>();

    public void register(EventListener listener) { register(listener, false); }

    public void register(EventListener listener, boolean isProgrammatic) {
        if (listener instanceof ServletContextListener l) contextListeners.add(l);
        if (listener instanceof ServletContextAttributeListener l) contextAttrListeners.add(l);
        if (listener instanceof ServletRequestListener l) requestListeners.add(l);
        if (listener instanceof ServletRequestAttributeListener l) requestAttrListeners.add(l);
        if (listener instanceof HttpSessionListener l) sessionListeners.add(l);
        if (listener instanceof HttpSessionAttributeListener l) sessionAttrListeners.add(l);
        if (listener instanceof HttpSessionIdListener l) sessionIdListeners.add(l);
        programmatic.put(listener, isProgrammatic);
    }

    /**
     * Registers a request listener of the container itself, ahead of the application's: it sees a
     * request first and is told last that it ended (destroyed events run in reverse order). Used for
     * the CDI request context ({@link CdiRequestScopeListener}). Call it before the deployment
     * serves requests.
     */
    public void addFirst(ServletRequestListener listener) {
        requestListeners.add(0, listener);
    }

    public boolean isProgrammatic(EventListener l) {
        return Boolean.TRUE.equals(programmatic.get(l));
    }

    public void registerAll(List<? extends EventListener> listeners) {
        listeners.forEach(this::register);
    }

    // ---- Context lifecycle ----

    public void fireContextInitialized(ServletContext ctx) {
        if (contextListeners.isEmpty()) return;
        var evt = new ServletContextEvent(ctx);
        var vctx = ctx instanceof io.vidocq.foy.internal.container.VidocqServletContext v ? v : null;
        if (vctx != null) vctx.setContextInitializedPhase(true);
        try {
            // Copie défensive : un listener peut ajouter programmatiquement d'autres
            // listeners pendant son contextInitialized.
            for (var l : new ArrayList<>(contextListeners)) {
                boolean prog = isProgrammatic(l);
                if (prog && vctx != null) {
                    vctx.setProgrammaticListenerActive(true);
                    try { l.contextInitialized(evt); }
                    finally { vctx.setProgrammaticListenerActive(false); }
                } else {
                    l.contextInitialized(evt);
                }
            }
        } finally {
            if (vctx != null) vctx.setContextInitializedPhase(false);
        }
    }

    /**
     * Notifies the context listeners in reverse declaration order (Servlet 6.1 chapter 11). A failing
     * listener is logged and does not keep the others from being notified: each one releases its
     * own resources at undeploy. A {@link VirtualMachineError} is rethrown at once.
     */
    public void fireContextDestroyed(ServletContext ctx) {
        if (contextListeners.isEmpty()) return;
        var evt = new ServletContextEvent(ctx);
        for (int i = contextListeners.size() - 1; i >= 0; i--) {
            ServletContextListener l = contextListeners.get(i);
            try {
                l.contextDestroyed(evt);
            } catch (VirtualMachineError e) {
                throw e;
            } catch (RuntimeException | Error e) {
                System.getLogger(ListenerRegistry.class.getName()).log(System.Logger.Level.WARNING,
                        "contextDestroyed failed for listener " + l.getClass().getName(), e);
            }
        }
    }

    public void fireContextAttributeAdded(ServletContext ctx, String name, Object value) {
        if (contextAttrListeners.isEmpty()) return;
        var evt = new ServletContextAttributeEvent(ctx, name, value);
        for (var l : contextAttrListeners) l.attributeAdded(evt);
    }

    public void fireContextAttributeReplaced(ServletContext ctx, String name, Object oldValue) {
        if (contextAttrListeners.isEmpty()) return;
        var evt = new ServletContextAttributeEvent(ctx, name, oldValue);
        for (var l : contextAttrListeners) l.attributeReplaced(evt);
    }

    public void fireContextAttributeRemoved(ServletContext ctx, String name, Object value) {
        if (contextAttrListeners.isEmpty()) return;
        var evt = new ServletContextAttributeEvent(ctx, name, value);
        for (var l : contextAttrListeners) l.attributeRemoved(evt);
    }

    // ---- Request lifecycle ----

    public void fireRequestInitialized(ServletContext ctx, ServletRequest req) {
        if (requestListeners.isEmpty()) return;
        var evt = new ServletRequestEvent(ctx, req);
        for (var l : requestListeners) l.requestInitialized(evt);
    }

    public void fireRequestDestroyed(ServletContext ctx, ServletRequest req) {
        if (requestListeners.isEmpty()) return;
        var evt = new ServletRequestEvent(ctx, req);
        for (int i = requestListeners.size() - 1; i >= 0; i--) {
            requestListeners.get(i).requestDestroyed(evt);
        }
    }

    public void fireRequestAttributeAdded(ServletContext ctx, ServletRequest req, String n, Object v) {
        if (requestAttrListeners.isEmpty()) return;
        var evt = new ServletRequestAttributeEvent(ctx, req, n, v);
        for (var l : requestAttrListeners) l.attributeAdded(evt);
    }

    public void fireRequestAttributeReplaced(ServletContext ctx, ServletRequest req, String n, Object oldV) {
        if (requestAttrListeners.isEmpty()) return;
        var evt = new ServletRequestAttributeEvent(ctx, req, n, oldV);
        for (var l : requestAttrListeners) l.attributeReplaced(evt);
    }

    public void fireRequestAttributeRemoved(ServletContext ctx, ServletRequest req, String n, Object v) {
        if (requestAttrListeners.isEmpty()) return;
        var evt = new ServletRequestAttributeEvent(ctx, req, n, v);
        for (var l : requestAttrListeners) l.attributeRemoved(evt);
    }

    // ---- Session lifecycle ----

    public void fireSessionCreated(HttpSession session) {
        if (sessionListeners.isEmpty()) return;
        var evt = new HttpSessionEvent(session);
        for (var l : sessionListeners) l.sessionCreated(evt);
    }

    public void fireSessionDestroyed(HttpSession session) {
        if (sessionListeners.isEmpty()) return;
        var evt = new HttpSessionEvent(session);
        for (int i = sessionListeners.size() - 1; i >= 0; i--) {
            sessionListeners.get(i).sessionDestroyed(evt);
        }
    }

    /** {@code HttpServletRequest.changeSessionId} (Servlet 6.1 section 7.3): fired once per change. */
    public void fireSessionIdChanged(HttpSession session, String oldSessionId) {
        if (sessionIdListeners.isEmpty()) return;
        var evt = new HttpSessionEvent(session);
        for (var l : sessionIdListeners) l.sessionIdChanged(evt, oldSessionId);
    }

    public void fireSessionAttributeAdded(HttpSession s, String name, Object value) {
        if (sessionAttrListeners.isEmpty()) return;
        var evt = new HttpSessionBindingEvent(s, name, value);
        for (var l : sessionAttrListeners) l.attributeAdded(evt);
    }

    public void fireSessionAttributeReplaced(HttpSession s, String name, Object oldValue) {
        if (sessionAttrListeners.isEmpty()) return;
        var evt = new HttpSessionBindingEvent(s, name, oldValue);
        for (var l : sessionAttrListeners) l.attributeReplaced(evt);
    }

    public void fireSessionAttributeRemoved(HttpSession s, String name, Object value) {
        if (sessionAttrListeners.isEmpty()) return;
        var evt = new HttpSessionBindingEvent(s, name, value);
        for (var l : sessionAttrListeners) l.attributeRemoved(evt);
    }

    // ---- Accesseurs diagnostic ----

    public List<ServletContextListener> contextListeners() { return Collections.unmodifiableList(contextListeners); }
    public List<ServletRequestListener> requestListeners() { return Collections.unmodifiableList(requestListeners); }
    public List<HttpSessionListener> sessionListeners() { return Collections.unmodifiableList(sessionListeners); }
}
