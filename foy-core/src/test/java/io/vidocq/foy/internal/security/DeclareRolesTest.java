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
package io.vidocq.foy.internal.security;

import io.vidocq.foy.internal.container.VidocqServletContext;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** {@code ServletContext.declareRoles} argument checks (Servlet 6.1 Javadoc). */
class DeclareRolesTest {

    @Test
    void aNullArrayIsAnIllegalArgument() {
        var ctx = new VidocqServletContext("/");
        assertThrows(IllegalArgumentException.class, () -> ctx.declareRoles((String[]) null));
    }

    @Test
    void aNullOrEmptyRoleNameIsAnIllegalArgumentAndDeclaresNothing() {
        var ctx = new VidocqServletContext("/");
        assertThrows(IllegalArgumentException.class, () -> ctx.declareRoles("ok", null));
        assertThrows(IllegalArgumentException.class, () -> ctx.declareRoles("ok", ""));
        assertEquals(Set.of(), ctx.programmaticDeclaredRoles());
    }

    @Test
    void validRolesAreDeclared() {
        var ctx = new VidocqServletContext("/");
        ctx.declareRoles("a", "b");
        assertEquals(Set.of("a", "b"), ctx.programmaticDeclaredRoles());
    }
}
