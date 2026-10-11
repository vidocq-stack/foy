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

import io.vidocq.chappe.api.TlsInfo;

import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/** The Servlet section 3.10 request attributes of a TLS connection, from chappe's {@link TlsInfo}. */
public final class TlsAttributes {
    public static final String X509_CERTIFICATE = "jakarta.servlet.request.X509Certificate";
    public static final String CIPHER_SUITE = "jakarta.servlet.request.cipher_suite";
    public static final String KEY_SIZE = "jakarta.servlet.request.key_size";
    public static final String SSL_SESSION_ID = "jakarta.servlet.request.ssl_session_id";
    public static final String SECURE_PROTOCOL = "jakarta.servlet.request.secure_protocol";

    private TlsAttributes() {}

    /** Puts the attributes straight into the map: they are the container's, so no listener is told. */
    public static void seed(TlsInfo tls, Map<String, Object> attributes) {
        if (!tls.peerCertificates().isEmpty()) {
            attributes.put(X509_CERTIFICATE, tls.peerCertificates().toArray(X509Certificate[]::new));
        }
        if (tls.cipherSuite() != null) attributes.put(CIPHER_SUITE, tls.cipherSuite());
        Integer bits = keySize(tls.cipherSuite());
        if (bits != null) attributes.put(KEY_SIZE, bits);
        byte[] id = tls.sessionId();
        if (id != null && id.length > 0) attributes.put(SSL_SESSION_ID, HexFormat.of().formatHex(id));
        if (tls.protocol() != null) attributes.put(SECURE_PROTOCOL, tls.protocol());
    }

    /**
     * The key size in bits of the suite's bulk cipher, from its standard name (JSSE does not expose
     * it); {@code null} when the name is not recognised.
     */
    public static Integer keySize(String cipherSuite) {
        if (cipherSuite == null) return null;
        String s = cipherSuite.toUpperCase(Locale.ROOT);
        if (s.contains("_WITH_NULL_")) return 0;
        if (s.contains("AES_256") || s.contains("CHACHA20") || s.contains("CAMELLIA_256") || s.contains("ARIA_256")) return 256;
        if (s.contains("AES_128") || s.contains("CAMELLIA_128") || s.contains("ARIA_128") || s.contains("RC4_128")
                || s.contains("IDEA")) return 128;
        if (s.contains("3DES_EDE")) return 168;
        if (s.contains("DES40") || s.contains("DES_40") || s.contains("RC4_40") || s.contains("RC2_CBC_40")) return 40;
        if (s.contains("DES_CBC")) return 56;
        return null;
    }
}
