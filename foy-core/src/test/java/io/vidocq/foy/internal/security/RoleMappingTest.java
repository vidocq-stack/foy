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

import io.vidocq.foy.spi.security.Identity;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RoleMappingTest {

    private static final Identity J2EE = Identity.of("j2ee", Set.of("Administrator", "Employee"));
    private final RoleMapping roles = new RoleMapping(Set.of("Administrator", "Manager", "Employee"),
            Map.of("Reverse", Map.of("MGR", "Administrator", "ADM", "Manager")));

    @Test
    void theExecutingServletsRoleRefWins() {
        assertTrue(roles.isUserInRole("Reverse", J2EE, "MGR"));
        assertFalse(roles.isUserInRole("Reverse", J2EE, "ADM"));
    }

    @Test
    void anUnreferencedRoleIsTestedDirectly() {
        assertTrue(roles.isUserInRole("Reverse", J2EE, "Employee"));
        assertTrue(roles.isUserInRole(null, J2EE, "Administrator"));
        assertFalse(roles.isUserInRole("Other", J2EE, "MGR"));
    }

    @Test
    void starIsNeverARoleAndDoubleStarMeansAuthenticated() {
        assertFalse(roles.isUserInRole(null, J2EE, "*"));
        assertTrue(roles.isUserInRole(null, J2EE, "**"));
        var declared = roles.withDeclaredRoles(Set.of("**"));
        assertFalse(declared.isUserInRole(null, J2EE, "**"), "a declared ** is an ordinary role");
    }

    @Test
    void anonymousAndNullAreNeverInRole() {
        assertFalse(roles.isUserInRole(null, null, "Administrator"));
        assertFalse(roles.isUserInRole(null, J2EE, null));
        assertFalse(roles.isUserInRole(null, null, "**"));
    }
}
