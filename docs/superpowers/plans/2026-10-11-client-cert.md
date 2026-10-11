# CLIENT-CERT Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Foy authenticates `CLIENT-CERT` applications from the TLS client certificate chappe now exposes, and it sets the Servlet §3.10 TLS request attributes. The two official TCK CLIENT-CERT tests pass, and BUG-20261011-03 and BUG-20261011-11 are closed.

**Architecture:**
- chappe (main `261e6d2`, chappe#28 and chappe#29) supplies `Request.tls()` → `Optional<TlsInfo>`, plus `ClientAuth` with `Server.Builder.tls(SSLContext, ClientAuth)`.
- Foy reads it in three places:
  - `HttpServletRequestImpl` seeds the five §3.10 attributes from it;
  - a new `ClientCertAuthenticator` maps the leaf certificate to an `Identity`, through a new default method `IdentityStore.validate(X509Certificate[])`;
  - foy-tck gets an `https` Arquillian container backed by a TLS `ServletTestHost`, with keystores generated at build time.

**Tech Stack:** Java 25, Jakarta Servlet 6.1, chappe 0.4.0-SNAPSHOT, JUnit 5, Arquillian (foy-tck), keytool.

**Spec:** the maintainer decisions and controller rulings in `chappe-tls-decisions.md`, and the research in `chappe-clientcert-research.md`, both in the controller's scratchpad, with Servlet 6.1 §3.10 and §13.6.3. The rulings that bind this plan are copied into Global Constraints.

## Global Constraints

- **Language:** English everywhere: code, Javadoc, comments, tests, commits and docs. Say "Java Modules", never the abbreviation.
- **Dependencies:**
  - Zero runtime dependencies. `java.security.cert` and `javax.security.auth.x500` are in `java.base`, so no new `requires` is needed.
  - Build-time tooling (foy-tck only) may use `maven-antrun-plugin` and `maven-dependency-plugin`. Name them in `docs/en/modules/ROOT/pages/tck.adoc`.
- **TDD:** write the failing test first. Record RED and GREEN, with the number of tests that ran (a 0-test SUCCESS is neither).
- **Cert → identity:** a default method on Foy's `IdentityStore`, `Optional<Identity> validate(X509Certificate[] chain)`, which refuses by default. `IdentityStore` stays a `@FunctionalInterface`.
- **In-memory store:** it maps a subject DN to roles, compared as `X500Principal` (canonical form). The principal name is the certificate subject in RFC 1779 form, `X500Principal.getName(X500Principal.RFC1779)`. Example: `CN=CTS, OU=Java Software, O=Sun Microsystems Inc., L=Burlington, ST=MA, C=US`.
- **Builders:** `FoyChappeBoot.Builder.certificateUser(String subjectDn, String... roles)`. Like `user(...)`, it is exclusive with `identityStore(...)`.
- **CLIENT-CERT behaviour:**
  - `getAuthType()` is `HttpServletRequest.CLIENT_CERT_AUTH` (`"CLIENT_CERT"`, with an underscore).
  - Without a certificate, or with an unmapped certificate, the answer is **403** (fail closed; no renegotiation is possible).
  - `HttpServletRequest.login(user, password)` is **allowed** under CLIENT-CERT and goes through the store's password path.
- **§3.10 attributes:** set when `Request.tls()` is present, and seeded without firing `ServletRequestAttributeListener`.
  - `jakarta.servlet.request.X509Certificate` → `X509Certificate[]`, only when the chain is non-empty;
  - `jakarta.servlet.request.cipher_suite` → `String`;
  - `jakarta.servlet.request.key_size` → `Integer`, derived in Foy from the cipher-suite name, and absent when the suite is unknown;
  - `jakarta.servlet.request.ssl_session_id` → lowercase hex `String`, absent when the id is empty;
  - `jakarta.servlet.request.secure_protocol` → `String`, for example `TLSv1.3` (new in Servlet 6.1).
- **foy-tck keystores:** the server keystore is generated at build time (keytool, under the `tck-official` profile), with `SAN=ip:127.0.0.1,dns:localhost`. The client certificate is the TCK jar's `certificates/clientcert.jks` (JKS, alias `cts`, password `changeit`). The CTS DN maps to role `Administrator`.
- **Commits:**
  - `git commit -S -s`, Conventional Commits, explicit paths only.
  - The last paragraph is exactly `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` followed by `Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi`.
- **Build:**
  - Run `./mvnw -ntp …` from the foy root with `-Dmaven.repo.local=<scratchpad>/m2 -Dmaven.repo.local.tail=$HOME/.m2/repository`. The chappe main snapshot is installed into that private repository before Task 1.
  - Select tests with `-Dtest='A,B'` (commas).
  - Known unrelated failure: `FoyWebExtensionTest.requestScopedThroughFoyChappeBoot` (BUG-20261011-02).
- **BUG.md:** follow the file's conventions. This PR closes BUG-20261011-03 and -11. A new bug found on 2026-10-11 takes the next free id after the last `BUG-20261011-NN` in the file.

## Review Focus

1. **The same DN spelled differently** (`CN=CTS,OU=…` vs `CN=CTS, OU=…`, different attribute case) must map to the same user (Task 1 test `aDnMatchesWhateverItsSpacing`).
2. **An application that overwrites, mutates or removes `jakarta.servlet.request.X509Certificate`** must not change who the request is authenticated as, nor what the next request sees (Task 2 test `theCertificateArrayIsPerRequest`, Task 3 test `authenticationReadsTheConnectionNotTheAttribute`).
3. **A plain-HTTP request** carries none of the five attributes, and `getAttributeNames()` is unchanged (Task 2 test `aPlainRequestHasNoTlsAttribute`).
4. **HTTP/2 over TLS** gets the attributes and the authentication exactly like HTTP/1.1 (Task 2 and Task 3 tests are parameterized on both versions).
5. **A trusted certificate that is not mapped**, or an `IdentityStore` that throws or returns `null`, gives 403, never 500 and never access (Task 3 tests `aTrustedButUnmappedCertificateIsForbidden` and `aThrowingStoreIsForbidden`).

---

## Pre-step (controller, before Task 1)

- Branch `pr/ybl/foy-client-cert` from `origin/main` `12ab9d6` (done).
- Install chappe main `261e6d2` into the private repository: in `chappe/`, `./mvnw -ntp install -DskipTests -Dmaven.repo.local=<scratchpad>/m2 -Dmaven.repo.local.tail=$HOME/.m2/repository`. Check that `chappe-api` has `TlsInfo`, `ClientAuth` and `Request.tls()`.

---

### Task 1: Certificate identities in the identity SPI, plus the TLS test fixtures

**Files:**
- Modify: `foy-api/src/main/java/io/vidocq/foy/spi/security/IdentityStore.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/security/InMemoryIdentityStore.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/security/CredentialCheck.java`
- Modify: `foy-chappe/src/main/java/io/vidocq/foy/chappe/FoyChappeBoot.java` (Builder)
- Create: `foy-core/src/test/java/io/vidocq/foy/internal/TlsTestCertificates.java`
- Create: `foy-core/src/test/java/io/vidocq/foy/internal/security/CertificateIdentityTest.java`
- Create: `foy-chappe/src/test/resources/tls/alice.pem` (a public certificate only, no key)
- Modify: `foy-chappe/src/test/java/io/vidocq/foy/chappe/FoyChappeBootTest.java`

**Interfaces:**
- Produces:
  - `IdentityStore#validate(X509Certificate[] chain)` → `Optional<Identity>`, a default method that returns `Optional.empty()`.
  - `InMemoryIdentityStore.Builder#certificateUser(String subjectDn, String... roles)`.
  - `CredentialCheck.validate(IdentityStore store, X509Certificate[] chain)` → `Optional<Identity>`.
  - `FoyChappeBoot.Builder#certificateUser(String subjectDn, String... roles)`.
  - The test fixture `io.vidocq.foy.internal.TlsTestCertificates` (public), with:
    - `static TlsTestCertificates get()`, generated once per JVM;
    - `SSLContext serverContext()`: the server key `CN=localhost`, SAN `ip:127.0.0.1,dns:localhost`, trusting the client certificates `alice` and `bob`;
    - `SSLContext clientContext(String who)`, with `who` = `"alice"` or `"bob"`: that client key, trusting the server certificate;
    - `SSLContext clientContextWithoutCertificate()`: trusts the server, has no key;
    - `X509Certificate certificate(String who)`.
  - Constants: `ALICE_DN = "CN=Alice Example, OU=Foy Tests, O=Vidocq, C=FR"` and `BOB_DN = "CN=Bob Example, OU=Foy Tests, O=Vidocq, C=FR"`.

- [ ] **Step 1: Write the fixture `TlsTestCertificates`.** It drives `${java.home}/bin/keytool` through one helper:
  - stdin from the null device;
  - `waitFor(60, SECONDS)`, otherwise `destroyForcibly()` and throw `AssertionError("keytool timed out: " + args)`;
  - assert exit code 0, with keytool's output in the message.

  Everything goes into a `Files.createTempDirectory("foy-tls")`, with PKCS12 stores and password `changeit`.

```java
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

    // keytool(List<String>) as described in Step 1
}
```

- [ ] **Step 2: Write the failing tests** in `CertificateIdentityTest` (package `io.vidocq.foy.internal.security`).

```java
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
                .certificateUser("cn=alice example,ou=Foy Tests,o=Vidocq,c=FR", "admin").build();
        assertTrue(store.validate(new X509Certificate[] {CERTS.certificate("alice")}).isPresent());
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
        assertThrows(IllegalArgumentException.class, () -> InMemoryIdentityStore.builder().certificateUser("not a dn"));
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
            public Optional<Identity> validate(String u, char[] p) { return Optional.empty(); }
            public Optional<Identity> validate(X509Certificate[] c) { throw new IllegalStateException("down"); }
        };
        IdentityStore nulls = new IdentityStore() {
            public Optional<Identity> validate(String u, char[] p) { return Optional.empty(); }
            public Optional<Identity> validate(X509Certificate[] c) { return null; }
        };
        var chain = new X509Certificate[] {CERTS.certificate("alice")};
        assertTrue(CredentialCheck.validate(throwing, chain).isEmpty());
        assertTrue(CredentialCheck.validate(nulls, chain).isEmpty());
    }
}
```

  `"not a dn"` must make `new X500Principal(...)` throw `IllegalArgumentException`. If the JDK accepts it, use `"CN"` instead, and note it in the report.

- [ ] **Step 3: Run and check RED.** Run `./mvnw -ntp -pl foy-api,foy-core -am test -Dtest='CertificateIdentityTest' -Dsurefire.failIfNoSpecifiedTests=false`, plus the repository flags. Expected: a compile failure (`validate(X509Certificate[])`, `certificateUser` and `CredentialCheck.validate(…, X509Certificate[])` do not exist yet).

- [ ] **Step 4: Implement.**

  `IdentityStore` (keep `@FunctionalInterface`, add the import `java.security.cert.X509Certificate`):

```java
    /**
     * The identity of a TLS client certificate chain, leaf first, as the handshake delivered it (the
     * TLS layer has already checked it against the server's trust material). CLIENT-CERT calls it.
     * The default refuses every chain: a store that only knows passwords never authenticates a
     * certificate.
     *
     * @param chain the client chain, leaf first; never empty when called by Foy
     * @return the identity of the leaf certificate's subject, or empty to refuse it
     */
    default Optional<Identity> validate(X509Certificate[] chain) { return Optional.empty(); }
```

  `InMemoryIdentityStore`:
  - add `private final Map<X500Principal, Set<String>> certificateUsers;`, filled by a new constructor `(Map<String, User> users, Map<X500Principal, Set<String>> certificateUsers)` with `Map.copyOf` on both;
  - add a matching `LinkedHashMap` in the `Builder`.

```java
    @Override
    public Optional<Identity> validate(X509Certificate[] chain) {
        if (chain == null || chain.length == 0 || chain[0] == null) return Optional.empty();
        X500Principal subject = chain[0].getSubjectX500Principal();
        Set<String> roles = certificateUsers.get(subject);
        return roles == null ? Optional.empty()
                : Optional.of(new Identity(new Identity.NamedPrincipal(subject.getName(X500Principal.RFC1779)), roles));
    }

        /**
         * Declares a certificate user: a TLS client whose leaf certificate has this subject is
         * authenticated by CLIENT-CERT with these roles. The DN is compared in its canonical form,
         * so spacing and attribute-value case do not matter.
         *
         * @throws IllegalArgumentException when the DN is malformed or declared twice
         */
        public Builder certificateUser(String subjectDn, String... roles) {
            Objects.requireNonNull(subjectDn, "subjectDn");
            X500Principal subject = new X500Principal(subjectDn);
            if (certificateUsers.putIfAbsent(subject, Set.copyOf(List.of(roles))) != null) {
                throw new IllegalArgumentException("certificate user '" + subjectDn + "' declared twice");
            }
            return this;
        }
```

  `CredentialCheck`:

```java
    /** {@link IdentityStore#validate(X509Certificate[])}, with a failing or {@code null}-returning store read as a refusal. */
    public static Optional<Identity> validate(IdentityStore store, X509Certificate[] chain) {
        try {
            if (chain == null || chain.length == 0) return Optional.empty();
            Optional<Identity> result = store.validate(chain.clone());
            return result == null ? Optional.empty() : result;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "identity store {0} failed with {1}; the certificate is refused",
                    store.getClass().getName(), e.getClass().getName());
            return Optional.empty();
        }
    }
```

  `FoyChappeBoot.Builder`: add the method below. Update the Javadoc of `identityStore(...)` to say it is also behind CLIENT-CERT and exclusive with `certificateUser`. Also update the message of the existing `IllegalStateException` in `securitySettings()` to `"identityStore(...) and user(...)/certificateUser(...) are exclusive"`.

```java
        /**
         * Declares a certificate user of Foy's in-memory identity store: CLIENT-CERT authenticates a
         * TLS client whose leaf certificate has this subject DN, with these roles. Exclusive with
         * {@link #identityStore}.
         *
         * @throws IllegalArgumentException when the DN is malformed or declared twice
         */
        public Builder certificateUser(String subjectDn, String... roles) {
            if (users == null) users = InMemoryIdentityStore.builder();
            users.certificateUser(subjectDn, roles);
            return this;
        }
```

- [ ] **Step 5: foy-chappe test.** Generate a public certificate once and commit it as `foy-chappe/src/test/resources/tls/alice.pem`. The store never checks validity, so the expiry does not matter.

```bash
keytool -genkeypair -alias alice -keyalg RSA -keysize 2048 -validity 36500 \
  -dname "CN=Alice Example, OU=Foy Tests, O=Vidocq, C=FR" -keystore /tmp/alice.p12 \
  -storetype PKCS12 -storepass changeit -keypass changeit
keytool -exportcert -rfc -alias alice -keystore /tmp/alice.p12 -storepass changeit -file foy-chappe/src/test/resources/tls/alice.pem
```

  Use the scratchpad instead of `/tmp`, and delete the p12. Add to `FoyChappeBootTest`:

```java
    @Test
    void certificateUserIsMappedByTheInMemoryStore() throws Exception {
        X509Certificate alice;
        try (var in = FoyChappeBootTest.class.getResourceAsStream("/tls/alice.pem")) {
            alice = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        var store = FoyChappeBoot.builder().certificateUser("CN=Alice Example, OU=Foy Tests, O=Vidocq, C=FR", "admin")
                .securitySettings().identityStore();
        assertEquals(Set.of("admin"), store.validate(new X509Certificate[] {alice}).orElseThrow().roles());
    }

    @Test
    void certificateUserIsExclusiveWithIdentityStore() {
        var b = FoyChappeBoot.builder().identityStore(IdentityStore.none())
                .certificateUser("CN=Alice Example, OU=Foy Tests, O=Vidocq, C=FR", "admin");
        assertThrows(IllegalStateException.class, b::securitySettings);
    }
```

  `securitySettings()` is package-private, and `FoyChappeBootTest` is in package `io.vidocq.foy.chappe`. If it is not reachable from the test, go through `build()` and the mounted deployment's settings, and report how.

- [ ] **Step 6: Run and check GREEN.** Run `-pl foy-api,foy-core,foy-chappe -am test -Dtest='CertificateIdentityTest,FoyChappeBootTest,InMemoryIdentityStoreTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: all pass. Report the count, and drop `InMemoryIdentityStoreTest` from the list if no such class exists.

- [ ] **Step 7: Commit.**

```bash
git add foy-api/src/main/java/io/vidocq/foy/spi/security/IdentityStore.java \
  foy-core/src/main/java/io/vidocq/foy/internal/security/InMemoryIdentityStore.java \
  foy-core/src/main/java/io/vidocq/foy/internal/security/CredentialCheck.java \
  foy-chappe/src/main/java/io/vidocq/foy/chappe/FoyChappeBoot.java \
  foy-core/src/test/java/io/vidocq/foy/internal/TlsTestCertificates.java \
  foy-core/src/test/java/io/vidocq/foy/internal/security/CertificateIdentityTest.java \
  foy-chappe/src/test/resources/tls/alice.pem \
  foy-chappe/src/test/java/io/vidocq/foy/chappe/FoyChappeBootTest.java
git commit -S -s -m "feat(security): map TLS client certificates to identities"
```

---

### Task 2: Servlet §3.10 TLS request attributes

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/TlsAttributes.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java` (constructor, new accessor `peerCertificates()`)
- Modify: `foy-core/src/test/java/io/vidocq/foy/internal/TestServerLauncher.java` and `TestServerLauncherAccess` (wherever it lives): add a TLS start
- Create: `foy-core/src/test/java/io/vidocq/foy/internal/bridge/TlsAttributesTest.java` (unit)
- Create: `foy-core/src/test/java/io/vidocq/foy/internal/TlsAttributesEndToEndTest.java`

**Interfaces:**
- Consumes: `TlsTestCertificates` (Task 1). From chappe: `io.vidocq.chappe.api.TlsInfo(String protocol, String cipherSuite, byte[] sessionId, List<X509Certificate> peerCertificates)` (`sessionId()` returns a copy), `Request.tls()` → `Optional<TlsInfo>`, `io.vidocq.chappe.api.ClientAuth`, and `Server.Builder.tls(SSLContext, ClientAuth)`.
- Produces:
  - `TlsAttributes` (public, final, internal package), with constants `X509_CERTIFICATE`, `CIPHER_SUITE`, `KEY_SIZE`, `SSL_SESSION_ID` and `SECURE_PROTOCOL`, holding the exact attribute names from Global Constraints;
  - `static Integer keySize(String cipherSuite)`, `null` when unknown;
  - `static void seed(TlsInfo tls, Map<String, Object> attributes)`;
  - `HttpServletRequestImpl#peerCertificates()` → `X509Certificate[]`: a fresh array, leaf first, empty on a plain connection or without a client certificate;
  - `TestServerLauncher.startTls(Handler, SSLContext, ClientAuth)`, returning the same `Result`. Mirror it in `TestServerLauncherAccess` (`startTls`) so `internal.security` tests can call it.

- [ ] **Step 1: Write the failing unit test** `TlsAttributesTest`:

```java
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
```

  Check `TlsInfo`'s constructor in chappe (`chappe-api/.../TlsInfo.java`). If it rejects an empty `sessionId` or a `null` element, adapt the test inputs and report it.

- [ ] **Step 2: Write the failing end-to-end test** `TlsAttributesEndToEndTest`. It starts a TLS server through `TestServerLauncher.startTls(bridge, TlsTestCertificates.get().serverContext(), ClientAuth.WANT)`, behind a `ChappeServletBridge` built as in `ChunkedBodyLimitEndToEndTest` (with `TestSecurity.permitAll(ctx)`). The servlet at `/a` writes, one line each:
  - every attribute name starting with `jakarta.servlet.request.`, sorted;
  - the leaf subject when the X509 attribute is present;
  - `isSecure()` and `getScheme()`.

  A `ServletRequestAttributeListener` registered on the context counts `attributeAdded` events. The client is `HttpClient.newBuilder().sslContext(...).version(v)`.

```java
    @ParameterizedTest
    @EnumSource(value = HttpClient.Version.class)
    void aClientCertificateFillsTheAttributes(HttpClient.Version version) throws Exception {
        String body = get(version, CERTS.clientContext("alice"));
        assertTrue(body.contains("jakarta.servlet.request.X509Certificate"), body);
        assertTrue(body.contains("jakarta.servlet.request.cipher_suite"), body);
        assertTrue(body.contains("jakarta.servlet.request.key_size"), body);
        assertTrue(body.contains("jakarta.servlet.request.ssl_session_id"), body);
        assertTrue(body.contains("jakarta.servlet.request.secure_protocol"), body);
        assertTrue(body.contains("leaf=CN=Alice Example"), body);
        assertTrue(body.contains("secure=true scheme=https"), body);
        assertEquals(0, ADDED.get(), "container attributes fire no listener");
    }

    @ParameterizedTest
    @EnumSource(value = HttpClient.Version.class)
    void noClientCertificateLeavesOnlyTheConnectionAttributes(HttpClient.Version version) throws Exception {
        String body = get(version, CERTS.clientContextWithoutCertificate());
        assertFalse(body.contains("X509Certificate"), body);
        assertTrue(body.contains("jakarta.servlet.request.cipher_suite"), body);
    }

    @Test
    void aPlainRequestHasNoTlsAttribute() throws Exception {
        // a second, plain server with TestServerLauncher.start(bridge): no jakarta.servlet.request.* attribute, secure=false
    }

    @Test
    void theCertificateArrayIsPerRequest() throws Exception {
        // servlet /mutate: nulls element 0 of the X509 array, then removes the attribute; a second request on
        // the same client still sees leaf=CN=Alice Example
    }
```

  Write out the two commented tests completely, following the same pattern. With `HttpClient.Version.HTTP_2`, assert inside the servlet output that `getProtocol()` is `HTTP/2.0`: chappe's default ALPN offers `h2`. This proves the test really ran over h2.

- [ ] **Step 3: Run and check RED.** Run `-pl foy-core -am test -Dtest='TlsAttributesTest,TlsAttributesEndToEndTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: a compile failure (`TlsAttributes`, `startTls`).

- [ ] **Step 4: Implement `TlsAttributes`.**

```java
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
```

  `HttpServletRequestImpl`:
  - At the end of the 6-argument constructor, add `if (chappe != null) chappe.tls().ifPresent(t -> TlsAttributes.seed(t, attributes));`.
  - Add the accessor below.

```java
    /**
     * The client certificate chain of the connection, leaf first, as the TLS handshake delivered it:
     * a fresh array, empty on a plain connection or when the client sent no certificate. It is read
     * from the connection, never from the request attribute an application can change.
     */
    public X509Certificate[] peerCertificates() {
        return chappe == null ? new X509Certificate[0] : chappe.tls()
                .map(t -> t.peerCertificates().toArray(X509Certificate[]::new)).orElseGet(() -> new X509Certificate[0]);
    }
```

  `TestServerLauncher.startTls` is the same loop as `start`, with `.tls(sslContext, clientAuth)` on the builder.

- [ ] **Step 5: Run and check GREEN.** Run the two classes, then all of `foy-core` (`-pl foy-core -am test`): no regression. Report the counts.

- [ ] **Step 6: Commit.** Use explicit paths and the message `feat(core): set the Servlet TLS request attributes from the connection`.

---

### Task 3: The CLIENT-CERT authenticator

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/security/ClientCertAuthenticator.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/security/WebSecurity.java` (`authenticatorFor`)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/security/DenyingAuthenticator.java` (Javadoc: CLIENT-CERT is no longer one of its uses; DIGEST and custom methods remain)
- Create: `foy-core/src/test/java/io/vidocq/foy/internal/security/ClientCertEndToEndTest.java`
- Modify: `foy-core/src/test/java/io/vidocq/foy/internal/security/SecureApp.java` (TLS variant)

**Interfaces:**
- Consumes:
  - from Task 1: `CredentialCheck.validate(IdentityStore, X509Certificate[])`, `InMemoryIdentityStore.Builder#certificateUser` and `TlsTestCertificates`;
  - from Task 2: `HttpServletRequestImpl#peerCertificates()` and `TestServerLauncherAccess.startTls`.
- Produces: `ClientCertAuthenticator(IdentityStore store)`, used by `WebSecurity.authenticatorFor` for `auth-method` `CLIENT-CERT`.

- [ ] **Step 1: Give `SecureApp` a TLS variant.**
  - Constructor `SecureApp(WebAppModel model, SecuritySettings settings, SSLContext serverContext, SSLContext clientContext, HttpClient.Version version)`.
  - It starts with `TestServerLauncherAccess.startTls(deployment.handler(), serverContext, ClientAuth.WANT)` and builds the client with `.sslContext(clientContext).version(version)`.
  - `request(path)` uses `https://127.0.0.1:` when TLS is on.
  - Keep the existing constructors unchanged.

- [ ] **Step 2: Write the failing tests** in `ClientCertEndToEndTest`. Use a `Who` servlet that writes `user=<getRemoteUser()> type=<getAuthType()> admin=<isUserInRole("ADM")>`, declared with a `security-role-ref` `ADM` → `admin`. The constraint is on `/secure/*`, role `admin`, `transport-guarantee` `CONFIDENTIAL`, with `LoginConfigDef("CLIENT-CERT", null, null, null)`. The store is:

```java
    static final IdentityStore STORE = InMemoryIdentityStore.builder()
            .certificateUser(TlsTestCertificates.ALICE_DN, "admin")
            .user("carol", "c-secret".toCharArray(), "admin")
            .build();
```

  Tests, parameterized on `HTTP_1_1` and `HTTP_2` where marked (*):
  - (*) `aMappedCertificateIsAuthenticated`: the alice client gets 200, with body `user=CN=Alice Example, OU=Foy Tests, O=Vidocq, C=FR type=CLIENT_CERT admin=true`.
  - (*) `noCertificateIsForbidden`: the client without a certificate gets 403, and the response has no `WWW-Authenticate` header.
  - (*) `aTrustedButUnmappedCertificateIsForbidden`: the bob client gets 403.
  - `aThrowingStoreIsForbidden`: a store whose `validate(X509Certificate[])` throws gives 403, not 500.
  - `plainHttpIsRefusedByTheTransportGuarantee`: a plain `SecureApp`, same model, gets 403 (no `confidentialPort`).
  - `loginWithAPasswordIsAllowed`: a servlet at `/login` (unconstrained) calls `req.login("carol", "c-secret")` and writes `getRemoteUser()` and `getAuthType()`. Over TLS without a client certificate it gives 200 `carol CLIENT_CERT`.
  - `authenticationReadsTheConnectionNotTheAttribute`: a filter mapped to `/*` replaces the X509 attribute with an empty array before the chain continues. The constrained request still authenticates as alice.
    - Check how the existing tests declare filters on a `WebAppModel` (`FilterDecl`) and follow that.
    - Admission runs before the filter chain, so this pins that a later mutation is harmless. Also assert that `req.getAttribute(X509)` inside the servlet is the replaced value: the application owns the attribute after seeding.
  - `httpServletRequestAuthenticateUsesTheCertificate`: on an unconstrained `/auth` over TLS with alice, `req.authenticate(resp)` returns `true` and `getRemoteUser()` is alice's RFC 1779 name.

- [ ] **Step 3: Run and check RED.** Run `-pl foy-core -am test -Dtest='ClientCertEndToEndTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: the `admin=true` and authentication assertions fail with 403, because `DenyingAuthenticator` is still in use.

- [ ] **Step 4: Implement.**

```java
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
```

  In `WebSecurity.authenticatorFor`, add `case "CLIENT-CERT" -> new ClientCertAuthenticator(store);` before `default`.

- [ ] **Step 5: Run and check GREEN.** Run the class, then the whole security package (`-Dtest='io.vidocq.foy.internal.security.*Test'`), then all of `foy-core`. Report the counts.

- [ ] **Step 6: Commit.** Message: `feat(security): authenticate CLIENT-CERT from the TLS client certificate`.

---

### Task 4: Pin BUG-20261011-11 as fixed by chappe#28

**Files:**
- Modify: `foy-core/src/test/java/io/vidocq/foy/internal/ChunkedBodyLimitEndToEndTest.java`
- Modify: `BUG.md` (BUG-20261011-11)

- [ ] **Step 1: Write the test** `anEndlessChunkedBodyIsCutOffAfterTheAnswer`.
  - Start the app with a cap of 1024, then open a raw `Socket` to `127.0.0.1:port` with `setSoTimeout(15_000)`.
  - Send `POST /body HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n`.
  - A virtual thread keeps writing `"1000\r\n" + 4096 bytes + "\r\n"` chunks until a write throws. Record the total bytes written in an `AtomicLong`.
  - The main thread reads the response until EOF.
  - Assert:
    - the status line is `HTTP/1.1 200`, and the body contains `RequestBodyTooLargeException`;
    - EOF (or a reset `SocketException`) arrives within 15 s;
    - the writer stopped, and the bytes it wrote are under `10 * 1024 * 1024`. chappe's default `maxDrainSize` is 1 MiB; the 10 MiB bound only rules out an unbounded drain, so it is not exact. Add a comment saying so.
  - Close the socket in `finally`.

  This test passes on chappe main (`261e6d2`). The RED is historic: before chappe#28 this test never ended. Say this in the report; no RED run is expected.

- [ ] **Step 2: Run** `-Dtest='ChunkedBodyLimitEndToEndTest'`, 3 times in a row. Expected: 4/4 each time, and the new test under 5 s.

- [ ] **Step 3: Update `BUG.md`.** In BUG-20261011-11, set the status to `FIXED by chappe#28 (CHAPPE-020 chunked cap, CHAPPE-021 maxDrainSize); pinned by ChunkedBodyLimitEndToEndTest.anEndlessChunkedBodyIsCutOffAfterTheAnswer`. Keep the rest of the entry. In `docs/en/modules/ROOT/pages/internals.adoc`, the multipart line says the remainder is "left to Chappe's drain (BUG-20261011-11)". Change it to say that chappe bounds that drain (`maxDrainSize`, 1 MiB by default).

- [ ] **Step 4: Commit.** Message: `test(core): pin that an endless chunked body is cut off after the answer`.

---

### Task 5: The `https` container in foy-tck, and the two official CLIENT-CERT tests

**Files:**
- Modify: `foy-tck/src/main/java/io/vidocq/foy/tck/ServletTestHost.java`
- Modify: `foy-tck/src/main/java/io/vidocq/foy/tck/ServletTestHarness.java` (`baseUrl()` scheme)
- Modify: `foy-tck/src/test/java/io/vidocq/foy/tck/arquillian/VidocqContainerConfiguration.java`
- Modify: `foy-tck/src/test/java/io/vidocq/foy/tck/arquillian/VidocqDeployableContainer.java`
- Modify: `foy-tck/src/test/java/io/vidocq/foy/tck/arquillian/TckIdentities.java`
- Modify: `foy-tck/src/test/resources/arquillian.xml`
- Modify: `foy-tck/pom.xml` (profile `tck-official`)
- Modify: `foy-tck/src/test/java/io/vidocq/foy/tck/ServletTestHostTest.java`, `foy-tck/src/test/java/io/vidocq/foy/tck/arquillian/TckIdentitiesTest.java`
- Modify: `foy-tck/tck-baseline.txt`, `TCK.md`, `BUG.md` (BUG-20261011-03)

**Interfaces:**
- Consumes: `InMemoryIdentityStore.Builder#certificateUser` (Task 1), CLIENT-CERT (Task 3), and chappe's `Server.Builder.tls(SSLContext, ClientAuth)`.
- Produces:
  - `ServletTestHost(SSLContext sslContext, ClientAuth clientAuth)` (TLS) next to `ServletTestHost()` (plain), with `String scheme()` returning `"https"` or `"http"`;
  - `VidocqContainerConfiguration#isTls()/setTls(boolean)`.

- [ ] **Step 1: Write the failing in-reactor tests.**
  - `ServletTestHostTest.aTlsHostServesHttps`: a `ServletTestHost(ctx, ClientAuth.WANT)` built with a server context.
    - foy-tck has no `TlsTestCertificates`. Generate a keystore in the test with the same keytool discipline (a small private helper, or reuse `foy-tck/target/tls/server.p12` when the build created it).
    - Mount a handler that answers 200 `ok`.
    - `HttpClient` with an `SSLContext` trusting that keystore gets `ok` over `https://127.0.0.1:<port>/`.
    - `scheme()` is `"https"`.
  - `TckIdentitiesTest.theCtsCertificateIsAnAdministrator`: `TckIdentities.store()` maps a chain whose leaf has subject `CN=CTS, OU=Java Software, O=Sun Microsystems Inc., L=Burlington, ST=MA, C=US` to an identity with role `Administrator` and that exact name.
    - Load the leaf from `target/tck-certificates/certificates/cts_cert` (unpacked in Step 3).
    - Mark the test `@EnabledIf` on that file existing, so it does not fail outside `-Ptck,tck-official`. If `TckIdentitiesTest` runs only under `-Ptck`, keep it consistent with the existing tests there.

- [ ] **Step 2: Run and check RED.** Run `./mvnw -ntp -Ptck -pl foy-tck test -Dtest='ServletTestHostTest,TckIdentitiesTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: a compile failure on the new constructor.

- [ ] **Step 3: Generate the keystores at build time** (`foy-tck/pom.xml`, profile `tck-official`).
  - `maven-dependency-plugin` 3.8.1, execution `unpack-tck-certificates`, phase `generate-test-resources`, goal `unpack`:
    - artifact `jakarta.tck:servlet-tck-runtime:6.1.0`;
    - `<includes>certificates/**</includes>`;
    - output `${project.build.directory}/tck-certificates`.
  - `maven-antrun-plugin` 3.1.0, execution `generate-tls-stores`, phase `process-test-resources`, goal `run`, as below. The `<delete>` makes it idempotent across builds.

```xml
<target>
    <property name="tls.dir" value="${project.build.directory}/tls"/>
    <property name="keytool" value="${java.home}/bin/keytool"/>
    <delete dir="${tls.dir}"/>
    <mkdir dir="${tls.dir}"/>
    <exec executable="${keytool}" failonerror="true">
        <arg line="-genkeypair -alias foy-tck -keyalg RSA -keysize 2048 -validity 3650"/>
        <arg value="-dname"/><arg value="CN=localhost, O=Foy TCK"/>
        <arg value="-ext"/><arg value="SAN=ip:127.0.0.1,dns:localhost"/>
        <arg line="-keystore ${tls.dir}/server.p12 -storetype PKCS12 -storepass changeit -keypass changeit"/>
    </exec>
    <exec executable="${keytool}" failonerror="true">
        <arg line="-exportcert -rfc -alias foy-tck -keystore ${tls.dir}/server.p12 -storepass changeit -file ${tls.dir}/server.pem"/>
    </exec>
    <exec executable="${keytool}" failonerror="true">
        <arg line="-importcert -noprompt -alias foy-tck -file ${tls.dir}/server.pem -keystore ${tls.dir}/client-trust.p12 -storetype PKCS12 -storepass changeit"/>
    </exec>
    <exec executable="${keytool}" failonerror="true">
        <arg line="-importcert -noprompt -alias cts -file ${project.build.directory}/tck-certificates/certificates/cts_cert -keystore ${tls.dir}/server-trust.p12 -storetype PKCS12 -storepass changeit"/>
    </exec>
</target>
```

  `${java.home}` in antrun resolves to the JDK running Maven, which is Java 25 here. Add these properties to the surefire `systemPropertyVariables` of the same profile:

```xml
<!-- CLIENT-CERT (BUG-20261011-03): the TCK client presents the CTS certificate and trusts the
     build-generated server certificate; the https container serves with server.p12 and trusts cts_cert. -->
<javax.net.ssl.keyStore>${project.build.directory}/tck-certificates/certificates/clientcert.jks</javax.net.ssl.keyStore>
<javax.net.ssl.keyStorePassword>changeit</javax.net.ssl.keyStorePassword>
<javax.net.ssl.keyStoreType>JKS</javax.net.ssl.keyStoreType>
<javax.net.ssl.trustStore>${project.build.directory}/tls/client-trust.p12</javax.net.ssl.trustStore>
<javax.net.ssl.trustStorePassword>changeit</javax.net.ssl.trustStorePassword>
<javax.net.ssl.trustStoreType>PKCS12</javax.net.ssl.trustStoreType>
<foy.tck.tls.dir>${project.build.directory}/tls</foy.tck.tls.dir>
```

  If `ServletTestHostTest` runs under `-Ptck` without `tck-official`, it must not depend on `target/tls`. Give it its own keytool helper, as in Step 1.

- [ ] **Step 4: Implement the TLS host and the container.**
  - **`ServletTestHost`.** Extract the start loop into a private constructor `(SSLContext sslContext, ClientAuth clientAuth)`. With a non-null context it calls `.tls(sslContext, clientAuth)`; `ServletTestHost()` delegates with `(null, ClientAuth.NONE)`. Add `public String scheme()`.
  - **`ServletTestHarness.baseUrl()`.** Use `host.scheme() + "://127.0.0.1:" + port + …`. `VidocqDeployableContainer` strips `"http://" + host + ":" + port` from it. Change that to `harness.baseUrl().replaceFirst("^https?://[^/]+", "")`, so both schemes work.
  - **`VidocqContainerConfiguration`.** Add `private boolean tls;` with `isTls()`/`setTls(boolean)`. `validate()` throws `ConfigurationException("tls=true needs -Dfoy.tck.tls.dir (run with -Ptck,tck-official)")` when `tls` is set and the system property is missing or names no directory.
  - **`VidocqDeployableContainer.host()`.** When `config.isTls()`, build the server `SSLContext`:
    - key material from `<foy.tck.tls.dir>/server.p12`, trust from `<foy.tck.tls.dir>/server-trust.p12`, both PKCS12 with `changeit`;
    - use `new ServletTestHost(ctx, ClientAuth.WANT)`. WANT is enough: the application's CONFIDENTIAL constraint and CLIENT-CERT do the rest.
  - **`TckIdentities.store()`.** Add `.certificateUser("CN=CTS, OU=Java Software, O=Sun Microsystems Inc., L=Burlington, ST=MA, C=US", "Administrator")`.
  - **`arquillian.xml`:**

```xml
<group qualifier="foy" default="true">
    <container qualifier="vidocq-servlet-chappe" default="true">
        <configuration>
            <property name="host">127.0.0.1</property>
        </configuration>
    </container>
    <!-- @TargetsContainer("https"): the CLIENT-CERT tests (BUG-20261011-03). -->
    <container qualifier="https">
        <configuration>
            <property name="host">127.0.0.1</property>
            <property name="tls">true</property>
        </configuration>
    </container>
</group>
```

  If Arquillian validates every container of a group at start-up, the in-reactor `-Ptck` run (without `tck-official`) fails in `validate()`, because there is no `foy.tck.tls.dir`. In that case, make `validate()` lenient and fail later, in `deploy()`, with the same message. Say which one you did in the report.

- [ ] **Step 5: Run and check GREEN, in-reactor.** Run `./mvnw -ntp -Ptck -pl foy-tck test`. Expected: all in-reactor tests pass (30 before this task, plus the new ones). Report the count.

- [ ] **Step 6: Run the official tests.** Run `./run-official-tck-servlet6.1.sh --no-install -Dtest='ClientCertTests,ClientCertAnnoTests'`. Expected: 2/2 pass.
  - If they fail, read the servlet output in the surefire log: the TCK servlet prints `FAILED` lines naming what is missing.
  - Fix inside the scope of Tasks 1–5. A new defect outside it goes into `BUG.md` and is reported.
  - Then run the full official TCK (`--all`, or the script's equivalent) and compare it with `tck-baseline.txt`. Expected: 1702/1714 (1700 + 2), with no other class changing.

- [ ] **Step 7: Update the records.**
  - **`tck-baseline.txt`:** set the two clientcert lines to `1 0 0`, in the file's format (check its header for the column meaning).
  - **`TCK.md`:** remove the two rows that list the CLIENT-CERT tests as an accepted gap (lines ~53 and ~98). Update the security totals (`clientcert*` 2/2 already shows as passing in the per-class table; check every total that counts them as failing). Add an entry for this exit with the new total.
  - **`BUG.md` BUG-20261011-03:** set the status to `FIXED`, naming this branch, chappe#29, and the two TCK tests that now pass.

- [ ] **Step 8: Commit.** Message: `test(tck): serve the CLIENT-CERT tests from an https container`.

---

### Task 6: Documentation

**Files:**
- Modify: `docs/en/modules/ROOT/pages/usage.adoc` (section "Application security": a new `=== Client certificates (CLIENT-CERT)` subsection)
- Modify: `docs/en/modules/ROOT/pages/reference.adoc` (`FoyChappeBoot.Builder` table: `certificateUser`; `IdentityStore`: `validate(X509Certificate[])`; the §3.10 attributes)
- Modify: `docs/en/modules/ROOT/pages/whats-new.adoc`
- Modify: `docs/en/modules/ROOT/pages/tck.adoc` (CLIENT-CERT passes; the https container; the build-time tooling `maven-antrun-plugin` and `maven-dependency-plugin` used for the keystores)
- Modify: `docs/en/modules/ROOT/pages/internals.adoc` (the security mechanisms list, if it names CLIENT-CERT as unsupported)

- [ ] **Step 1: Write the usage subsection.** It covers:
  1. **The TLS listener.** The chappe server must ask for a certificate: `Server.builder().tls(sslContext, ClientAuth.WANT)`, or `NEED`. Explain why per listener: there is no renegotiation under TLS 1.3 or HTTP/2, so a certificate cannot be requested only for a protected URL.
  2. **Trust.** The trust material is the `SSLContext`'s. Pass a TrustManager built from your client CA: `init(km, null, …)` trusts the JDK's public CAs (chappe's warning).
  3. **Identities.** Map them with `FoyChappeBoot.Builder.certificateUser(dn, roles…)`, or a custom `IdentityStore` overriding `validate(X509Certificate[])`. Give the principal name rule (RFC 1779 form, with an example), and say that DN matching ignores spacing and case.
  4. **Behaviour.** No certificate, or an unmapped one, gives 403. `getAuthType()` is `CLIENT_CERT`. `login(user, password)` still works. CONFIDENTIAL is satisfied by the TLS listener.
  5. **The five attributes**, with their types, and the key-size derivation rule (absent for an unknown suite).

  A complete code example:

```java
SSLContext tls = SSLContext.getInstance("TLS");
tls.init(serverKeyManagers, clientCaTrustManagers, null);
var mounted = FoyChappeBoot.builder()
        .webXml(webXml)
        .certificateUser("CN=Alice Example, OU=Ops, O=Example, C=FR", "admin")
        .build().orElseThrow();
Server server = Server.builder().port(8443).tls(tls, ClientAuth.WANT)
        .handler(Router.builder().mount(mounted.mountPrefix(), mounted.handler(), false).build())
        .build();
```

  Check the `Router`/`mount` call against the current getting-started page (from #32, the 3-argument mount), and copy its exact shape.

- [ ] **Step 2: Update reference, whats-new, tck and internals** as listed under Files. English only.

- [ ] **Step 3: Check.** `grep -rn "JPMS" docs/en` must give nothing new, and every Java snippet must use names that exist. Do not build the vidocq-docs site; say so in the report.

- [ ] **Step 4: Commit.** Message: `docs: document CLIENT-CERT authentication and the TLS request attributes`.

---

## Self-review notes (plan author)

- **Spec coverage:**
  - the default method: Task 1;
  - in-memory DN mapping and RFC 1779 name: Task 1;
  - `FoyChappeBoot.certificateUser`: Task 1;
  - the §3.10 attributes, including `secure_protocol`: Task 2;
  - `CLIENT_CERT` authType, 403 and `login` allowed: Task 3;
  - the foy-tck https container, the build-time keystore with SAN, `clientcert.jks` and the CTS DN mapped to Administrator: Task 5;
  - BUG-03 closed: Task 5. BUG-11 closed: Task 4;
  - docs: Task 6.
- **Consistency:**
  - `peerCertificates()` is defined in Task 2 and used in Task 3;
  - `CredentialCheck.validate(IdentityStore, X509Certificate[])` is defined in Task 1 and used in Task 3;
  - `TestServerLauncher.startTls` is defined in Task 2 and used in Tasks 2 and 3;
  - `TlsTestCertificates` is defined in Task 1 and used in Tasks 1–3.
- **Known risks:**
  - Arquillian may validate every container of the group (Task 5, Step 4 fallback).
  - The official tests' exact HTTPS URL and host come from the TCK. The SAN covers both `127.0.0.1` and `localhost`.
  - The Foy PR CI resolves chappe 0.4.0-SNAPSHOT from Central snapshots. chappe's main CI must have deployed `261e6d2` (or later) before the Foy PR CI runs.
