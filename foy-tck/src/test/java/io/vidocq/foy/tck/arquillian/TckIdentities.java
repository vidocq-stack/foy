package io.vidocq.foy.tck.arquillian;

import io.vidocq.foy.internal.security.InMemoryIdentityStore;
import io.vidocq.foy.spi.security.IdentityStore;

/**
 * The users the Servlet TCK security tests log in with. They restate the GlassFish principal-to-role
 * mapping of the security wars' {@code sun-web.xml}, which Foy never reads. Nobody holds {@code VP}:
 * the tests assert {@code isUserInRole("VP") == false}. Keep in sync with the {@code user},
 * {@code password}, {@code authuser} and {@code authpassword} properties of {@code foy-tck/pom.xml}.
 * The CLIENT-CERT tests present the TCK's {@code certificates/cts_cert}, whose subject maps to
 * {@code Administrator} as in the {@code clientcert} wars' {@code sun-web.xml}.
 */
final class TckIdentities {

    private TckIdentities() {}

    static IdentityStore store() {
        return InMemoryIdentityStore.builder()
                .user("j2ee", "j2ee".toCharArray(), "Administrator", "Employee")
                .user("javajoe", "javajoe".toCharArray(), "Manager", "Employee")
                .certificateUser("CN=CTS, OU=Java Software, O=Sun Microsystems Inc., L=Burlington, ST=MA, C=US",
                        "Administrator")
                .build();
    }
}
