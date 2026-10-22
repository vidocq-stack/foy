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

import io.vidocq.foy.internal.TlsTestCertificates;
import io.vidocq.foy.spi.security.Identity;
import io.vidocq.foy.spi.security.IdentityStore;
import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CertificateIdentityTest {
    private static final TlsTestCertificates CERTS = TlsTestCertificates.get();

    @Test
    void aStoreThatDoesNotOverrideRefusesEveryCertificate() {
        IdentityStore passwordsOnly = (u, p) -> Optional.empty();
        assertTrue(passwordsOnly.validate(new X509Certificate[] {CERTS.certificate("alice")}).isEmpty());
    }

    @Test
    void aMappedCertificateGivesItsRolesAndTheRfc1779Name() {
        var store = InMemoryIdentityStore.builder().certificateUser(TlsTestCertificates.ALICE_DN, "admin").build();
        var id = store.validate(new X509Certificate[] {CERTS.certificate("alice")}).orElseThrow();
        assertEquals("CN=Alice Example, OU=Foy Tests, O=Vidocq, C=FR", id.name());
        assertEquals(Set.of("admin"), id.roles());
    }

    @Test
    void aDnMatchesWhateverItsSpacing() {
        var store = InMemoryIdentityStore.builder()
                .certificateUser("CN=Alice Example,OU=Foy Tests ,  O=Vidocq,C=FR", "admin").build();
        assertTrue(store.validate(new X509Certificate[] {CERTS.certificate("alice")}).isPresent());
    }

    @Test
    void aDnMatchesWhateverItsCase() {
        var store = InMemoryIdentityStore.builder()
                .certificateUser("cn=ALICE EXAMPLE, ou=foy tests, o=VIDOCQ, c=fr", "admin").build();
        assertTrue(store.validate(new X509Certificate[] {CERTS.certificate("alice")}).isPresent());
    }

    private static final String ADMIN_DN = "CN=admin, O=Example, C=FR";

    private static boolean accepts(String declared, String presented) {
        var store = InMemoryIdentityStore.builder().certificateUser(declared, "admin").build();
        return store.validate(new X509Certificate[] {CERTS.issue(presented)}).isPresent();
    }

    @Test
    void theIssuedSubjectItselfMatches() {
        assertTrue(accepts(ADMIN_DN, ADMIN_DN));
    }

    @Test
    void aFullwidthLookalikeSubjectIsRefused() {
        assertFalse(accepts(ADMIN_DN, "CN=ａdmin, O=Example, C=FR"));
    }

    @Test
    void aDotlessILookalikeSubjectIsRefused() {
        assertFalse(accepts(ADMIN_DN, "CN=admın, O=Example, C=FR"));
    }

    @Test
    void reorderedRdnsAreRefused() {
        assertFalse(accepts(ADMIN_DN, "O=Example, CN=admin, C=FR"));
    }

    @Test
    void onlyTheLeafIsMapped() {
        var store = InMemoryIdentityStore.builder().certificateUser(TlsTestCertificates.ALICE_DN, "admin").build();
        assertTrue(store.validate(new X509Certificate[] {CERTS.certificate("bob"), CERTS.certificate("alice")}).isEmpty());
    }

    @Test
    void anUnmappedEmptyOrNullChainIsRefused() {
        var store = InMemoryIdentityStore.builder().certificateUser(TlsTestCertificates.ALICE_DN, "admin").build();
        assertTrue(store.validate(new X509Certificate[] {CERTS.certificate("bob")}).isEmpty());
        assertTrue(store.validate(new X509Certificate[0]).isEmpty());
        assertTrue(store.validate((X509Certificate[]) null).isEmpty());
    }

    @Test
    void aMalformedOrDuplicateDnIsRejectedAtBuild() {
        var b = InMemoryIdentityStore.builder().certificateUser(TlsTestCertificates.ALICE_DN, "admin");
        assertThrows(IllegalArgumentException.class, () -> b.certificateUser("CN=Alice Example,OU=Foy Tests,O=Vidocq,C=FR"));
        var fresh = InMemoryIdentityStore.builder();
        assertThrows(IllegalArgumentException.class, () -> fresh.certificateUser("not a dn"));
    }

    @Test
    void passwordUsersAndCertificateUsersLiveSideBySide() {
        var store = InMemoryIdentityStore.builder().user("carol", "c".toCharArray(), "user")
                .certificateUser(TlsTestCertificates.ALICE_DN, "admin").build();
        assertTrue(store.validate("carol", "c".toCharArray()).isPresent());
        assertTrue(store.validate(new X509Certificate[] {CERTS.certificate("alice")}).isPresent());
    }

    @Test
    void credentialCheckTurnsAThrowingOrNullStoreIntoARefusal() {
        IdentityStore throwing = new IdentityStore() {
            @Override public Optional<Identity> validate(String u, char[] p) { return Optional.empty(); }
            @Override public Optional<Identity> validate(X509Certificate[] c) { throw new IllegalStateException("down"); }
        };
        IdentityStore nulls = new IdentityStore() {
            @Override public Optional<Identity> validate(String u, char[] p) { return Optional.empty(); }
            @Override public Optional<Identity> validate(X509Certificate[] c) { return null; }
        };
        var chain = new X509Certificate[] {CERTS.certificate("alice")};
        assertTrue(CredentialCheck.validate(throwing, chain).isEmpty());
        assertTrue(CredentialCheck.validate(nulls, chain).isEmpty());
    }
}
