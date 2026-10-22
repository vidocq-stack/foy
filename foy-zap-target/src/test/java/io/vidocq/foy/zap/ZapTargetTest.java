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
package io.vidocq.foy.zap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ZapTargetTest {

    @Test
    void thePortIsTheArgumentThenTheEnvironmentThenTheDefault() {
        assertEquals(9000, ZapTarget.port(new String[] {"9000"}, "9100"));
        assertEquals(9100, ZapTarget.port(new String[0], " 9100 "));
        assertEquals(ZapTarget.DEFAULT_PORT, ZapTarget.port(new String[0], null));
        assertEquals(ZapTarget.DEFAULT_PORT, ZapTarget.port(new String[0], " "));
        String[] notANumber = {"http"};
        assertThrows(NumberFormatException.class, () -> ZapTarget.port(notANumber, null));
    }

    @Test
    void theEchoedTextIsEscapedForElementsAndQuotedAttributes() {
        assertEquals("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; &quot;q&quot;",
                Html.escape("<script>alert('x')</script> & \"q\""));
    }
}
