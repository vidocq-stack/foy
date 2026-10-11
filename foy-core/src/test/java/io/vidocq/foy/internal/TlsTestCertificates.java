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
package io.vidocq.foy.internal;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Throw-away TLS material for the tests, generated once per JVM with the JDK's {@code keytool}: a
 * server key ({@code CN=localhost}, SAN 127.0.0.1 and localhost) and two client keys, {@code alice} and
 * {@code bob}. The server trusts both clients; a client trusts the server.
 */
public final class TlsTestCertificates {
    public static final String ALICE_DN = "CN=Alice Example, OU=Foy Tests, O=Vidocq, C=FR";
    public static final String BOB_DN = "CN=Bob Example, OU=Foy Tests, O=Vidocq, C=FR";
    private static final char[] PW = "changeit".toCharArray();
    private static TlsTestCertificates instance;

    public static synchronized TlsTestCertificates get() {
        if (instance == null) instance = new TlsTestCertificates();
        return instance;
    }

    private final Path dir;

    private TlsTestCertificates() {
        try {
            dir = Files.createTempDirectory("foy-tls");
            genKeyPair("server", "CN=localhost, O=Foy Tests", "SAN=ip:127.0.0.1,dns:localhost");
            genKeyPair("alice", ALICE_DN, null);
            genKeyPair("bob", BOB_DN, null);
            for (String who : List.of("server", "alice", "bob")) export(who);
            importCert("server-trust.p12", "alice");
            importCert("server-trust.p12", "bob");
            importCert("client-trust.p12", "server");
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private void genKeyPair(String alias, String dname, String ext) {
        var args = new ArrayList<>(List.of("-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "3650", "-dname", dname, "-keystore", dir.resolve(alias + ".p12").toString(),
                "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit"));
        if (ext != null) { args.add("-ext"); args.add(ext); }
        keytool(args);
    }

    private void export(String alias) {
        keytool(List.of("-exportcert", "-rfc", "-alias", alias, "-keystore", dir.resolve(alias + ".p12").toString(),
                "-storepass", "changeit", "-file", dir.resolve(alias + ".pem").toString()));
    }

    private void importCert(String store, String alias) {
        keytool(List.of("-importcert", "-noprompt", "-alias", alias, "-file", dir.resolve(alias + ".pem").toString(),
                "-keystore", dir.resolve(store).toString(), "-storetype", "PKCS12", "-storepass", "changeit"));
    }

    public SSLContext serverContext() { return context(dir.resolve("server.p12"), dir.resolve("server-trust.p12")); }
    public SSLContext clientContext(String who) { return context(dir.resolve(who + ".p12"), dir.resolve("client-trust.p12")); }
    public SSLContext clientContextWithoutCertificate() { return context(null, dir.resolve("client-trust.p12")); }

    public X509Certificate certificate(String who) {
        try (var in = Files.newInputStream(dir.resolve(who + ".pem"))) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (IOException | CertificateException e) { throw new IllegalStateException(e); }
    }

    private static SSLContext context(Path keyStore, Path trustStore) {
        try {
            KeyManager[] km = null;
            if (keyStore != null) {
                var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(KeyStore.getInstance(keyStore.toFile(), PW), PW);
                km = kmf.getKeyManagers();
            }
            var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(KeyStore.getInstance(trustStore.toFile(), PW));
            var ctx = SSLContext.getInstance("TLS");
            ctx.init(km, tmf.getTrustManagers(), null);
            return ctx;
        } catch (GeneralSecurityException | IOException e) { throw new IllegalStateException(e); }
    }

    private static void keytool(List<String> args) {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(args);
        try {
            var process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
                    .start();
            // keytool prints little; read it after exit would risk a full pipe, so drain concurrently.
            var output = new String[1];
            var reader = Thread.ofVirtual().start(() -> {
                try { output[0] = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); }
                catch (IOException e) { output[0] = e.toString(); }
            });
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("keytool timed out: " + args);
            }
            reader.join();
            if (process.exitValue() != 0) {
                throw new AssertionError("keytool " + args + " exited " + process.exitValue() + ": " + output[0]);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
    }
}
