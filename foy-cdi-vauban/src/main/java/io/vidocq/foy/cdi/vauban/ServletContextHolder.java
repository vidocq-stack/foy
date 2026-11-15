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
package io.vidocq.foy.cdi.vauban;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.servlet.ServletContext;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The {@link ServletContext} of the application deployed on this CDI container: {@link ServletContextBinding}
 * attaches it before the application's {@code contextInitialized} and detaches it after its
 * {@code contextDestroyed}, and {@link ServletBuiltInBeans} produces the {@code ServletContext} bean from it.
 *
 * <p>One deployment per CDI container, as Vidocq runs it: a second deployment on the same container while the
 * first is up does not take the bean over, and gets one warning naming both context paths. Once the first is
 * undeployed, the next deployment attaches its own (a dev-mode reload).</p>
 */
@ApplicationScoped
class ServletContextHolder {

    private static final System.Logger LOG = System.getLogger(ServletContextHolder.class.getName());

    /** Why there is no context before the deployment or after the undeploy. */
    static final String NO_CONTEXT = "No ServletContext: Foy has not deployed the application on this CDI container "
            + "yet, or has undeployed it; the ServletContext bean is there from the first contextInitialized of the "
            + "deployment to its last contextDestroyed";

    private final AtomicReference<ServletContext> attached = new AtomicReference<>();

    /**
     * Attaches the context of a deployment, unless the context of another one is attached: that one stays, and a
     * warning names both.
     */
    void attach(ServletContext context) {
        ServletContext held = attached.compareAndExchange(null, context);
        if (held != null) {
            LOG.log(System.Logger.Level.WARNING, "The ServletContext bean stays the one of the application deployed "
                            + "at {0}: the application deployed at {1} runs on the same CDI container and does not get "
                            + "its own. Foy on Vauban gives the Servlet built-in beans to one deployment per CDI "
                            + "container.",
                    displayPath(held), displayPath(context));
        }
    }

    /**
     * Detaches the context of a deployment being undeployed.
     *
     * @return whether {@code context} was the one the bean gave
     */
    boolean detach(ServletContext context) {
        return attached.compareAndSet(context, null);
    }

    /**
     * The context of the application deployed on this container.
     *
     * @throws IllegalStateException before the deployment, or after the undeploy
     */
    ServletContext context() {
        ServletContext context = attached.get();
        if (context == null) {
            throw new IllegalStateException(NO_CONTEXT);
        }
        return context;
    }

    /** The context path as a log names it: {@code /} for the root context. */
    private static String displayPath(ServletContext context) {
        String path = context.getContextPath();
        return path.isEmpty() ? "/" : path;
    }
}
