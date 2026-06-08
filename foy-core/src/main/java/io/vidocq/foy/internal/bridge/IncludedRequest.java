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

import io.vidocq.foy.internal.dispatcher.DispatchTarget;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Wrapper for an include: URI reflect methods the original resource
 * (Servlet spec 6.1 §9.3) while the include information is exposed
 * via the {@code jakarta.servlet.include.*} attributes.
 */
public final class IncludedRequest extends HttpServletRequestWrapper {

    private final DispatchTarget target;

    public IncludedRequest(HttpServletRequest original, DispatchTarget target) {
        super(original);
        this.target = target;
    }

    DispatchTarget target() { return target; }

    @Override public DispatcherType getDispatcherType() { return DispatcherType.INCLUDE; }
}
