package io.vidocq.foy.tck.arquillian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TckIdentitiesTest {

    @Test
    void theTckUsersHaveTheirSunWebXmlRoles() {
        var store = TckIdentities.store();
        assertEquals(Set.of("Administrator", "Employee"),
                store.validate("j2ee", "j2ee".toCharArray()).orElseThrow().roles());
        assertEquals(Set.of("Manager", "Employee"),
                store.validate("javajoe", "javajoe".toCharArray()).orElseThrow().roles());
        assertTrue(store.validate("j2ee", "javajoe".toCharArray()).isEmpty());
    }

    /** The TCK's client certificate, unpacked from the TCK jar by the {@code tck-official} profile. */
    private static final Path CTS_CERT = Path.of(System.getProperty("basedir", "."),
            "target", "tck-certificates", "certificates", "cts_cert");

    static boolean ctsCertificateUnpacked() { return Files.isRegularFile(CTS_CERT); }

    @Test
    @EnabledIf("ctsCertificateUnpacked")
    void theCtsCertificateIsAnAdministrator() throws Exception {
        X509Certificate leaf;
        try (InputStream in = Files.newInputStream(CTS_CERT)) {
            leaf = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        var identity = TckIdentities.store().validate(new X509Certificate[] {leaf}).orElseThrow();
        assertEquals("CN=CTS, OU=Java Software, O=Sun Microsystems Inc., L=Burlington, ST=MA, C=US",
                identity.name());
        assertEquals(Set.of("Administrator"), identity.roles());
    }
}
