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

import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.vidocq.foy.internal.security.SecureApp.constraint;
import static org.junit.jupiter.api.Assertions.*;

class TransportGuaranteeEndToEndTest {

    private static WebAppModel model(String guarantee) {
        return WebAppModel.builder("/")
                .servlet(new ServletDecl("who", WebSecurityEndToEndTest.Who.class, WebSecurityEndToEndTest.Who::new,
                        List.of("/conf/*", "/plain/*"), Map.of(), -1, false))
                .securityConstraints(List.of(
                        constraint(List.of("/conf/*"), List.of(), List.of(), null, guarantee),
                        constraint(List.of("/plain/*"), List.of(), List.of(), null, "NONE")))
                .build();
    }

    @Test
    void aConfidentialConstraintRefusesPlainHttpByDefault() throws Exception {
        try (var app = new SecureApp(model("CONFIDENTIAL"))) {
            assertEquals(403, app.get("/conf/x").statusCode());
            assertEquals(200, app.get("/plain/x").statusCode());
        }
    }

    @Test
    void withAnHttpsPortPlainHttpIsRedirectedForConfidentialAndIntegral() throws Exception {
        for (String guarantee : List.of("CONFIDENTIAL", "INTEGRAL")) {
            var settings = SecuritySettings.DEFAULTS.withIdentityStore(SecureApp.USERS).withConfidentialPort(8443);
            try (var app = new SecureApp(model(guarantee), settings)) {
                var r = app.get("/conf/x?y=1");
                assertEquals(302, r.statusCode(), guarantee);
                assertEquals("https://127.0.0.1:8443/conf/x?y=1", r.headers().firstValue("Location").orElse(""), guarantee);
                assertEquals(200, app.get("/plain/x").statusCode(), guarantee);
            }
        }
    }
}
