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
package io.vidocq.foy.internal.cdi;

import io.vidocq.foy.internal.bridge.HttpServletRequestImpl;
import io.vidocq.foy.internal.session.HttpSessionImpl;
import io.vidocq.foy.internal.session.SessionLifecycleHook;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives Foy's session context (foy#21): binds it for each request and destroys the session beans
 * when their session ends.
 *
 * <p>As a request listener, registered by the deployment just inside {@link
 * io.vidocq.foy.internal.listener.CdiRequestScopeListener}, it binds the context when the request
 * starts and unbinds it when it ends, on the request thread. If another session context is already
 * active on that thread (an embedding runtime, a Weld bound context the application activated), it
 * steps aside: CDI forbids two active contexts for a scope (§6.5.1).</p>
 *
 * <p>As the {@link SessionLifecycleHook} of the session manager, it serves a session created by the
 * current request from the request's binding at once, before the application's
 * {@code sessionCreated} listeners run, then fires {@code @Initialized(SessionScoped.class)}; a
 * session bean used from those callbacks resolves to the new session. It destroys the beans of a
 * dying session: at the end of the request that invalidated it ("at the very end of any request in
 * which invalidate() was called"), or at once on expiry and undeploy, after the application's
 * {@code sessionDestroyed} listeners, which still see the context active. Destruction fires
 * {@code @BeforeDestroyed}, destroys every instance (a failure is logged, the others go on), then
 * fires {@code @Destroyed}; the payload is the {@code HttpSession}, and a failing observer is
 * logged. Once destroyed, the session's beans cannot be created again (see
 * {@link SessionBeanStore#of}). Outside a request, a request context is activated for the duration
 * when the container provides {@code RequestContextController} (Vauban does not), so that
 * {@code @PreDestroy} may then use request-scoped beans.</p>
 *
 * <p>Threads started for asynchronous work ({@code AsyncContext.start}) are not bound, as for the
 * request context (BUG-20261010-07).</p>
 */
public final class CdiSessionScopeListener implements ServletRequestListener, SessionLifecycleHook {

    private static final System.Logger LOG = System.getLogger(CdiSessionScopeListener.class.getName());

    private final BeanManager beanManager;
    private final Map<ServletRequest, SessionContextBinding> bound = new ConcurrentHashMap<>();
    /** The session contexts of the container; fixed once it is booted, read on first use. */
    private volatile List<Context> sessionContexts;

    public CdiSessionScopeListener(BeanManager beanManager) {
        this.beanManager = beanManager;
    }

    // ---- request listener ----

    @Override
    public void requestInitialized(ServletRequestEvent event) {
        if (event.getServletRequest() instanceof HttpServletRequestImpl request) {
            bind(request, SessionContextBinding.SessionSource.of(request));
        }
    }

    /** Binds the context to {@code request} on the current thread, unless {@linkplain #stepsAside() stepping aside}. */
    void bind(ServletRequest request, SessionContextBinding.SessionSource source) {
        if (stepsAside()) return;
        bound.put(request, SessionContextBinding.bind(source));
    }

    @Override
    public void requestDestroyed(ServletRequestEvent event) {
        SessionContextBinding binding = bound.remove(event.getServletRequest());
        if (binding == null) return;
        List<HttpSessionImpl> dying = binding.invalidatedHere();
        binding.unbind();
        for (HttpSessionImpl session : dying) destroyScope(session);
    }

    // ---- session lifecycle hook ----

    @Override
    public void sessionCreated(HttpSessionImpl session) {
        SessionContextBinding current = SessionContextBinding.current();
        if (current == null) {
            if (stepsAside()) return;
        } else if (current.isRequestBinding()) {
            // Created by this request, which does not hold it yet: serve it now, so that a session
            // bean used from the creation callbacks does not create another session.
            current.adopt(session);
        }
        fire(Initialized.Literal.of(SessionScoped.class), session);
    }

    @Override
    public void aroundDestruction(HttpSessionImpl session, Runnable destruction) {
        SessionContextBinding current = SessionContextBinding.current();
        if (current != null && current.isRequestBinding() && current.owns(session)) {
            // invalidate() from the request using this session: destroyed when the request ends.
            current.markInvalidatedHere(session);
            destruction.run();
            return;
        }
        if (stepsAside()) {
            // Another session context serves this thread: Foy's events would duplicate its own.
            try {
                destruction.run();
            } finally {
                destroyBeans(session);
            }
            return;
        }
        // Activated first and bound inside the try: whatever escapes (an Error from the
        // RequestContextController lookup included), the reaper's reused thread keeps no binding.
        RequestContextController requestContext = activateRequestContext();
        SessionContextBinding binding = null;
        try {
            binding = SessionContextBinding.bindTo(session);
            destruction.run();
        } finally {
            if (binding != null) binding.unbind();
            try {
                destroyScope(session);
            } finally {
                if (requestContext != null) requestContext.deactivate();
            }
        }
    }

    // ---- internals ----

    /** {@code @BeforeDestroyed}, the beans' destruction (context bound to the dying session), {@code @Destroyed}. */
    private void destroyScope(HttpSessionImpl session) {
        SessionContextBinding binding = SessionContextBinding.bindTo(session);
        try {
            fire(BeforeDestroyed.Literal.of(SessionScoped.class), session);
            destroyBeans(session);
        } finally {
            binding.unbind();
        }
        fire(Destroyed.Literal.of(SessionScoped.class), session);
    }

    /** Detaches the beans of {@code session}, leaving it unable to create new ones, and destroys them. */
    private static void destroyBeans(HttpSessionImpl session) {
        SessionBeanStore store = SessionBeanStore.take(session);
        if (store != null) store.destroyAll();
    }

    private void fire(Annotation qualifier, HttpSessionImpl session) {
        try {
            beanManager.getEvent().select(qualifier).fire(session);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "an observer of " + qualifier + " failed for session " + session.getId(), e);
        }
    }

    /**
     * {@code true} when Foy's context must stay out of this thread: no Foy binding exists on it and
     * another session context is active. Without a Foy binding, Foy's own context reports itself
     * inactive, so any active one is another container's.
     */
    private boolean stepsAside() {
        return SessionContextBinding.current() == null && otherSessionContextActive();
    }

    private boolean otherSessionContextActive() {
        List<Context> contexts = sessionContexts;
        if (contexts == null) {
            try {
                contexts = List.copyOf(beanManager.getContexts(SessionScoped.class));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "no session contexts from the BeanManager: {0}", e.toString());
                contexts = List.of();
            }
            sessionContexts = contexts;
        }
        for (Context context : contexts) {
            try {
                if (context.isActive()) return true;
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "isActive failed on {0}: {1}", context, e.toString());
            }
        }
        return false;
    }

    /**
     * Activates a request context for a destruction outside a request; {@code null} when none was
     * activated (one is already active, or the container has no {@code RequestContextController}).
     */
    private RequestContextController activateRequestContext() {
        try {
            var controllers = beanManager.createInstance().select(RequestContextController.class);
            if (!controllers.isResolvable()) return null;
            RequestContextController controller = controllers.get();
            return controller.activate() ? controller : null;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "No RequestContextController: {0}", e.toString());
            return null;
        }
    }
}
