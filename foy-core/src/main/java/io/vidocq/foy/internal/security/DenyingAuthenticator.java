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

import io.vidocq.foy.internal.bridge.HttpServletRequestImpl;
import io.vidocq.foy.spi.security.Identity;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Optional;

/**
 * The mechanism of an application whose {@code auth-method} Foy does not support (CLIENT-CERT,
 * DIGEST, a custom name). It fails closed: a protected resource answers 403 and {@code login()}
 * throws.
 */
public final class DenyingAuthenticator implements Authenticator {

    private final String authMethod;

    public DenyingAuthenticator(String authMethod) { this.authMethod = authMethod; }

    @Override public String authType() { return authMethod; }
    @Override public Optional<Identity> authenticate(HttpServletRequestImpl req) { return Optional.empty(); }
    @Override public void challenge(HttpServletRequestImpl req, HttpServletResponse res) throws IOException {
        res.sendError(HttpServletResponse.SC_FORBIDDEN);
    }
    @Override public boolean supportsPasswordLogin() { return false; }
}
