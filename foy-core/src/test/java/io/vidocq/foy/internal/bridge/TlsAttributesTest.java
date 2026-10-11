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
import io.vidocq.foy.internal.TlsTestCertificates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TlsAttributesTest {
    @ParameterizedTest
    @CsvSource({
            "TLS_AES_128_GCM_SHA256,128", "TLS_AES_256_GCM_SHA384,256", "TLS_CHACHA20_POLY1305_SHA256,256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,128", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384,256",
            "TLS_RSA_WITH_3DES_EDE_CBC_SHA,168", "TLS_RSA_WITH_NULL_SHA256,0", "SSL_RSA_EXPORT_WITH_DES40_CBC_SHA,40",
            "SSL_RSA_WITH_DES_CBC_SHA,56", "TLS_ECDHE_RSA_WITH_CAMELLIA_256_CBC_SHA384,256"})
    void keySizeFollowsTheBulkCipher(String suite, int bits) { assertEquals(bits, TlsAttributes.keySize(suite)); }

    @Test
    void anUnknownSuiteHasNoKeySize() {
        assertNull(TlsAttributes.keySize("TLS_FUTURE_CIPHER_SHA999"));
        assertNull(TlsAttributes.keySize(null));
    }

    @Test
    void seedingFillsTheFiveAttributes() {
        var cert = TlsTestCertificates.get().certificate("alice");
        Map<String, Object> m = new HashMap<>();
        TlsAttributes.seed(new TlsInfo("TLSv1.3", "TLS_AES_128_GCM_SHA256", new byte[] {0x0a, (byte) 0xff}, List.of(cert)), m);
        assertArrayEquals(new X509Certificate[] {cert}, (X509Certificate[]) m.get("jakarta.servlet.request.X509Certificate"));
        assertEquals("TLS_AES_128_GCM_SHA256", m.get("jakarta.servlet.request.cipher_suite"));
        assertEquals(128, m.get("jakarta.servlet.request.key_size"));
        assertEquals("0aff", m.get("jakarta.servlet.request.ssl_session_id"));
        assertEquals("TLSv1.3", m.get("jakarta.servlet.request.secure_protocol"));
    }

    @Test
    void noChainNoSessionIdNoKnownSuiteLeaveThoseAttributesOut() {
        Map<String, Object> m = new HashMap<>();
        TlsAttributes.seed(new TlsInfo("TLSv1.3", "TLS_FUTURE_CIPHER_SHA999", new byte[0], List.of()), m);
        assertEquals(Set.of("jakarta.servlet.request.cipher_suite", "jakarta.servlet.request.secure_protocol"), m.keySet());
    }
}
