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
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Optional;

/** One HTTP authentication mechanism of a web application (Servlet 6.1 section 13.6), chosen from {@code login-config}. */
public interface Authenticator {

    /** The value of {@code getAuthType()} for an identity this mechanism established. */
    String authType();

    /** The identity the request carries by itself (BASIC: its {@code Authorization} header); empty otherwise. */
    Optional<Identity> authenticate(HttpServletRequestImpl req);

    /** Asks the client for credentials and produces the response (BASIC: 401 with the challenge). */
    void challenge(HttpServletRequestImpl req, HttpServletResponse res) throws IOException, ServletException;

    /** Whether {@code HttpServletRequest.login(user, password)} is possible under this mechanism. */
    default boolean supportsPasswordLogin() { return true; }

    /** Whether an established identity is kept in the session (FORM, section 13.7). */
    default boolean sessionBased() { return false; }
}
