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
import io.vidocq.foy.spi.security.IdentityStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.Optional;

/**
 * CLIENT-CERT (Servlet section 13.6.4): the identity is the leaf of the TLS client certificate
 * chain, mapped by the identity store. The chain was checked by the TLS layer against the
 * listener's trust material; a request without one is forbidden, since a certificate cannot be
 * asked for after the handshake (no renegotiation under TLS 1.3 or HTTP/2). Password login stays
 * possible through the store.
 */
public final class ClientCertAuthenticator implements Authenticator {

    private final IdentityStore store;

    public ClientCertAuthenticator(IdentityStore store) { this.store = Objects.requireNonNull(store, "store"); }

    @Override public String authType() { return HttpServletRequest.CLIENT_CERT_AUTH; }

    @Override public Optional<Identity> authenticate(HttpServletRequestImpl req) {
        if (!req.isSecure()) return Optional.empty();
        X509Certificate[] chain = req.peerCertificates();
        return chain.length == 0 ? Optional.empty() : CredentialCheck.validate(store, chain);
    }

    @Override public void challenge(HttpServletRequestImpl req, HttpServletResponse res) throws IOException {
        res.sendError(HttpServletResponse.SC_FORBIDDEN);
    }
}
