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

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;

import java.lang.annotation.Annotation;

/**
 * Foy's context for {@code @SessionScoped} (foy#21), registered by {@link FoySessionScopeExtension}
 * on CDI Full containers and by foy-cdi-vauban's build compatible extension on Vauban.
 *
 * <p>Public, with a public no-argument constructor, and not a bean: containers instantiate it
 * themselves. It is stateless — several containers or deployments may create one each, and Weld
 * hands out a wrapper of it — so every call reads the state of the calling thread.</p>
 */
public final class FoySessionContext implements AlterableContext {

    /** Public no-argument constructor, required by {@code MetaAnnotations.addContext}. */
    public FoySessionContext() {}

    @Override
    public Class<? extends Annotation> getScope() {
        return SessionScoped.class;
    }

    @Override
    public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        throw notActive();
    }

    @Override
    public <T> T get(Contextual<T> contextual) {
        throw notActive();
    }

    @Override
    public boolean isActive() {
        return false;
    }

    @Override
    public void destroy(Contextual<?> contextual) {
        throw notActive();
    }

    private static ContextNotActiveException notActive() {
        return new ContextNotActiveException("the Foy session context is not active on " + Thread.currentThread());
    }
}
