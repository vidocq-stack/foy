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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FormAuthenticatorTest {

    @Test
    void safeTargetKeepsContextPaths() {
        assertEquals("/app/secure/page?x=1", FormAuthenticator.safeTarget("/app/secure/page?x=1", "/app"));
        assertEquals("/secure", FormAuthenticator.safeTarget("/secure", ""));
    }

    @Test
    void safeTargetRefusesForeignTargets() {
        for (String bad : new String[] {null, "http://evil.example/", "//evil.example/x", "/other/x", "/app\\x",
                "/app/x\r\nSet-Cookie: a=b", "/appx"}) {
            assertEquals("/app/", FormAuthenticator.safeTarget(bad, "/app"), String.valueOf(bad));
        }
        assertEquals("/", FormAuthenticator.safeTarget("//evil.example", ""));
    }

    @Test
    void loginActionIsAnySegmentNamedJSecurityCheck() {
        var form = new FormAuthenticator(SecureApp.USERS, "/login", "/error");
        assertTrue(form.isLoginAction("/j_security_check"));
        assertTrue(form.isLoginAction("/sub/j_security_check"));
        assertFalse(form.isLoginAction("/j_security_check2"));
        assertFalse(form.isLoginAction("/xj_security_check"));
    }
}
