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
package io.vidocq.foy.internal;

import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.security.WebSecurity;

/**
 * Test support: a context that is not deployed has no security and refuses every request
 * (fail closed). A test that builds a bridge by hand opts into "no constraint" explicitly here.
 */
public final class TestSecurity {

    /** Gives {@code ctx} a permit-all security over its current settings; returns it. */
    public static <C extends VidocqServletContext> C permitAll(C ctx) {
        ctx.setSecurity(WebSecurity.permitAll(ctx.securitySettings()));
        return ctx;
    }

    private TestSecurity() {}
}
