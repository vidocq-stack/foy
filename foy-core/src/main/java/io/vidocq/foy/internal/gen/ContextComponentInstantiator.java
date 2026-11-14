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
package io.vidocq.foy.internal.gen;

import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.spi.gen.ComponentInstantiator;
import jakarta.servlet.ServletException;

import java.util.Objects;

/**
 * Exposes a context's component factory as a {@link ComponentInstantiator}. It asks the context
 * on every call, so a later {@code setComponentFactory} is honoured.
 */
public final class ContextComponentInstantiator implements ComponentInstantiator {

    private final VidocqServletContext context;

    public ContextComponentInstantiator(VidocqServletContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public <T> T newInstance(Class<T> type) throws ServletException {
        return context.componentFactory().newInstance(type);
    }
}
