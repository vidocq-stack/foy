# Phase 6 — Security Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Foy enforces the Servlet 6.1 security model end to end. That covers web.xml and annotation constraints compiled per url-pattern, `deny-uncovered-http-methods`, BASIC and FORM login, `security-role-ref`, `login()`/`logout()`/`authenticate()`, `setServletSecurity` and the transport guarantee. Users come from an exported `IdentityStore` SPI, with a built-in in-memory store that the embedder configures through `FoyChappeBoot`. The TCK goes from 1659 to **1692 passing / 1714 run** (+33).

**Architecture:** the deployer builds a `ConstraintTable` once. It holds the descriptor constraints first, then each servlet's `ServletSecurityElement` from its annotation or `setServletSecurity`, applied to every pattern the descriptor does not constrain exactly. The bridge asks a per-context `WebSecurity` to `admit` every REQUEST dispatch. The check runs on the canonical path, before any target, including the container default servlet and unmapped paths; forward, include, error and async dispatches are never re-checked. `WebSecurity` owns one `Authenticator` (BASIC, FORM, or a fail-closed one for unsupported methods) chosen from `login-config`. FORM keeps the saved GET target and the identity in container-private session *notes*. Those notes are not attributes and survive `changeSessionId`, which runs on every successful login. `isUserInRole` resolves the `security-role-ref`s of the servlet currently executing, which the bridge tracks around each invocation.

**Tech Stack:** Java 25, Jakarta Servlet 6.1, foy-api / foy-core / foy-chappe / foy-tck, chappe 0.4.0-SNAPSHOT (unchanged in this phase), JUnit 5 end-to-end tests over real chappe (`TestServerLauncherAccess`, `java.net.http.HttpClient` with a `CookieManager` and redirects off), official Servlet TCK via Arquillian.

**Spec:** `docs/superpowers/plans/2026-10-07-servlet-completion-roadmap.md`, sections "Phase 6 — Security" and "Phase 5 exit / follow-ups", amended by the maintainer decisions below. Jakarta Servlet 6.1 chapter 13 (13.3 programmatic security, 13.4 `@ServletSecurity`/`setServletSecurity`, 13.5 roles, 13.6.1 BASIC, 13.6.3 FORM, 13.7 server tracking of authentication, 13.8 constraints and 13.8.4 uncovered methods, 13.10 login/logout). Check each section number against the 6.1 PDF before quoting it in Javadoc. Javadoc of `HttpServletRequest#login/logout/authenticate/isUserInRole`, `ServletRegistration.Dynamic#setServletSecurity`, `ServletContext#declareRoles`. Evidence: the Phase 6 research notes of 2026-10-10 (summarised below).

**Realigned on main 776b708** (the plan was written at a75e6ad; #23 `221ee7c` and #25 `8daaab7..776b708` landed since):
- `ChappeServletBridge.handle(Request, List, HttpServletResponseImpl)` now wraps the main branch in an outer `try`/`catch (Throwable)` with a `requestDestroyed` flag, an inner `try` around the check and `invoke`, and an `endRequest` helper; the unmapped-path branch ends through `endRequest` too. Task 6.5 step 4 is rewritten against that shape (the denial path sets `requestDestroyed` and no longer fires `requestDestroyed` inside the inner `try`, where a listener failure would be caught and fire it twice). Task 6.3's `invoke` anchor moved to ~935-960.
- Task 6.1 now also edits the bridge: its `new SecurityConstraintEnforcer(servletContext.securityProvider())` call site would not compile once `securityProvider()` is removed (missing from the original plan).
- `FoyChappeBoot.build()` anchors are unchanged (`build()` ~204, `DeployOptions` chain ~285); #23 only adds the `CdiContextListeners` loop after the deployment, which this phase does not touch. `HttpSessionImpl` was not changed by #23: the CDI session context goes through session attributes (`foy-cdi-vauban` `HttpSessionStore`), so the session notes and `changeSessionId` of Task 6.6 keep the CDI session beans.
- `LogCapture` (test) now has `thrown()` and keeps captured records off the console. `credentialsNeverLogged` (6.5) and `passwordNeverLogged` (6.6) assert on what is actually logged (the bridge logs a failure at ERROR, with the exception attached).
- `BUG.md` ends at BUG-20261010-08; `migration.adoc` has a `=== Request listeners` subsection under "Behaviour changes for `FoyChappeBoot` embedders". `foy-tck/tck-baseline.txt` and `TCK.md` are unchanged (1659/1714, 7 skipped, 48 bad): the Evidence below stands. Task 6.8's two look-ups and Task 6.9's conditional are resolved, and the exit tally of Task 6.10 is restated with the baseline's class names.
- Test fixes found while checking against main: the oversized-header case of `malformedBasicHeaderIs401Not500` stays under chappe's default 8 KiB header limit (chappe would answer first); the FORM redirect assertions strip the `;jsessionid=` that `encodeRedirectURL` appends when the login request created the session; `TransportGuaranteeEndToEndTest` is now written out.

## Maintainer decisions (binding)

- CLIENT-CERT is **deferred** (no chappe change in this phase). DIGEST is **out of scope**. An application whose `auth-method` is neither BASIC nor FORM gets a fail-closed mechanism: protected resources answer 403, `login()` throws.
- Identity SPI: roles are returned directly, with no group or mapper layer. `IdentityStore.validate(String user, char[] password) -> Optional<Identity>`, where `Identity(Principal principal, Set<String> roles)`. Both live in `foy-api` (`io.vidocq.foy.spi.security`, the exported SPI package). They replace `SecurityProvider`/`AuthenticatedUser`, an API break that is documented in the migration page.
- Foy ships `InMemoryIdentityStore` (foy-core, `internal.security`). The embedder configures it through `FoyChappeBoot.Builder.user(...)` or passes its own store with `identityStore(...)`.
- `logout()` clears the identity only: the request's principal and the identity cached in the session for FORM. The session and its attributes stay.
- FORM:
  - An unauthenticated request is **forwarded** to `form-login-page`, so the browser keeps the original URL.
  - After a successful `POST .../j_security_check`, Foy redirects (302) to the **original GET URL saved server-side in the session**, never to a value taken from the request. With nothing saved, it redirects to the context root.
  - An interrupted protected POST is not replayed.
  - `j_security_check` accepts POST only; any other method gets 405 with `Allow: POST`.
- A successful login rotates the session id (`changeSessionId`) by default, with an embedder opt-out.
- A CONFIDENTIAL or INTEGRAL constraint on a non-secure request answers 403. When the embedder configured an HTTPS port on `FoyChappeBoot`, it answers 302 to `https://<host>:<port><uri>` instead.
- Credential handling:
  - Passwords are compared in constant time (`MessageDigest.isEqual` on encoded bytes) and every `char[]` is wiped after use.
  - Credentials are never logged.
  - Basic headers are parsed strictly.
- Constraints are evaluated on the canonical path of the original request, before any target. On an exact pattern, the descriptor beats the annotation (§13.4.1).
- The BASIC realm comes from `login-config/realm-name`. When absent, Foy uses the neutral `Restricted`, not `vidocq`.
- Accepted gaps of this phase, recorded in `TCK.md` and the roadmap: `ClientCertTests.clientCertTest`, `ClientCertAnnoTests.clientCertTest` (CLIENT-CERT deferred), `SecFormTests.test2`, `SecFormTests.test2_anno` (they need `error.jsp` executed, and Foy has no JSP engine).

## Controller rulings (2026-10-10, on the plan's open questions)

- Session id rotation on login: `FoyChappeBoot.Builder.rotateSessionIdOnLogin(boolean)`, default `true`, is the opt-out meant by the decision above.
- Under FORM, `login()` creates the session when none exists, so the identity survives the request (§13.10 lets the container track it in the session). Documented in the migration page.
- `Restricted` is the default realm name.
- `SecurityProvider`/`AuthenticatedUser` are removed outright, with no deprecated bridge. No other repository of the workspace uses them (checked: vidocq, cassini, quickstarts, tassis), and Foy is pre-1.0.
- The `j_password` value lives as a `String` in the parameter map and cannot be wiped; documented, not worked around.
- Merge order: foy#21 (`2026-10-10-cdi-session-context.md`) lands first. This phase branches from `main` after it, so `FoyChappeBoot` and `HttpSessionImpl` already carry the CDI session wiring when Task 6.1 starts.

## Evidence (Phase 5 exit, 1659/1714)

`foy-tck/tck-baseline.txt`: 1714 run, 1659 passing, 7 skipped, 48 bad. `spec.*` 163/207 (7 skipped). Re-checked at main 776b708: the file is unchanged since a75e6ad (`api.*` 848/859, `pluggability.*` 646/646, `spec.*` 163/207 with 7 skipped, `compat.*` 2/2).

| Class | run | bad today | Fixed by this plan | Left |
|---|---|---|---|---|
| `spec.security.secform.SecFormTests` | 25 | 17 | 15: `test1, test3, test4, test6, test10, test11, test14, test16, test17, test18, test1_anno, test3_anno, test4_anno, test6_anno, test14_anno` | `test2, test2_anno` (JSP) |
| `spec.security.secbasic.SecBasicTests` | 14 | 8 | 8: `test1, test2, test3, test4, test6, test2_anno, test4_anno, test6_anno` | 0 |
| `spec.security.denyUncovered.DenyUncoveredTests` | 5 | 4 | 4: `testPartialDDServlet, testDenySomeUncovered, testExcludeAuthConstraint, testAllMethodsAllowedAnno` | 0 |
| `spec.security.metadatacomplete.MetaDataCompleteTests` | 6 | 4 | 4: `test1, test2, test3, test6` | 0 |
| `spec.security.annotations.AnnotationsTests` | 7 | 2 | 2: `test2, test7` | 0 |
| `spec.security.clientcert.ClientCertTests` | 1 | 1 | 0 | `clientCertTest` (deferred) |
| `spec.security.clientcertanno.ClientCertAnnoTests` | 1 | 1 | 0 | `clientCertTest` (deferred) |

**Exit arithmetic:**
- Passing: 1659 + 15 + 8 + 4 + 4 + 2 = **1692 passing / 1714 run, 7 skipped**.
- Bad: 48 − 33 = 15, which is 2 SecForm JSP + 2 CLIENT-CERT + 8 multipart (Phase 7) + 3 `ServletContext40Tests` (JSP).
- Per family: `api.*` 848/859, `pluggability.*` 646/646, `spec.*` 196/207 (7 skipped), `compat.*` 2/2.
- The research's 1694 included the two CLIENT-CERT tests, which are deferred by decision.

**Tests that pass today and must stay green:**
- `secbasic`: `test5, test7, test1_anno, test3_anno, test5_anno, test7_anno`.
- `secform`: `test5, test7, test8, test9, test13, test15, test5_anno, test15_anno`.
- `annotations`: `test1, test3, test4, test5, test6`.
- `metadatacomplete`: `test4, test5`.
- `denyUncovered`: `testAccessToMethodAllowed`. It passes today only because `/TestServlet` is not enforced. Once enforced, it needs the `user`/`password` system properties from Task 6.2.
- `secform test13`: it POSTs anonymously to the FORM-protected `ControlServlet`. Today it gets 200. Once enforced, it gets the login page with a 200 through a forward, which is still a non-error response.

**TCK facts this plan relies on** (from the descriptors in `servlet-tck-runtime-6.1.0.jar`, `servlet/tck/spec/security/*/*_web.xml`):

- **Users** (the GlassFish `sun-web.xml` mapping, restated in `foy-tck`; Foy never parses `sun-web.xml`):
  - `j2ee`/`j2ee`: Administrator, Employee.
  - `javajoe`/`javajoe`: Manager, Employee.
  - Nobody has `VP`.
- **secform:**
  - `login-config` FORM, `/login.jsp`, `/error.jsp`. Both are `<jsp-file>` servlets, which Foy skips, so a forward to `/login.jsp` lands on `*.jsp` → `TwoTestServlet`, with a 200 and non-error content.
  - The client GETs the protected page, POSTs `j_username`/`j_password` to `<ctx>/j_security_check`, follows a 301/302 with a GET and keeps the cookies (`addNewCookies`).
- **secbasic:** BASIC, `realm-name` `default`. The `RoleReverse*` servlets map `MGR→Administrator, ADM→Manager`. The annotated servlets take their `security-role-ref`s from the web.xml `<servlet>` of the same name.
- **metadatacomplete:** `metadata-complete="true"`. `/ServletSecTest` carries two constraints (POST Manager, GET Administrator). `/ServletDenyAll` has an empty `auth-constraint`.
- **annotations:**
  - `/PartialDDTest` has a descriptor constraint with omission POST, Administrator; the descriptor beats `PartialDDServlet`'s annotation on that exact pattern.
  - `/GuestPageTest` has a descriptor constraint (GET/POST Manager).
- **denyUncovered:**
  - `<deny-uncovered-http-methods/>` is set.
  - `/TestServlet` is GET/POST Administrator.
  - `/ExcludeAuthConstraint` is omission GET/POST with an empty `auth-constraint`.
  - `/PartialDDServlet` is `@ServletSecurity(@HttpConstraint default, GET/POST Administrator)`, so PUT/DELETE are uncovered.
  - `/AllMethodsAllowedAnno` is `@HttpConstraint(rolesAllowed=...)`, which covers every method.
- **System properties read by the clients:** `user`, `password`, `authuser`, `authpassword`. `DenyUncoveredTests` and `MetaDataCompleteTests` read them without defaults.

## Global Constraints

- Branch `pr/ybl/servlet-completion-phase6` from `main`. **No chappe change** in this phase. Foy builds against the published chappe/vauban `0.4.0-SNAPSHOT`.
- Runtime modules keep zero dependency beyond the Jakarta APIs plus chappe/vauban. Everything needed is in the JDK: `Base64`, `MessageDigest`, `SecureRandom`, `CharsetDecoder`.
- Static codegen doctrine: no new `getAnnotation(` / `getDeclaredConstructor(` / `Class.forName(` in foy-core main outside the `NoProductReflectionTest` allow-list. `@ServletSecurity`, `@DeclareRoles` and `@RunAs` come from `WebComponentDescriptor` (`servletSecurity()`, `declaredRoles()`, `runAs()`), never from runtime reflection.
- Java Modules: say "Java Modules", never the abbreviation. `io.vidocq.foy.spi.security` is already exported by `foy-api`'s `module-info.java`. `io.vidocq.foy.internal.security` is already exported by foy-core.
- Virtual threads for any thread Foy starts. This phase starts none.
- English everywhere: code, Javadoc, comments, tests, docs, commits.
- Conventional Commits, `git commit -S -s`, trailers `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi`.
- Every reproducible bug found on the way goes to Foy's `BUG.md` (id, date, symptom, minimal repro, cause hypothesis, status). Any performance figure goes to `BENCH.md` first. The plan makes no performance claim.
- Write original code. Do not consult or copy the Tomcat sources under `../provenance-audit/corpus`: they exist for the similarity audit.
- Build with `./mvnw` from the Foy repository root (Java 25 and Maven 3.9.16, through `sdk env`). The other agent's plan `docs/superpowers/plans/2026-10-10-cdi-session-context.md` is not touched.
- Security invariants: credentials, `Authorization` values, `j_password` and Base64 credential tokens never appear in a log record, an exception message or an error page. Every `char[]` password is wiped (`Arrays.fill(pw, '\0')`) once validated. Secrets are compared with `MessageDigest.isEqual`. Authenticators fail closed: an exception denies.
- **Gates:**
  - Every task keeps `./mvnw -ntp install` green.
  - Tasks that touch request handling (6.3, 6.5, 6.6, 6.7, 6.8, 6.9) also run `./mvnw -ntp -Ptck -pl foy-tck test`.
  - TCK checks:
    - Tasks 6.2, 6.3, 6.5, 6.6 and 6.7 run the security classes with `./run-official-tck-servlet6.1.sh --no-install -Dtest='SecBasicTests,SecFormTests,AnnotationsTests,MetaDataCompleteTests,DenyUncoveredTests'`.
    - Tasks 6.5, 6.6 and 6.7 also run `--family spec`, tallied with `foy-tck/tck-tally.sh` and joined per class against `foy-tck/tck-baseline.txt` with `LC_ALL=C join … | awk '$5 != $2 || $6 > $3 || $7 > $4 || $5-$6-$7 < $2-$3-$4 {print "REGRESSION", $0}'`. No class may regress, and every `tiers=Stats` line must read `reflection=0`.
  - The exit task runs `--all`.

## Review Focus

1. **A session id the client received before logging in** (from the login-page visit, or planted by an attacker). After FORM login or `login()`, the old id no longer resolves to the session, the client holds a new id, and the session attributes are kept. Tests: Task 6.6 `loginRotatesTheSessionAndKeepsItsAttributes`, Task 6.7 `loginRotatesAnExistingSession`.
2. **A post-login redirect the client tries to steer** (a `redirect` parameter, a `Referer`, a protected POST, a saved target outside the context). Foy redirects only to the server-saved GET target inside the context, else to the context root. Tests: Task 6.6 `noSavedTargetRedirectsToTheContextRoot`, `protectedPostIsNotReplayed`, `FormAuthenticatorTest.safeTargetRefusesForeignTargets`.
3. **A protected resource reached through a back door:** an encoded path, a path parameter, a `..` segment, the container default servlet, an unmapped path or a HEAD request. The constraint of the canonical path applies, and an anonymous client never gets 200. Tests: Task 6.5 `canonicalPathDrivesTheConstraint`, `constraintAppliesBeforeTheDefaultServlet`, `headIsCheckedAsItsOwnMethod`.
4. **Credentials leaking into logs or errors:** the `Authorization` header, `j_password`, a `login()` password, or an identity store that throws. No log record or error message holds the password or its Base64 form. Tests: Task 6.1 `CredentialCheckTest`, Task 6.5 `credentialsNeverLogged`, Task 6.6 `passwordNeverLogged`.
5. **Malformed or hostile Basic headers** (bad Base64, no colon, empty user, control characters, non-UTF-8, oversized, tab separator) answer 401 with a challenge, never 500. Tests: Task 6.1 `BasicAuthenticatorTest`, Task 6.5 `malformedBasicHeaderIs401Not500`.

---

### Task 6.1: Identity SPI, in-memory store, strict BASIC parsing, embedder configuration

**Files:**
- Create:
  - `foy-api/src/main/java/io/vidocq/foy/spi/security/Identity.java`
  - `foy-api/src/main/java/io/vidocq/foy/spi/security/IdentityStore.java`
- Delete:
  - `foy-api/src/main/java/io/vidocq/foy/spi/security/AuthenticatedUser.java`
  - `foy-api/src/main/java/io/vidocq/foy/spi/security/SecurityProvider.java`
  - `foy-core/src/main/java/io/vidocq/foy/internal/security/AnonymousSecurityProvider.java`
- Create:
  - `foy-core/src/main/java/io/vidocq/foy/internal/security/InMemoryIdentityStore.java`
  - `foy-core/src/main/java/io/vidocq/foy/internal/security/CredentialCheck.java`
  - `foy-core/src/main/java/io/vidocq/foy/internal/security/SecuritySettings.java`
- Rewrite: `foy-core/src/main/java/io/vidocq/foy/internal/security/BasicAuthenticator.java`
- Modify:
  - `foy-core/src/main/java/io/vidocq/foy/internal/security/SecurityConstraintEnforcer.java` (adapt to `IdentityStore`/`Identity`; it is deleted in Task 6.5)
  - `foy-core/src/main/java/io/vidocq/foy/internal/boot/DeployOptions.java` (component `SecurityProvider securityProvider` → `SecuritySettings security`)
  - `foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppDeployer.java` (`newContext`, line ~194)
  - `foy-core/src/main/java/io/vidocq/foy/internal/container/VidocqServletContext.java` (lines ~75 and ~251-257)
  - `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java` (lines ~96, ~143-151, ~672-676, ~784-813)
  - `foy-core/src/main/java/io/vidocq/foy/internal/bridge/ChappeServletBridge.java` (the `SecurityConstraintEnforcer` call site, ~434)
  - `foy-chappe/src/main/java/io/vidocq/foy/chappe/FoyChappeBoot.java` (Builder plus `build()` ~204, `DeployOptions` chain ~285)
  - `foy-tck/src/main/java/io/vidocq/foy/tck/ServletTestHarness.java` (lines ~142, ~269, ~300)
- Tests:
  - Create `foy-core/src/test/java/io/vidocq/foy/internal/security/InMemoryIdentityStoreTest.java`
  - Create `foy-core/src/test/java/io/vidocq/foy/internal/security/CredentialCheckTest.java`
  - Rewrite `foy-core/src/test/java/io/vidocq/foy/internal/security/BasicAuthenticatorTest.java`
  - Modify `foy-core/src/test/java/io/vidocq/foy/internal/ServletSecurityEndToEndTest.java` (provider → store)
  - Modify `foy-core/src/test/java/io/vidocq/foy/internal/boot/WebAppDeployerEndToEndTest.java:475` (still compiles: `null` first argument)
  - Modify `foy-tck/src/test/java/io/vidocq/foy/tck/conformance/ConformanceSecurityTest.java`
  - Modify `foy-chappe/src/test/java/io/vidocq/foy/chappe/FoyChappeBootTest.java`

**Interfaces:**
- Produces (foy-api, exported):

```java
public record Identity(Principal principal, Set<String> roles) {
    public static Identity of(String name, Set<String> roles);
    public String name();                 // principal().getName()
    public boolean hasRole(String role);
    public record NamedPrincipal(String name) implements Principal { }
}

@FunctionalInterface
public interface IdentityStore {
    Optional<Identity> validate(String username, char[] password);
    static IdentityStore none();          // refuses everyone
}
```

- Produces (foy-core, `io.vidocq.foy.internal.security`):

```java
public final class InMemoryIdentityStore implements IdentityStore {
    public static Builder builder();
    public static final class Builder {
        public Builder user(String name, char[] password, String... roles);
        public boolean isEmpty();
        public InMemoryIdentityStore build();
    }
}
public final class CredentialCheck {
    /** Validates, never throws, always wipes {@code password}; a store exception is a refusal. */
    public static Optional<Identity> validate(IdentityStore store, String username, char[] password);
}
public record SecuritySettings(IdentityStore identityStore, int confidentialPort, boolean rotateSessionIdOnLogin) {
    public static final SecuritySettings DEFAULTS;   // none(), -1, true
    public SecuritySettings withIdentityStore(IdentityStore s);
    public SecuritySettings withConfidentialPort(int port);
    public SecuritySettings withRotateSessionIdOnLogin(boolean rotate);
}
public final class BasicAuthenticator {             // implements Authenticator from Task 6.5
    public static final String DEFAULT_REALM = "Restricted";
    static final int MAX_CREDENTIALS_LENGTH = 8192;
    public BasicAuthenticator(IdentityStore store, String realm);
    public String realm();
    public Optional<Identity> tryAuthenticate(String authorizationHeader);
    public String challengeHeaderValue();
    record UserPassword(String user, char[] password) {}
    static UserPassword parse(String authorizationHeader);   // null when refused
}
```

- Produces (other modules):
  - `DeployOptions`: the record component `SecuritySettings security()` (never null) replaces `securityProvider()`. Withers: `withSecurity(SecuritySettings)`, `withIdentityStore(IdentityStore)`. `withSecurityProvider` is removed.
  - `VidocqServletContext`: `SecuritySettings securitySettings()`, `void setSecuritySettings(SecuritySettings)`, `IdentityStore identityStore()`. `securityProvider()`/`setSecurityProvider()` are removed.
  - `HttpServletRequestImpl`: `void bindIdentity(Identity identity, String authType)` (a null identity clears both), `Identity identity()`. `bindAuthenticated`/`currentUser` are removed.
  - `FoyChappeBoot.Builder`: `identityStore(IdentityStore)`, `user(String name, char[] password, String... roles)`, `confidentialPort(int)`, `rotateSessionIdOnLogin(boolean)`. `identityStore` and `user` are exclusive: `build()` throws `IllegalStateException`.
  - `ServletTestHarness.Builder`: `identityStore(IdentityStore)` replaces `securityProvider(SecurityProvider)`.

- [ ] **Step 1: Write the failing tests.**

`InMemoryIdentityStoreTest`:

```java
package io.vidocq.foy.internal.security;

import io.vidocq.foy.spi.security.Identity;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryIdentityStoreTest {

    private static InMemoryIdentityStore store() {
        return InMemoryIdentityStore.builder()
                .user("alice", "a-secret".toCharArray(), "admin", "user")
                .user("bob", "b-secret".toCharArray(), "user")
                .build();
    }

    @Test
    void validCredentialsYieldThePrincipalAndTheRoles() {
        Identity id = store().validate("alice", "a-secret".toCharArray()).orElseThrow();
        assertEquals("alice", id.name());
        assertEquals("alice", id.principal().getName());
        assertEquals(Set.of("admin", "user"), id.roles());
    }

    @Test
    void wrongPasswordUnknownUserAndNullsAreRefused() {
        var s = store();
        assertTrue(s.validate("alice", "b-secret".toCharArray()).isEmpty());
        assertTrue(s.validate("carol", "a-secret".toCharArray()).isEmpty());
        assertTrue(s.validate(null, "a-secret".toCharArray()).isEmpty());
        assertTrue(s.validate("alice", null).isEmpty());
        assertTrue(s.validate("alice", new char[0]).isEmpty());
    }

    @Test
    void theDeclaredArrayIsNotRetained() {
        char[] pw = "a-secret".toCharArray();
        var s = InMemoryIdentityStore.builder().user("alice", pw, "admin").build();
        java.util.Arrays.fill(pw, 'x');
        assertTrue(s.validate("alice", "a-secret".toCharArray()).isPresent());
    }

    @Test
    void aUserDeclaredTwiceIsRejected() {
        var b = InMemoryIdentityStore.builder().user("alice", "p".toCharArray());
        assertThrows(IllegalArgumentException.class, () -> b.user("alice", "q".toCharArray()));
    }
}
```

`CredentialCheckTest`:

```java
package io.vidocq.foy.internal.security;

import io.vidocq.foy.internal.LogCapture;
import io.vidocq.foy.spi.security.IdentityStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CredentialCheckTest {

    @Test
    void thePasswordIsWipedWhateverTheOutcome() {
        char[] pw = "a-secret".toCharArray();
        CredentialCheck.validate(IdentityStore.none(), "alice", pw);
        for (char c : pw) assertEquals('\0', c);
    }

    @Test
    void aStoreThatThrowsRefusesAndTheLogHoldsNoCredential() {
        IdentityStore broken = (u, p) -> { throw new IllegalStateException("db down for " + u + "/" + new String(p)); };
        try (var log = LogCapture.of(CredentialCheck.class.getName())) {
            assertTrue(CredentialCheck.validate(broken, "alice", "a-secret".toCharArray()).isEmpty());
            assertFalse(log.warnings().isEmpty());
            for (String w : log.warnings()) {
                assertFalse(w.contains("alice"), w);
                assertFalse(w.contains("a-secret"), w);
                assertFalse(w.contains("db down"), w);
            }
        }
    }

    @Test
    void aStoreReturningNullRefuses() {
        assertTrue(CredentialCheck.validate((u, p) -> null, "alice", "x".toCharArray()).isEmpty());
    }
}
```

`BasicAuthenticatorTest` (replace the file):

```java
package io.vidocq.foy.internal.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class BasicAuthenticatorTest {

    private static final InMemoryIdentityStore USERS = InMemoryIdentityStore.builder()
            .user("alice", "pa:ss".toCharArray(), "admin").build();
    private final BasicAuthenticator basic = new BasicAuthenticator(USERS, "Corp");

    private static String b64(byte[] raw) { return Base64.getEncoder().encodeToString(raw); }
    private static String b64(String raw) { return b64(raw.getBytes(StandardCharsets.UTF_8)); }

    @Test
    void validHeaderAuthenticatesAndSplitsOnTheFirstColon() {
        assertEquals("alice", basic.tryAuthenticate("Basic " + b64("alice:pa:ss")).orElseThrow().name());
        assertEquals("alice", basic.tryAuthenticate("basic " + b64("alice:pa:ss")).orElseThrow().name());
    }

    @Test
    void malformedHeadersAreRefusedWithoutException() {
        String[] bad = {
                null, "", "Basic", "Basic ", "Bearer " + b64("alice:pa:ss"), "Basic\t" + b64("alice:pa:ss"),
                "Basic %%%%", "Basic " + b64("alice"), "Basic " + b64(":pa:ss"),
                "Basic " + b64("ali\u0001ce:pa:ss"), "Basic " + b64("alice:pa\nss"),
                "Basic " + b64(new byte[] {(byte) 0xC3, (byte) 0x28, ':', 'x'}),
                "Basic " + "A".repeat(BasicAuthenticator.MAX_CREDENTIALS_LENGTH),
                "Basic " + b64("alice:pa:ss") + " trailing"
        };
        for (String h : bad) assertNull(BasicAuthenticator.parse(h), String.valueOf(h));
        for (String h : bad) assertTrue(basic.tryAuthenticate(h).isEmpty(), String.valueOf(h));
    }

    @Test
    void wrongPasswordIsRefused() {
        assertTrue(basic.tryAuthenticate("Basic " + b64("alice:nope")).isEmpty());
    }

    @Test
    void challengeQuotesTheRealm() {
        assertEquals("Basic realm=\"Corp\", charset=\"UTF-8\"", basic.challengeHeaderValue());
        assertEquals("Basic realm=\"a\\\"b\\\\c\", charset=\"UTF-8\"",
                new BasicAuthenticator(USERS, "a\"b\\c").challengeHeaderValue());
        assertEquals("Restricted", BasicAuthenticator.DEFAULT_REALM);
    }
}
```

`FoyChappeBootTest`, two new tests (reuse the existing `plainApp(null)` helper):

```java
    @Test
    void usersDeclaredOnTheBuilderReachTheContext() throws Exception {
        try (var mounted = plainApp(null).contextPath("/").user("alice", "a-secret".toCharArray(), "admin")
                .build().orElseThrow()) {
            var store = mounted.servletContext().identityStore();
            assertEquals(Set.of("admin"), store.validate("alice", "a-secret".toCharArray()).orElseThrow().roles());
            assertTrue(store.validate("alice", "wrong".toCharArray()).isEmpty());
        }
    }

    @Test
    void identityStoreAndUsersAreExclusive() {
        var b = plainApp(null).contextPath("/").identityStore(io.vidocq.foy.spi.security.IdentityStore.none())
                .user("alice", "a".toCharArray());
        assertThrows(IllegalStateException.class, b::build);
    }
```

- [ ] **Step 2: Run them and confirm they fail.** Run `./mvnw -ntp -pl foy-api,foy-core,foy-chappe install -Dtest='InMemoryIdentityStoreTest,CredentialCheckTest,BasicAuthenticatorTest,FoyChappeBootTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compilation FAIL, because `Identity`, `IdentityStore`, `InMemoryIdentityStore` and `CredentialCheck` do not exist.

- [ ] **Step 3: Write the SPI** (license header copied from `SessionStore.java`).

`Identity.java`:

```java
package io.vidocq.foy.spi.security;

import java.security.Principal;
import java.util.Objects;
import java.util.Set;

/**
 * The outcome of a successful credential validation: the caller's principal and the application
 * roles it holds (Servlet 6.1 section 13.3). The roles are application role names, used as they
 * are by {@code isUserInRole} (after the servlet's {@code security-role-ref}s) and by the security
 * constraints; Foy maps no group.
 */
public record Identity(Principal principal, Set<String> roles) {

    public Identity {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(principal.getName(), "principal name");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    /** An identity whose principal is a {@link NamedPrincipal} called {@code name}. */
    public static Identity of(String name, Set<String> roles) {
        return new Identity(new NamedPrincipal(name), roles);
    }

    /** The principal's name: the value of {@code getRemoteUser()}. */
    public String name() { return principal.getName(); }

    public boolean hasRole(String role) { return role != null && roles.contains(role); }

    /** A principal that carries only a name. */
    public record NamedPrincipal(String name) implements Principal {
        public NamedPrincipal { Objects.requireNonNull(name, "name"); }
        @Override public String getName() { return name; }
        @Override public String toString() { return name; }
    }
}
```

`IdentityStore.java`:

```java
package io.vidocq.foy.spi.security;

import java.util.Optional;

/**
 * Validates a user name and a password against the embedder's user base: the container "realm"
 * of Servlet 6.1 section 13.6. Foy calls it for BASIC, FORM and {@code HttpServletRequest.login}.
 *
 * <p>Implementations must:</p>
 * <ul>
 *   <li>never log, keep or echo the password; Foy wipes the array as soon as {@code validate}
 *       returns;</li>
 *   <li>compare secrets in constant time ({@link java.security.MessageDigest#isEqual}) and spend the
 *       same work on an unknown user as on a wrong password;</li>
 *   <li>return {@link Optional#empty()} on any refusal. Foy treats an exception or a {@code null}
 *       result as a refusal.</li>
 * </ul>
 */
@FunctionalInterface
public interface IdentityStore {

    Optional<Identity> validate(String username, char[] password);

    /** A store that refuses everyone: the default when the embedder configures none. */
    static IdentityStore none() {
        return (username, password) -> Optional.empty();
    }
}
```

- [ ] **Step 4: Write the foy-core security support classes.**

`CredentialCheck.java`:

```java
package io.vidocq.foy.internal.security;

import io.vidocq.foy.spi.security.Identity;
import io.vidocq.foy.spi.security.IdentityStore;

import java.util.Arrays;
import java.util.Optional;

/** Calls an {@link IdentityStore} the way every Foy mechanism must: fail closed, wipe, log no credential. */
public final class CredentialCheck {

    private static final System.Logger LOG = System.getLogger(CredentialCheck.class.getName());

    private CredentialCheck() {}

    /**
     * Validates {@code username}/{@code password} and wipes {@code password}, whatever the outcome.
     * A store that throws or returns {@code null} refuses. The refusal is logged at WARNING with the
     * store class and the exception class only, never the user name, the password or the message.
     */
    public static Optional<Identity> validate(IdentityStore store, String username, char[] password) {
        try {
            if (username == null || password == null) return Optional.empty();
            Optional<Identity> result = store.validate(username, password);
            return result == null ? Optional.empty() : result;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "identity store {0} failed with {1}; the login is refused",
                    store.getClass().getName(), e.getClass().getName());
            return Optional.empty();
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }
}
```

`InMemoryIdentityStore.java`:

```java
package io.vidocq.foy.internal.security;

import io.vidocq.foy.spi.security.Identity;
import io.vidocq.foy.spi.security.IdentityStore;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An {@link IdentityStore} over a fixed set of users the embedder declares
 * ({@code FoyChappeBoot.Builder.user}). Each password is kept only as a salted SHA-256 digest:
 * no text, and no {@code char[]} the caller still owns. Validation compares digests with
 * {@link MessageDigest#isEqual} and digests a decoy for an unknown user, so the response time does
 * not tell which user names exist. This is not a password database: a persistent or shared user
 * base plugs its own {@link IdentityStore}, with a slow hash (PBKDF2, Argon2) if it stores hashes.
 */
public final class InMemoryIdentityStore implements IdentityStore {

    private record User(Identity identity, byte[] salt, byte[] digest) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, User> users;
    private final byte[] decoySalt = salt();
    private final byte[] decoyDigest = digest(decoySalt, new char[] {'\0'});

    private InMemoryIdentityStore(Map<String, User> users) {
        this.users = Map.copyOf(users);
    }

    public static Builder builder() { return new Builder(); }

    @Override
    public Optional<Identity> validate(String username, char[] password) {
        if (username == null || password == null) return Optional.empty();
        User user = users.get(username);
        byte[] candidate = digest(user == null ? decoySalt : user.salt(), password);
        try {
            boolean equal = MessageDigest.isEqual(candidate, user == null ? decoyDigest : user.digest());
            return equal && user != null ? Optional.of(user.identity()) : Optional.empty();
        } finally {
            Arrays.fill(candidate, (byte) 0);
        }
    }

    private static byte[] salt() {
        byte[] s = new byte[16];
        RANDOM.nextBytes(s);
        return s;
    }

    /** SHA-256 over salt then the UTF-8 password; every intermediate buffer is wiped. */
    static byte[] digest(byte[] salt, char[] password) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(bytes);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        } finally {
            Arrays.fill(bytes, (byte) 0);
            if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        }
    }

    public static final class Builder {
        private final Map<String, User> users = new LinkedHashMap<>();

        private Builder() {}

        /** Declares a user. {@code password} is digested at once and not kept: the caller may wipe it. */
        public Builder user(String name, char[] password, String... roles) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(password, "password");
            if (users.containsKey(name)) throw new IllegalArgumentException("user '" + name + "' declared twice");
            byte[] salt = salt();
            users.put(name, new User(Identity.of(name, Set.copyOf(List.of(roles))), salt, digest(salt, password)));
            return this;
        }

        public boolean isEmpty() { return users.isEmpty(); }

        public InMemoryIdentityStore build() { return new InMemoryIdentityStore(users); }
    }
}
```

`SecuritySettings.java`:

```java
package io.vidocq.foy.internal.security;

import io.vidocq.foy.spi.security.IdentityStore;

/**
 * Container-side security settings of a deployment, chosen by the embedder.
 *
 * @param identityStore          user base behind BASIC, FORM and {@code login()}; {@code null} means
 *                               {@link IdentityStore#none()}
 * @param confidentialPort       HTTPS port a CONFIDENTIAL/INTEGRAL constraint redirects a plain request
 *                               to; {@code -1}: such a request gets 403 (Task 6.9)
 * @param rotateSessionIdOnLogin whether a successful login changes the session id (Task 6.6)
 */
public record SecuritySettings(IdentityStore identityStore, int confidentialPort, boolean rotateSessionIdOnLogin) {

    public static final SecuritySettings DEFAULTS = new SecuritySettings(IdentityStore.none(), -1, true);

    public SecuritySettings {
        if (identityStore == null) identityStore = IdentityStore.none();
        if (confidentialPort != -1 && (confidentialPort < 1 || confidentialPort > 65_535)) {
            throw new IllegalArgumentException("confidentialPort must be -1 or a TCP port: " + confidentialPort);
        }
    }

    public SecuritySettings withIdentityStore(IdentityStore s) {
        return new SecuritySettings(s, confidentialPort, rotateSessionIdOnLogin);
    }

    public SecuritySettings withConfidentialPort(int port) {
        return new SecuritySettings(identityStore, port, rotateSessionIdOnLogin);
    }

    public SecuritySettings withRotateSessionIdOnLogin(boolean rotate) {
        return new SecuritySettings(identityStore, confidentialPort, rotate);
    }
}
```

`BasicAuthenticator.java` (full replacement; Task 6.5 adds `implements Authenticator`):

```java
package io.vidocq.foy.internal.security;

import io.vidocq.foy.spi.security.Identity;
import io.vidocq.foy.spi.security.IdentityStore;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * HTTP Basic authentication (RFC 7617, Servlet 6.1 section 13.6.1). The header is parsed strictly,
 * and any deviation is a refusal, never an exception:
 * <ul>
 *   <li>the scheme is {@code Basic} (any case) followed by spaces, then one Base64 token and
 *       nothing else;</li>
 *   <li>the decoded bytes are strict UTF-8 with no control character;</li>
 *   <li>the user-id is non-empty and ends at the first colon.</li>
 * </ul>
 * The password only ever lives in a {@code char[]} that is wiped after validation.
 */
public final class BasicAuthenticator {

    /** Realm sent when {@code login-config} names none: neutral, no product name. */
    public static final String DEFAULT_REALM = "Restricted";
    /** Longest accepted header value, in characters. */
    static final int MAX_CREDENTIALS_LENGTH = 8192;

    private final IdentityStore store;
    private final String realm;

    public BasicAuthenticator(IdentityStore store, String realm) {
        this.store = Objects.requireNonNull(store, "store");
        this.realm = Objects.requireNonNull(realm, "realm");
    }

    public String realm() { return realm; }

    /** The identity carried by an {@code Authorization} header value; empty when absent, malformed or refused. */
    public Optional<Identity> tryAuthenticate(String authorizationHeader) {
        UserPassword credentials = parse(authorizationHeader);
        if (credentials == null) return Optional.empty();
        return CredentialCheck.validate(store, credentials.user(), credentials.password());
    }

    public String challengeHeaderValue() {
        return "Basic realm=\"" + realm.replace("\\", "\\\\").replace("\"", "\\\"") + "\", charset=\"UTF-8\"";
    }

    record UserPassword(String user, char[] password) {}

    /** The user and password of a Basic header, or {@code null} when anything is off. */
    static UserPassword parse(String header) {
        if (header == null || header.length() > MAX_CREDENTIALS_LENGTH) return null;
        if (header.length() < 6 || !header.regionMatches(true, 0, "Basic", 0, 5) || header.charAt(5) != ' ') {
            return null;
        }
        int start = 5;
        while (start < header.length() && header.charAt(start) == ' ') start++;
        String token = header.substring(start);
        if (token.isEmpty()) return null;
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            return null;
        }
        char[] all = null;
        try {
            CharBuffer chars;
            try {
                chars = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(decoded));
            } catch (CharacterCodingException e) {
                return null;
            }
            all = new char[chars.remaining()];
            chars.get(all);
            if (chars.hasArray()) Arrays.fill(chars.array(), '\0');
            int colon = -1;
            for (int i = 0; i < all.length; i++) {
                char c = all[i];
                if (c < 0x20 || c == 0x7f) return null;
                if (colon < 0 && c == ':') colon = i;
            }
            if (colon <= 0) return null;
            return new UserPassword(new String(all, 0, colon), Arrays.copyOfRange(all, colon + 1, all.length));
        } finally {
            Arrays.fill(decoded, (byte) 0);
            if (all != null) Arrays.fill(all, '\0');
        }
    }
}
```

- [ ] **Step 5: Rewire the callers.**
  1. `SecurityConstraintEnforcer`:
     - The constructor becomes `SecurityConstraintEnforcer(IdentityStore store)` with `new BasicAuthenticator(store, BasicAuthenticator.DEFAULT_REALM)`.
     - `impl.currentUser()` becomes `impl.identity()`, and `bindAuthenticated(u, "BASIC")` becomes `bindIdentity(u, HttpServletRequest.BASIC_AUTH)`.
     - `extractUser` returns `Identity`. For a non-Foy request, it returns `null`.
     - `user.hasRole(r)` is unchanged.
     - In `ChappeServletBridge.handle(Request, List, HttpServletResponseImpl)`, inside the inner `try` of the main branch, `new SecurityConstraintEnforcer(servletContext.securityProvider())` becomes `new SecurityConstraintEnforcer(servletContext.identityStore())`. Nothing else in the bridge changes in this task.
  2. `HttpServletRequestImpl`:
     - Replace the fields at ~96 with `private Identity identity; private String authType;`.
     - Replace `bindAuthenticated`/`currentUser` with:

```java
    /** Binds (or, with {@code null}, clears) the caller identity of this request and its auth type. */
    public void bindIdentity(Identity identity, String authType) {
        this.identity = identity;
        this.authType = identity == null ? null : authType;
    }

    public Identity identity() { return identity; }
```

     - Replace lines ~672-676 and ~784-813 with:

```java
    @Override public String getRemoteUser() { return identity == null ? null : identity.name(); }
    @Override public boolean isUserInRole(String role) { return identity != null && identity.hasRole(role); }
    @Override public Principal getUserPrincipal() { return identity == null ? null : identity.principal(); }
```

```java
    @Override public boolean authenticate(jakarta.servlet.http.HttpServletResponse response) throws IOException {
        if (identity != null) return true;
        var basic = new io.vidocq.foy.internal.security.BasicAuthenticator(identityStore(),
                io.vidocq.foy.internal.security.BasicAuthenticator.DEFAULT_REALM);
        var found = basic.tryAuthenticate(getHeader("Authorization"));
        if (found.isEmpty()) {
            response.setHeader("WWW-Authenticate", basic.challengeHeaderValue());
            response.sendError(401);
            return false;
        }
        bindIdentity(found.get(), BASIC_AUTH);
        return true;
    }
    @Override public void login(String username, String password) throws ServletException {
        if (identity != null) throw new ServletException("an identity is already established for this request");
        Identity found = io.vidocq.foy.internal.security.CredentialCheck.validate(identityStore(), username,
                password == null ? null : password.toCharArray()).orElse(null);
        if (found == null) throw new ServletException("login failed");
        bindIdentity(found, BASIC_AUTH);
    }
    @Override public void logout() { bindIdentity(null, null); }

    private io.vidocq.foy.spi.security.IdentityStore identityStore() {
        return servletContext instanceof VidocqServletContext v ? v.identityStore()
                : io.vidocq.foy.spi.security.IdentityStore.none();
    }
```

     - Import `io.vidocq.foy.spi.security.Identity`.
  3. `VidocqServletContext`: replace the `securityProvider` field (~75) and its accessors (~251-257) with:

```java
    private volatile io.vidocq.foy.internal.security.SecuritySettings securitySettings =
            io.vidocq.foy.internal.security.SecuritySettings.DEFAULTS;
    public io.vidocq.foy.internal.security.SecuritySettings securitySettings() { return securitySettings; }
    public void setSecuritySettings(io.vidocq.foy.internal.security.SecuritySettings settings) {
        this.securitySettings = java.util.Objects.requireNonNull(settings, "settings");
    }
    public io.vidocq.foy.spi.security.IdentityStore identityStore() { return securitySettings.identityStore(); }
```

  4. `DeployOptions`:
     - Rename the first component to `SecuritySettings security`.
     - In the compact constructor, add `if (security == null) security = SecuritySettings.DEFAULTS;`.
     - In every wither and in the 8-argument constructor, replace `securityProvider` with `security`.
     - Replace `withSecurityProvider` with:

```java
    public DeployOptions withSecurity(SecuritySettings s) {
        return new DeployOptions(s, resourceProvider, servletContextName,
                reservedServletNames, reservedFilterNames, reservedUrlPatterns, componentFactory, handlesTypes,
                virtualServerName, tempDirRoot);
    }

    public DeployOptions withIdentityStore(io.vidocq.foy.spi.security.IdentityStore store) {
        return withSecurity(security.withIdentityStore(store));
    }
```

     - Update the `@param` Javadoc.
  5. `WebAppDeployer.newContext`: replace line ~194 with `ctx.setSecuritySettings(options.security());`.
  6. `FoyChappeBoot.Builder`:
     - Add the four setters below.
     - Make `SecuritySettings security = securitySettings();` the **first** statement of `build()`, so the exclusivity check throws `IllegalStateException`, not a wrapped `ServletException`.
     - Append `.withSecurity(security)` to the `DeployOptions` chain at ~285.

```java
        private IdentityStore identityStore;
        private InMemoryIdentityStore.Builder users;
        private int confidentialPort = -1;
        private boolean rotateSessionIdOnLogin = true;

        /**
         * The user base behind BASIC, FORM and {@code HttpServletRequest.login}. With neither this nor
         * {@link #user}, every login fails. Exclusive with {@link #user}.
         */
        public Builder identityStore(IdentityStore store) {
            this.identityStore = Objects.requireNonNull(store, "store");
            return this;
        }

        /**
         * Declares a user of Foy's in-memory identity store, with its application roles. The password is
         * digested at once (salted SHA-256) and not kept: the caller may wipe the array afterwards.
         */
        public Builder user(String name, char[] password, String... roles) {
            if (users == null) users = InMemoryIdentityStore.builder();
            users.user(name, password, roles);
            return this;
        }

        /** HTTPS port a CONFIDENTIAL/INTEGRAL constraint redirects plain HTTP to; by default such a request gets 403. */
        public Builder confidentialPort(int port) { this.confidentialPort = port; return this; }

        /** Whether a successful login changes the session id (session fixation defence); {@code true} by default. */
        public Builder rotateSessionIdOnLogin(boolean rotate) { this.rotateSessionIdOnLogin = rotate; return this; }

        SecuritySettings securitySettings() {
            if (identityStore != null && users != null) {
                throw new IllegalStateException("identityStore(...) and user(...) are exclusive");
            }
            IdentityStore store = identityStore != null ? identityStore
                    : users != null ? users.build() : IdentityStore.none();
            return new SecuritySettings(store, confidentialPort, rotateSessionIdOnLogin);
        }
```

     - Imports: `io.vidocq.foy.spi.security.IdentityStore`, `io.vidocq.foy.internal.security.InMemoryIdentityStore`, `io.vidocq.foy.internal.security.SecuritySettings`.
  7. `ServletTestHarness`: field `private IdentityStore identityStore;` and `public Builder identityStore(IdentityStore s) { this.identityStore = s; return this; }`. In `start()`, `.withIdentityStore(identityStore)` replaces `.withSecurityProvider(securityProvider)`.
  8. Migrate the remaining test callers:
     - `ConformanceSecurityTest` builds its store with `InMemoryIdentityStore.builder().user("alice", "admin".toCharArray(), "admin").user("bob", "user".toCharArray(), "user").build()` and passes it through `.identityStore(...)`. Delete the `unused()` helper.
     - `ServletSecurityEndToEndTest.USERS` becomes the same kind of store (alice/a-secret admin, bob/b-secret user). `ctx.setSecurityProvider(USERS)` becomes `ctx.setSecuritySettings(SecuritySettings.DEFAULTS.withIdentityStore(USERS))`.
  9. Delete `AuthenticatedUser.java`, `SecurityProvider.java` and `AnonymousSecurityProvider.java`. Then `grep -rn "SecurityProvider\|AuthenticatedUser" --include='*.java' .` (outside `target/`) must be empty. The docs are updated in Task 6.10.

- [ ] **Step 6: Run and confirm the tests pass.** Run `./mvnw -ntp install`. Expected: green, with the new tests passing and `ServletSecurityEndToEndTest`/`ConformanceSecurityTest` unchanged in outcome.

- [ ] **Step 7: Commit.** Use the message `feat(api)!: replace SecurityProvider with the IdentityStore SPI and an in-memory store`. The body names the API break and the `FoyChappeBoot` setters.

---

### Task 6.2: TCK harness identities and system properties

**Files:**
- Create: `foy-tck/src/test/java/io/vidocq/foy/tck/arquillian/TckIdentities.java`
- Modify:
  - `foy-tck/src/test/java/io/vidocq/foy/tck/arquillian/VidocqDeployableContainer.java` (the `ServletTestHarness.builder()` chain, ~line 181)
  - `foy-tck/pom.xml` (`tck-official` profile, surefire `systemPropertyVariables`, ~line 185)
- Test: create `foy-tck/src/test/java/io/vidocq/foy/tck/arquillian/TckIdentitiesTest.java`

**Interfaces:**
- Consumes: `InMemoryIdentityStore.builder()`, `ServletTestHarness.Builder.identityStore(IdentityStore)` (Task 6.1).
- Produces: `TckIdentities.store()`, a package-private `IdentityStore` with j2ee/j2ee (Administrator, Employee) and javajoe/javajoe (Manager, Employee).

- [ ] **Step 1: Write the failing test.**

```java
package io.vidocq.foy.tck.arquillian;

import org.junit.jupiter.api.Test;

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
}
```

- [ ] **Step 2: Run it.** Run `./mvnw -ntp -Ptck -pl foy-tck test -Dtest=TckIdentitiesTest`. Expected: FAIL to compile (`TckIdentities` missing).

- [ ] **Step 3: Implement.**

```java
package io.vidocq.foy.tck.arquillian;

import io.vidocq.foy.internal.security.InMemoryIdentityStore;
import io.vidocq.foy.spi.security.IdentityStore;

/**
 * The users the Servlet TCK security tests log in with. They restate the GlassFish principal-to-role
 * mapping of the security wars' {@code sun-web.xml}, which Foy never reads. Nobody holds {@code VP}:
 * the tests assert {@code isUserInRole("VP") == false}. Keep in sync with the {@code user},
 * {@code password}, {@code authuser} and {@code authpassword} properties of {@code foy-tck/pom.xml}.
 */
final class TckIdentities {

    private TckIdentities() {}

    static IdentityStore store() {
        return InMemoryIdentityStore.builder()
                .user("j2ee", "j2ee".toCharArray(), "Administrator", "Employee")
                .user("javajoe", "javajoe".toCharArray(), "Manager", "Employee")
                .build();
    }
}
```

In `VidocqDeployableContainer`, add `.identityStore(TckIdentities.store())` to the `ServletTestHarness.builder()` chain, after `.componentFactory(factory)`. In `foy-tck/pom.xml`, inside the same `systemPropertyVariables` as `webServerHost`, add:

```xml
                                <!-- Users of the security tests (TckIdentities). DenyUncoveredTests and
                                     MetaDataCompleteTests read them without defaults. -->
                                <user>j2ee</user>
                                <password>j2ee</password>
                                <authuser>javajoe</authuser>
                                <authpassword>javajoe</authpassword>
```

- [ ] **Step 4: Run the tests.**
  1. `./mvnw -ntp install` and `./mvnw -ntp -Ptck -pl foy-tck test`: green.
  2. `./run-official-tck-servlet6.1.sh --no-install -Dtest='SecBasicTests,SecFormTests,AnnotationsTests,MetaDataCompleteTests,DenyUncoveredTests' > /tmp/foy-tck-6.2.log 2>&1`, then `bash foy-tck/tck-tally.sh /tmp/foy-tck-6.2.log`.
  3. Expected: `AnnotationsTests.test2` and `SecBasicTests.test4_anno` (the annotation path is enforced today) turn green. `SecBasicTests.test2_anno` turns green only if its assertions do not need role refs; otherwise Task 6.3 fixes it.
  4. Write the per-class figures in the commit body. No class may lose a passing test.

- [ ] **Step 5: Commit.** Use the message `build(tck): give the security tests their users and identity store`.

---

### Task 6.3: `security-role-ref`, declared roles and `isUserInRole` of the executing servlet

**Files:**
- Modify:
  - `foy-core/src/main/java/io/vidocq/foy/internal/webxml/WebAppDescriptor.java` (`ServletDef`, line ~80)
  - `foy-core/src/main/java/io/vidocq/foy/internal/webxml/WebXmlParser.java` (`parseServlet`, ~292)
  - `foy-core/src/main/java/io/vidocq/foy/internal/webxml/ComponentMerger.java` (~55-130)
  - `foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppModel.java` (record, compact constructor, Builder)
  - `foy-core/src/main/java/io/vidocq/foy/internal/boot/DescriptorMerger.java` (`mergeEffective` ~254-279, `mergeServlets` ~281-328)
  - `foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppDeployer.java` (`newContext`)
  - `foy-core/src/main/java/io/vidocq/foy/internal/container/VidocqServletContext.java`
  - `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java`
  - `foy-core/src/main/java/io/vidocq/foy/internal/bridge/ChappeServletBridge.java` (private `invoke`, ~935-960)
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/security/RoleMapping.java`
- Test:
  - Create `foy-core/src/test/java/io/vidocq/foy/internal/security/RoleMappingTest.java`
  - Create `foy-core/src/test/java/io/vidocq/foy/internal/security/RoleRefEndToEndTest.java`
  - Modify `foy-core/src/test/java/io/vidocq/foy/internal/webxml/WebXmlParserSchemaTest.java`, `FragmentMergerTest.java`, `foy-core/src/test/java/io/vidocq/foy/internal/boot/DescriptorMergerTest.java`

**Interfaces:**
- Consumes: `Identity` (6.1), `HttpServletRequestImpl.identity()` (6.1).
- Produces:

```java
// WebAppDescriptor
public record ServletDef(String name, String className, Map<String, String> initParams,
                         Boolean asyncSupported, int loadOnStartup, MultipartConfigDef multipartConfig,
                         boolean enabled, String runAs, String jspFile,
                         Map<String, String> securityRoleRefs) { }   // role-name -> role-link, never null

// WebAppModel: new last component
Map<String, Map<String, String>> securityRoleRefs     // servlet name -> (role-name -> role-link)
public Builder securityRoleRefs(String servletName, Map<String, String> refs);
// securityRoles() now means "declared roles": <security-role> ∪ @DeclareRoles (unless metadata-complete)

public final class RoleMapping {
    public static final RoleMapping EMPTY;
    public RoleMapping(Set<String> declaredRoles, Map<String, Map<String, String>> roleRefs);
    public Set<String> declaredRoles();
    public RoleMapping withDeclaredRoles(Collection<String> more);
    public boolean isUserInRole(String servletName, Identity identity, String role);
}

// VidocqServletContext
public RoleMapping roleMapping();
public void setRoleMapping(RoleMapping mapping);

// HttpServletRequestImpl
public String enterServlet(String servletName);   // returns the previous one
public void leaveServlet(String previous);
public String currentServletName();
```

- [ ] **Step 1: Write the failing tests.**

`RoleMappingTest`:

```java
package io.vidocq.foy.internal.security;

import io.vidocq.foy.spi.security.Identity;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RoleMappingTest {

    private static final Identity J2EE = Identity.of("j2ee", Set.of("Administrator", "Employee"));
    private final RoleMapping roles = new RoleMapping(Set.of("Administrator", "Manager", "Employee"),
            Map.of("Reverse", Map.of("MGR", "Administrator", "ADM", "Manager")));

    @Test
    void theExecutingServletsRoleRefWins() {
        assertTrue(roles.isUserInRole("Reverse", J2EE, "MGR"));
        assertFalse(roles.isUserInRole("Reverse", J2EE, "ADM"));
    }

    @Test
    void anUnreferencedRoleIsTestedDirectly() {
        assertTrue(roles.isUserInRole("Reverse", J2EE, "Employee"));
        assertTrue(roles.isUserInRole(null, J2EE, "Administrator"));
        assertFalse(roles.isUserInRole("Other", J2EE, "MGR"));
    }

    @Test
    void starIsNeverARoleAndDoubleStarMeansAuthenticated() {
        assertFalse(roles.isUserInRole(null, J2EE, "*"));
        assertTrue(roles.isUserInRole(null, J2EE, "**"));
        var declared = roles.withDeclaredRoles(Set.of("**"));
        assertFalse(declared.isUserInRole(null, J2EE, "**"), "a declared ** is an ordinary role");
    }

    @Test
    void anonymousAndNullAreNeverInRole() {
        assertFalse(roles.isUserInRole(null, null, "Administrator"));
        assertFalse(roles.isUserInRole(null, J2EE, null));
        assertFalse(roles.isUserInRole(null, null, "**"));
    }
}
```

`WebXmlParserSchemaTest`, new test (use the class's `parse` helper):

```java
    @Test
    void securityRoleRefsAreParsed() throws IOException {
        var d = parse("""
                <servlet><servlet-name>s</servlet-name><servlet-class>a.S</servlet-class>
                  <security-role-ref><role-name>MGR</role-name><role-link>Administrator</role-link></security-role-ref>
                  <security-role-ref><role-name>EMP</role-name></security-role-ref>
                </servlet>
                <servlet><servlet-name>p</servlet-name><servlet-class>a.P</servlet-class></servlet>
                """);
        assertEquals(Map.of("MGR", "Administrator", "EMP", "EMP"), d.servlets().get(0).securityRoleRefs());
        assertEquals(Map.of(), d.servlets().get(1).securityRoleRefs());
    }
```

`FragmentMergerTest`, new test: web.xml servlet `s` with ref `MGR→A`, plus a fragment declaring the same servlet with refs `MGR→B` and `EMP→E`. After the merge, `s.securityRoleRefs()` equals `Map.of("MGR", "A", "EMP", "E")`: web.xml wins per role-name and fragments add the others. Build the fragment the way the class's existing servlet-merge tests do.

`DescriptorMergerTest`, new test `roleRefsAndDeclaredRolesReachTheModel`:
- Fixture: a nested servlet class `@jakarta.annotation.security.DeclareRoles("auditor") public static class Audited extends HttpServlet {}`, declared in web.xml with `<security-role-ref><role-name>A</role-name><role-link>auditor</role-link></security-role-ref>` and `<security-role><role-name>admin</role-name></security-role>`.
- Expected: `model.securityRoleRefs().get("audited")` equals `Map.of("A", "auditor")`, and `model.securityRoles()` contains both `admin` and `auditor`.
- With `metadata-complete="true"`, `auditor` is absent.
- Use the class's existing merge helper.

`RoleRefEndToEndTest` (deployed app; programmatic `login()` authenticates, since enforcement only comes in Task 6.5):

```java
package io.vidocq.foy.internal.security;

import io.vidocq.foy.internal.boot.DeployOptions;
import io.vidocq.foy.internal.boot.Deployment;
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoleRefEndToEndTest {

    private Deployment deployment;
    private io.vidocq.chappe.api.Server server;
    private int port;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        if (deployment != null) deployment.close();
    }

    /** Logs in as alice (role admin), prints MGR, includes /plain, prints MGR again. */
    public static class Linked extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            req.login("alice", "a-secret");
            resp.getWriter().write("linked:" + req.isUserInRole("MGR") + ";");
            req.getRequestDispatcher("/plain").include(req, resp);
            resp.getWriter().write("after:" + req.isUserInRole("MGR"));
        }
    }

    public static class Plain extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("plain:" + req.isUserInRole("MGR") + ";");
        }
    }

    @Test
    void roleRefsFollowTheExecutingServlet() throws Exception {
        var model = WebAppModel.builder("/")
                .servlet(new ServletDecl("linked", Linked.class, Linked::new, List.of("/linked"), Map.of(), -1, false))
                .servlet(new ServletDecl("plain", Plain.class, Plain::new, List.of("/plain"), Map.of(), -1, false))
                .securityRoleRefs("linked", Map.of("MGR", "admin"))
                .securityRoles(List.of("admin"))
                .build();
        deployment = WebAppDeployer.deploy(model, DeployOptions.defaults(getClass().getClassLoader())
                .withIdentityStore(InMemoryIdentityStore.builder().user("alice", "a-secret".toCharArray(), "admin").build()));
        var started = io.vidocq.foy.internal.TestServerLauncherAccess.start(deployment.handler());
        server = started.server();
        port = started.port();
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/linked")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("linked:true;plain:false;after:true", r.body());
    }
}
```

- [ ] **Step 2: Run them and confirm they fail.** Run `./mvnw -ntp -pl foy-core install -Dtest='RoleMappingTest,RoleRefEndToEndTest,WebXmlParserSchemaTest,FragmentMergerTest,DescriptorMergerTest'`. Expected: compilation FAIL (`RoleMapping`, `securityRoleRefs` missing).

- [ ] **Step 3: Implement parsing and merging.**
  1. `ServletDef`: append the component `Map<String, String> securityRoleRefs`, and add a compact constructor:

```java
        public ServletDef {
            securityRoleRefs = securityRoleRefs == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(securityRoleRefs));
        }
```

     The 5-argument convenience constructor passes `Map.of()` as the new last argument.
  2. `WebXmlParser.parseServlet`: pass `parseRoleRefs(e)` as the last argument, and add:

```java
    /** {@code <security-role-ref>}s of a servlet: role-name -> role-link (the role-name itself when no link). */
    private static Map<String, String> parseRoleRefs(Element servlet) {
        Map<String, String> refs = new LinkedHashMap<>();
        for (Element r : childrenByTag(servlet, "security-role-ref")) {
            String name = firstText(r, "role-name");
            if (name == null) continue;
            String link = firstText(r, "role-link");
            refs.putIfAbsent(name, link == null ? name : link);
        }
        return refs;
    }
```

  3. `ComponentMerger`:
     - `Component` gets `final Map<String, String> roleRefs = new LinkedHashMap<>();`.
     - In the web.xml loop: `c.roleRefs.putAll(d.securityRoleRefs());`.
     - In the fragment loop: `d.securityRoleRefs().forEach(c.roleRefs::putIfAbsent);`.
     - The output `new ServletDef(...)` passes `c.roleRefs` last.
  4. `WebAppModel`:
     - Append the component `Map<String, Map<String, String>> securityRoleRefs`. Javadoc: "servlet name to its `security-role-ref`s (role-name to role-link)".
     - Compact constructor:

```java
        var refs = new java.util.LinkedHashMap<String, Map<String, String>>();
        securityRoleRefs.forEach((servlet, m) -> refs.put(servlet, copyOf(m)));
        securityRoleRefs = Collections.unmodifiableMap(refs);
```

     - Builder: `private final Map<String, Map<String, String>> securityRoleRefs = new LinkedHashMap<>();` and

```java
        public Builder securityRoleRefs(String servletName, Map<String, String> refs) {
            securityRoleRefs.put(Objects.requireNonNull(servletName, "servletName"), Map.copyOf(refs));
            return this;
        }
```

     - `build()` passes `securityRoleRefs` last.
     - Update the `securityRoles` Javadoc to "declared roles: `<security-role>` and `@DeclareRoles` (Phase 6)".
  5. `DescriptorMerger`:
     - `mergeServlets` returns `Set<String>`, the roles declared by the merged servlets' `@DeclareRoles`.
     - In the web.xml loop, after `classDescriptor` is computed:

```java
            if (!def.securityRoleRefs().isEmpty()) target.securityRoleRefs(def.name(), def.securityRoleRefs());
            if (classDescriptor != null) declared.addAll(classDescriptor.declaredRoles());
```

     - In the annotated-only loop: `declared.addAll(descriptor(factory, a.type(), "servlet", a.name()).declaredRoles());`. `ann` is already empty under metadata-complete.
     - In `mergeEffective`, remove `target.securityRoles(webXml.securityRoles());` and, after `mergeServlets`, write:

```java
        Set<String> roles = new LinkedHashSet<>(webXml.securityRoles());
        roles.addAll(mergeServlets(webXml, ann, factory, target));
        target.securityRoles(List.copyOf(roles));
```

- [ ] **Step 4: Implement the runtime resolution.**

`RoleMapping.java`:

```java
package io.vidocq.foy.internal.security;

import io.vidocq.foy.spi.security.Identity;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Role names of one web application (Servlet 6.1 sections 13.3 and 13.5): the declared roles
 * ({@code <security-role>}, {@code @DeclareRoles}, {@code ServletContext.declareRoles}) and each
 * servlet's {@code security-role-ref}s.
 */
public final class RoleMapping {

    public static final RoleMapping EMPTY = new RoleMapping(Set.of(), Map.of());

    private final Set<String> declaredRoles;
    private final Map<String, Map<String, String>> roleRefs;

    public RoleMapping(Set<String> declaredRoles, Map<String, Map<String, String>> roleRefs) {
        this.declaredRoles = Set.copyOf(declaredRoles);
        var refs = new LinkedHashMap<String, Map<String, String>>();
        roleRefs.forEach((servlet, m) -> refs.put(servlet, Map.copyOf(m)));
        this.roleRefs = Map.copyOf(refs);
    }

    public Set<String> declaredRoles() { return declaredRoles; }

    public RoleMapping withDeclaredRoles(Collection<String> more) {
        var all = new LinkedHashSet<>(declaredRoles);
        all.addAll(more);
        return new RoleMapping(all, roleRefs);
    }

    /**
     * {@code isUserInRole(role)} for the servlet {@code servletName} now executing ({@code null}
     * outside any servlet, e.g. in an async {@code start} thread). The servlet's
     * {@code security-role-ref} with that role-name is followed to its role-link; otherwise the
     * role is tested as it is. {@code "*"} is never a role. {@code "**"} means "authenticated",
     * unless the application declares a role named {@code "**"}.
     */
    public boolean isUserInRole(String servletName, Identity identity, String role) {
        if (identity == null || role == null || "*".equals(role)) return false;
        if ("**".equals(role) && !declaredRoles.contains("**")) return true;
        String linked = servletName == null ? null : roleRefs.getOrDefault(servletName, Map.of()).get(role);
        return identity.hasRole(linked != null ? linked : role);
    }
}
```

1. `VidocqServletContext`: `private volatile RoleMapping roleMapping = RoleMapping.EMPTY;` with `roleMapping()` and `setRoleMapping(RoleMapping m)` (`requireNonNull`).
2. `WebAppDeployer.newContext`: `ctx.setRoleMapping(new RoleMapping(Set.copyOf(model.securityRoles()), model.securityRoleRefs()));`.
3. `HttpServletRequestImpl`:

```java
    private String currentServletName;

    /**
     * Marks {@code servletName} as the servlet now executing, for {@code security-role-ref}
     * resolution (section 13.3), and returns the previous one for {@link #leaveServlet}.
     */
    public String enterServlet(String servletName) {
        String previous = currentServletName;
        currentServletName = servletName;
        return previous;
    }

    public void leaveServlet(String previous) { currentServletName = previous; }

    public String currentServletName() { return currentServletName; }

    @Override public boolean isUserInRole(String role) {
        var roles = servletContext instanceof VidocqServletContext v ? v.roleMapping()
                : io.vidocq.foy.internal.security.RoleMapping.EMPTY;
        return roles.isUserInRole(currentServletName, identity, role);
    }
```

4. `ChappeServletBridge`, private `invoke(…, FilterRegistry.Chain chain)` (~935), in the `impl != null` path: replace the block from `boolean previous = impl.isAsyncSupported();` to the closing brace of its `finally` (today `setAsyncSupported(previous && target.asyncSupported()` is split over two lines) with:

```java
        boolean previous = impl.isAsyncSupported();
        String previousServlet = impl.enterServlet(target.servletName());
        impl.setAsyncSupported(previous && target.asyncSupported() && chain.asyncSupported());
        try {
            new VidocqFilterChain(filters, target.servlet()).doFilter(req, res);
        } finally {
            impl.setAsyncSupported(previous);
            impl.leaveServlet(previousServlet);
        }
```

- [ ] **Step 5: Run the tests and the gates.**
  1. `./mvnw -ntp install` and `./mvnw -ntp -Ptck -pl foy-tck test`: green.
  2. Run the security TCK classes (same command as Task 6.2, log `/tmp/foy-tck-6.3.log`). Expected: `SecBasicTests.test6_anno`, plus `test2_anno` if Task 6.2 left it, with no lost pass.

- [ ] **Step 6: Commit.** Use the message `feat(core): resolve security-role-ref of the executing servlet and collect declared roles`.

---

### Task 6.4: `ConstraintTable` — constraints compiled per url-pattern

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/security/ConstraintTable.java`
- Test: create `foy-core/src/test/java/io/vidocq/foy/internal/security/ConstraintTableTest.java`

**Interfaces:**
- Consumes: `SecurityDefs.SecurityConstraintDef`/`WebResourceCollectionDef` (existing), `UrlPatternMatcher` (existing: `of`, `matches`, `precedence`, `pattern`), `Identity` (6.1).
- Produces:

```java
public final class ConstraintTable {
    public record Decision(Access access, Set<String> roles, boolean anyAuthenticated, boolean confidential) {
        public enum Access { UNCHECKED, EXCLUDED, ROLES }
        public static final Decision UNCHECKED;
        public static final Decision EXCLUDED;
        public boolean permits(Identity identity);
    }
    public static final ConstraintTable EMPTY;
    public static Builder builder();
    public boolean isEmpty();
    public Decision decide(String canonicalPath, String method);
    public List<String> uncoveredMethodWarnings();
    public static final class Builder {
        public Builder declaredRoles(Collection<String> roles);
        public Builder denyUncoveredHttpMethods(boolean deny);
        public Builder descriptor(SecurityConstraintDef def);                     // call before servlet(...)
        public Set<String> descriptorPatterns();
        public Set<String> servlet(Collection<String> patterns, ServletSecurityElement element); // returns skipped patterns
        public ConstraintTable build();
    }
}
```

- [ ] **Step 1: Write the failing tests.**

```java
package io.vidocq.foy.internal.security;

import io.vidocq.foy.internal.security.ConstraintTable.Decision;
import io.vidocq.foy.internal.security.ConstraintTable.Decision.Access;
import io.vidocq.foy.internal.webxml.SecurityDefs.SecurityConstraintDef;
import io.vidocq.foy.internal.webxml.SecurityDefs.WebResourceCollectionDef;
import io.vidocq.foy.spi.security.Identity;
import jakarta.servlet.HttpConstraintElement;
import jakarta.servlet.HttpMethodConstraintElement;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.annotation.ServletSecurity.EmptyRoleSemantic;
import jakarta.servlet.annotation.ServletSecurity.TransportGuarantee;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConstraintTableTest {

    private static SecurityConstraintDef dd(List<String> patterns, List<String> methods, List<String> omissions,
                                            List<String> roles, String guarantee) {
        return new SecurityConstraintDef(List.of(new WebResourceCollectionDef("c", patterns, methods, omissions)),
                roles, guarantee);
    }

    private static SecurityConstraintDef dd(String pattern, List<String> roles) {
        return dd(List.of(pattern), List.of(), List.of(), roles, null);
    }

    @Test
    void anUnconstrainedPathIsUnchecked() {
        var t = ConstraintTable.builder().descriptor(dd("/a/*", List.of("A"))).build();
        assertEquals(Access.UNCHECKED, t.decide("/b", "GET").access());
        assertEquals(Access.UNCHECKED, ConstraintTable.EMPTY.decide("/a", "GET").access());
    }

    @Test
    void onlyTheBestMatchingPatternApplies() {
        var t = ConstraintTable.builder()
                .descriptor(dd("/a/*", List.of("A")))
                .descriptor(dd("/a/b", List.of("B")))
                .descriptor(dd("/a/b/c/*", null))
                .descriptor(dd("*.jsp", List.of("C")))
                .descriptor(dd("/", List.of()))
                .build();
        assertEquals(Set.of("B"), t.decide("/a/b", "GET").roles());
        assertEquals(Set.of("A"), t.decide("/a", "GET").roles());
        assertEquals(Set.of("A"), t.decide("/a/x.jsp", "GET").roles(), "prefix beats extension");
        assertEquals(Set.of("C"), t.decide("/x.jsp", "GET").roles());
        assertEquals(Access.UNCHECKED, t.decide("/a/b/c/d", "GET").access(), "longest prefix only");
        assertEquals(Access.EXCLUDED, t.decide("/elsewhere", "GET").access(), "default pattern");
    }

    @Test
    void theEmptyPatternIsTheContextRoot() {
        var t = ConstraintTable.builder().descriptor(dd("", List.of())).build();
        assertEquals(Access.EXCLUDED, t.decide("/", "GET").access());
        assertEquals(Access.UNCHECKED, t.decide("/x", "GET").access());
    }

    @Test
    void combinationAtOnePatternAndMethod() {
        var union = ConstraintTable.builder().descriptor(dd("/x", List.of("A"))).descriptor(dd("/x", List.of("B"))).build();
        assertEquals(Set.of("A", "B"), union.decide("/x", "GET").roles());
        var excluded = ConstraintTable.builder().descriptor(dd("/x", List.of("A"))).descriptor(dd("/x", List.of())).build();
        assertEquals(Access.EXCLUDED, excluded.decide("/x", "GET").access());
        var unchecked = ConstraintTable.builder().descriptor(dd("/x", List.of("A"))).descriptor(dd("/x", null)).build();
        assertEquals(Access.UNCHECKED, unchecked.decide("/x", "GET").access());
    }

    @Test
    void methodsAndOmissions() {
        var t = ConstraintTable.builder()
                .descriptor(dd(List.of("/m"), List.of("GET"), List.of(), List.of("A"), null))
                .descriptor(dd(List.of("/o"), List.of(), List.of("GET"), List.of("A"), null))
                .build();
        assertEquals(Access.ROLES, t.decide("/m", "GET").access());
        assertEquals(Access.UNCHECKED, t.decide("/m", "POST").access(), "uncovered, not denied");
        assertEquals(Access.UNCHECKED, t.decide("/m", "HEAD").access(), "HEAD is its own method");
        assertEquals(Access.ROLES, t.decide("/o", "POST").access());
        assertEquals(Access.UNCHECKED, t.decide("/o", "GET").access());
        var denied = ConstraintTable.builder().denyUncoveredHttpMethods(true)
                .descriptor(dd(List.of("/m"), List.of("GET"), List.of(), List.of("A"), null)).build();
        assertEquals(Access.EXCLUDED, denied.decide("/m", "POST").access());
        assertEquals(Access.EXCLUDED, denied.decide("/m", "HEAD").access());
        assertEquals(Access.UNCHECKED, denied.decide("/unconstrained", "PUT").access());
    }

    @Test
    void transportIsConfidentialOnlyWhenEveryConstraintRequiresIt() {
        var both = ConstraintTable.builder()
                .descriptor(dd(List.of("/c"), List.of(), List.of(), List.of("A"), "CONFIDENTIAL"))
                .descriptor(dd(List.of("/c"), List.of(), List.of(), List.of("B"), "INTEGRAL")).build();
        assertTrue(both.decide("/c", "GET").confidential());
        var mixed = ConstraintTable.builder()
                .descriptor(dd(List.of("/c"), List.of(), List.of(), List.of("A"), "CONFIDENTIAL"))
                .descriptor(dd(List.of("/c"), List.of(), List.of(), List.of("B"), "NONE")).build();
        assertFalse(mixed.decide("/c", "GET").confidential());
    }

    @Test
    void starAndDoubleStar() {
        var t = ConstraintTable.builder().declaredRoles(List.of("A", "B"))
                .descriptor(dd("/star", List.of("*")))
                .descriptor(dd("/any", List.of("**"))).build();
        assertEquals(Set.of("A", "B"), t.decide("/star", "GET").roles());
        Decision any = t.decide("/any", "GET");
        assertTrue(any.anyAuthenticated());
        assertTrue(any.permits(Identity.of("x", Set.of())));
        assertFalse(any.permits(null));
        var declared = ConstraintTable.builder().declaredRoles(List.of("**")).descriptor(dd("/any", List.of("**"))).build();
        assertFalse(declared.decide("/any", "GET").anyAuthenticated());
        assertEquals(Set.of("**"), declared.decide("/any", "GET").roles());
    }

    @Test
    void theDescriptorBeatsTheAnnotationOnAnExactPattern() {
        var b = ConstraintTable.builder().descriptor(dd("/a", null));
        var element = new ServletSecurityElement(new HttpConstraintElement(TransportGuarantee.NONE, "admin"));
        assertEquals(Set.of("/a"), b.servlet(List.of("/a", "/b"), element));
        var t = b.build();
        assertEquals(Access.UNCHECKED, t.decide("/a", "GET").access());
        assertEquals(Set.of("admin"), t.decide("/b", "DELETE").roles(), "an HttpConstraint covers every method");
    }

    @Test
    void allDefaultHttpConstraintLeavesTheOtherMethodsUncovered() {
        var element = new ServletSecurityElement(new HttpConstraintElement(),
                List.of(new HttpMethodConstraintElement("GET", new HttpConstraintElement(TransportGuarantee.NONE, "A")),
                        new HttpMethodConstraintElement("POST", new HttpConstraintElement(TransportGuarantee.NONE, "A"))));
        var b = ConstraintTable.builder().denyUncoveredHttpMethods(true);
        b.servlet(List.of("/p"), element);
        var t = b.build();
        assertEquals(Access.ROLES, t.decide("/p", "GET").access());
        assertEquals(Access.EXCLUDED, t.decide("/p", "PUT").access());
    }

    @Test
    void annotationDenyAndTransport() {
        var element = new ServletSecurityElement(new HttpConstraintElement(EmptyRoleSemantic.DENY),
                List.of(new HttpMethodConstraintElement("GET",
                        new HttpConstraintElement(TransportGuarantee.CONFIDENTIAL, "A"))));
        var b = ConstraintTable.builder();
        b.servlet(List.of("/d"), element);
        var t = b.build();
        assertEquals(Access.EXCLUDED, t.decide("/d", "POST").access());
        assertTrue(t.decide("/d", "GET").confidential());
    }

    @Test
    void uncoveredMethodsAreReported() {
        var t = ConstraintTable.builder()
                .descriptor(dd(List.of("/m"), List.of("GET", "POST"), List.of(), List.of("A"), null))
                .descriptor(dd(List.of("/o"), List.of(), List.of("GET"), List.of("A"), null))
                .descriptor(dd("/all", List.of("A"))).build();
        var w = t.uncoveredMethodWarnings();
        assertEquals(2, w.size(), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.contains("'/m'") && s.contains("every HTTP method other than [GET, POST]")));
        assertTrue(w.stream().anyMatch(s -> s.contains("'/o'") && s.contains("[GET]")));
    }
}
```

- [ ] **Step 2: Run it and confirm it fails.** Run `./mvnw -ntp -pl foy-core test -Dtest=ConstraintTableTest`. Expected: compilation FAIL.

- [ ] **Step 3: Implement.**

```java
package io.vidocq.foy.internal.security;

import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
import io.vidocq.foy.internal.webxml.SecurityDefs.SecurityConstraintDef;
import io.vidocq.foy.internal.webxml.SecurityDefs.WebResourceCollectionDef;
import io.vidocq.foy.spi.security.Identity;
import jakarta.servlet.HttpConstraintElement;
import jakarta.servlet.HttpMethodConstraintElement;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.annotation.ServletSecurity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The security constraints of one web application, compiled once at deploy time (Servlet 6.1
 * sections 13.4 and 13.8).
 *
 * <p>A request is matched against the constrained url-patterns with the servlet mapping rules
 * (exact, longest prefix, extension, default; section 12.2). Only the best pattern applies. Within
 * it, the constraints that cover the request method combine:</p>
 * <ul>
 *   <li>an empty {@code auth-constraint} excludes (403 for everyone);</li>
 *   <li>otherwise a constraint without {@code auth-constraint} leaves the method unchecked;</li>
 *   <li>otherwise the role lists are unioned;</li>
 *   <li>the transport is confidential only when every covering constraint requires it.</li>
 * </ul>
 * <p>A method no constraint covers at a constrained pattern is "uncovered" (section 13.8.4): it is
 * excluded under {@code <deny-uncovered-http-methods/>}, otherwise unchecked and reported at
 * deploy time.</p>
 *
 * <p>The descriptor's constraints are added first. A servlet's {@code ServletSecurityElement}
 * (annotation or {@code setServletSecurity}) then applies to each of its patterns, except the ones
 * a descriptor constraint names exactly: there the descriptor wins for every method (section 13.4.1).</p>
 */
public final class ConstraintTable {

    /** What a request needs, for one url-pattern and one HTTP method. */
    public record Decision(Access access, Set<String> roles, boolean anyAuthenticated, boolean confidential) {

        public enum Access { UNCHECKED, EXCLUDED, ROLES }

        public static final Decision UNCHECKED = new Decision(Access.UNCHECKED, Set.of(), false, false);
        public static final Decision EXCLUDED = new Decision(Access.EXCLUDED, Set.of(), false, false);

        public Decision {
            roles = Set.copyOf(roles);
        }

        /** Whether {@code identity} ({@code null} when anonymous) may proceed. */
        public boolean permits(Identity identity) {
            if (access == Access.UNCHECKED) return true;
            if (access == Access.EXCLUDED || identity == null) return false;
            if (anyAuthenticated) return true;
            for (String r : roles) if (identity.hasRole(r)) return true;
            return false;
        }
    }

    /** One constraint at one url-pattern; before {@code build()} the roles are raw ({@code *}, {@code **}). */
    private record Entry(Set<String> methods, Set<String> omissions, Decision.Access access, Set<String> roles,
                         boolean anyAuthenticated, boolean confidential) {
        boolean covers(String method) {
            return methods.isEmpty() ? !omissions.contains(method) : methods.contains(method);
        }
    }

    public static final ConstraintTable EMPTY = builder().build();

    private final List<UrlPatternMatcher> patterns;
    private final Map<String, List<Entry>> byPattern;
    private final boolean denyUncovered;

    private ConstraintTable(List<UrlPatternMatcher> patterns, Map<String, List<Entry>> byPattern, boolean denyUncovered) {
        this.patterns = patterns;
        this.byPattern = byPattern;
        this.denyUncovered = denyUncovered;
    }

    public static Builder builder() { return new Builder(); }

    public boolean isEmpty() { return byPattern.isEmpty(); }

    /** The decision for a canonical, context-relative path and a method name as sent ({@code HEAD} is its own method). */
    public Decision decide(String canonicalPath, String method) {
        String pattern = null;
        for (UrlPatternMatcher m : patterns) {
            if (m.matches(canonicalPath)) { pattern = m.pattern(); break; }
        }
        if (pattern == null) return Decision.UNCHECKED;
        boolean covered = false, excluded = false, unchecked = false, any = false, confidential = true;
        Set<String> roles = new LinkedHashSet<>();
        for (Entry e : byPattern.get(pattern)) {
            if (!e.covers(method)) continue;
            covered = true;
            confidential &= e.confidential();
            switch (e.access()) {
                case EXCLUDED -> excluded = true;
                case UNCHECKED -> unchecked = true;
                case ROLES -> { roles.addAll(e.roles()); any |= e.anyAuthenticated(); }
            }
        }
        if (!covered) return denyUncovered ? Decision.EXCLUDED : Decision.UNCHECKED;
        if (excluded) return Decision.EXCLUDED;
        if (unchecked) return new Decision(Decision.Access.UNCHECKED, Set.of(), false, confidential);
        return new Decision(Decision.Access.ROLES, roles, any, confidential);
    }

    /** One line per constrained url-pattern that leaves methods uncovered (section 13.8.4), for a deploy-time WARNING. */
    public List<String> uncoveredMethodWarnings() {
        List<String> out = new ArrayList<>();
        for (var e : byPattern.entrySet()) {
            Set<String> explicit = new TreeSet<>();
            Set<String> omittedByAll = null;
            for (Entry entry : e.getValue()) {
                if (!entry.methods().isEmpty()) {
                    explicit.addAll(entry.methods());
                } else if (omittedByAll == null) {
                    omittedByAll = new TreeSet<>(entry.omissions());
                } else {
                    omittedByAll.retainAll(entry.omissions());
                }
            }
            if (omittedByAll == null) {
                out.add("url-pattern '" + e.getKey() + "': every HTTP method other than " + explicit + " is uncovered");
            } else {
                omittedByAll.removeAll(explicit);
                if (!omittedByAll.isEmpty()) {
                    out.add("url-pattern '" + e.getKey() + "': HTTP methods " + omittedByAll + " are uncovered");
                }
            }
        }
        return out;
    }

    public static final class Builder {
        private final Map<String, List<Entry>> byPattern = new LinkedHashMap<>();
        private final Set<String> descriptorPatterns = new LinkedHashSet<>();
        private final Set<String> declaredRoles = new LinkedHashSet<>();
        private boolean denyUncovered;

        private Builder() {}

        /** The roles {@code "*"} stands for. */
        public Builder declaredRoles(Collection<String> roles) { declaredRoles.addAll(roles); return this; }

        public Builder denyUncoveredHttpMethods(boolean deny) { this.denyUncovered = deny; return this; }

        /** A descriptor {@code <security-constraint>}: every url-pattern of each collection. Add these first. */
        public Builder descriptor(SecurityConstraintDef def) {
            Decision.Access access = def.rolesAllowed() == null ? Decision.Access.UNCHECKED
                    : def.rolesAllowed().isEmpty() ? Decision.Access.EXCLUDED : Decision.Access.ROLES;
            Set<String> roles = def.rolesAllowed() == null ? Set.of() : Set.copyOf(def.rolesAllowed());
            boolean confidential = isConfidential(def.transportGuarantee());
            for (WebResourceCollectionDef c : def.collections()) {
                Set<String> methods = Set.copyOf(c.httpMethods());
                Set<String> omissions = methods.isEmpty() ? Set.copyOf(c.httpMethodOmissions()) : Set.of();
                for (String p : c.urlPatterns()) {
                    descriptorPatterns.add(p);
                    add(p, new Entry(methods, omissions, access, roles, false, confidential));
                }
            }
            return this;
        }

        /** The url-patterns a descriptor constraint names exactly (the result of {@code setServletSecurity}). */
        public Set<String> descriptorPatterns() { return Collections.unmodifiableSet(descriptorPatterns); }

        /**
         * A servlet's {@code ServletSecurityElement} on each of its {@code patterns}. A pattern the
         * descriptor constrains exactly is skipped and returned. Each {@code HttpMethodConstraintElement}
         * covers its method. The {@code HttpConstraintElement} covers every other method, unless it has
         * all default values and method constraints exist: then the other methods stay uncovered.
         */
        public Set<String> servlet(Collection<String> patterns, ServletSecurityElement element) {
            Set<String> skipped = new LinkedHashSet<>();
            for (String p : patterns) {
                if (descriptorPatterns.contains(p)) { skipped.add(p); continue; }
                Set<String> named = new LinkedHashSet<>();
                for (HttpMethodConstraintElement m : element.getHttpMethodConstraints()) {
                    named.add(m.getMethodName());
                    add(p, entry(Set.of(m.getMethodName()), Set.of(), m));
                }
                if (!(isAllDefault(element) && !named.isEmpty())) {
                    add(p, entry(Set.of(), Set.copyOf(named), element));
                }
            }
            return skipped;
        }

        public ConstraintTable build() {
            boolean starStarIsARole = declaredRoles.contains("**");
            Map<String, List<Entry>> resolved = new LinkedHashMap<>();
            for (var e : byPattern.entrySet()) {
                List<Entry> entries = new ArrayList<>();
                for (Entry entry : e.getValue()) {
                    if (entry.access() != Decision.Access.ROLES) { entries.add(entry); continue; }
                    Set<String> roles = new LinkedHashSet<>();
                    boolean any = false;
                    for (String r : entry.roles()) {
                        if ("*".equals(r)) roles.addAll(declaredRoles);
                        else if ("**".equals(r) && !starStarIsARole) any = true;
                        else roles.add(r);
                    }
                    entries.add(new Entry(entry.methods(), entry.omissions(), entry.access(), Set.copyOf(roles), any,
                            entry.confidential()));
                }
                resolved.put(e.getKey(), List.copyOf(entries));
            }
            List<UrlPatternMatcher> matchers = resolved.keySet().stream().map(UrlPatternMatcher::of)
                    .sorted(Comparator.comparingInt(UrlPatternMatcher::precedence)).toList();
            return new ConstraintTable(matchers, Collections.unmodifiableMap(resolved), denyUncovered);
        }

        private void add(String pattern, Entry entry) {
            byPattern.computeIfAbsent(pattern, k -> new ArrayList<>()).add(entry);
        }

        private static Entry entry(Set<String> methods, Set<String> omissions, HttpConstraintElement e) {
            String[] roles = e.getRolesAllowed();
            Decision.Access access = roles.length > 0 ? Decision.Access.ROLES
                    : e.getEmptyRoleSemantic() == ServletSecurity.EmptyRoleSemantic.DENY
                    ? Decision.Access.EXCLUDED : Decision.Access.UNCHECKED;
            return new Entry(methods, omissions, access, Set.copyOf(Arrays.asList(roles)), false,
                    e.getTransportGuarantee() == ServletSecurity.TransportGuarantee.CONFIDENTIAL);
        }

        private static boolean isAllDefault(HttpConstraintElement e) {
            return e.getEmptyRoleSemantic() == ServletSecurity.EmptyRoleSemantic.PERMIT
                    && e.getRolesAllowed().length == 0
                    && e.getTransportGuarantee() == ServletSecurity.TransportGuarantee.NONE;
        }

        private static boolean isConfidential(String guarantee) {
            if (guarantee == null) return false;
            String g = guarantee.trim();
            return g.equalsIgnoreCase("CONFIDENTIAL") || g.equalsIgnoreCase("INTEGRAL");
        }
    }
}
```

- [ ] **Step 4: Run the tests.** Run `./mvnw -ntp -pl foy-core test -Dtest=ConstraintTableTest` (PASS), then `./mvnw -ntp install` (green).

- [ ] **Step 5: Commit.** Use the message `feat(core): compile security constraints per url-pattern`.

---

### Task 6.5: Enforcement — `WebSecurity` gate, BASIC from `login-config`, deploy-time table

**Files:**
- Create:
  - `foy-core/src/main/java/io/vidocq/foy/internal/security/Authenticator.java`
  - `foy-core/src/main/java/io/vidocq/foy/internal/security/DenyingAuthenticator.java`
  - `foy-core/src/main/java/io/vidocq/foy/internal/security/WebSecurity.java`
- Delete: `foy-core/src/main/java/io/vidocq/foy/internal/security/SecurityConstraintEnforcer.java`
- Modify:
  - `foy-core/src/main/java/io/vidocq/foy/internal/security/BasicAuthenticator.java` (`implements Authenticator`)
  - `foy-core/src/main/java/io/vidocq/foy/internal/dispatcher/ServletDispatcher.java` (`Mapping` loses `security`)
  - `foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppDeployer.java` (`deploy`, `initServlets` ~410, new `buildSecurity`)
  - `foy-core/src/main/java/io/vidocq/foy/internal/container/VidocqServletContext.java` (`security()`/`setSecurity`)
  - `foy-core/src/main/java/io/vidocq/foy/internal/bridge/ChappeServletBridge.java` (`handle(Request, List, HttpServletResponseImpl)` ~310-480)
- Test:
  - Create `foy-core/src/test/java/io/vidocq/foy/internal/security/SecureApp.java` (test support, reused by 6.6-6.9)
  - Create `foy-core/src/test/java/io/vidocq/foy/internal/security/WebSecurityEndToEndTest.java`
  - Modify `foy-core/src/test/java/io/vidocq/foy/internal/ServletSecurityEndToEndTest.java` (`startWith`)

**Interfaces:**
- Consumes: `ConstraintTable` (6.4), `RoleMapping`/`VidocqServletContext.roleMapping()` (6.3), `SecuritySettings`/`BasicAuthenticator`/`CredentialCheck` (6.1), `HttpServletRequestImpl.identity()/bindIdentity()` (6.1).
- Produces:

```java
public interface Authenticator {
    String authType();                                       // HttpServletRequest.BASIC_AUTH, FORM_AUTH, ...
    Optional<Identity> authenticate(HttpServletRequestImpl req);   // credentials the request carries by itself
    void challenge(HttpServletRequestImpl req, HttpServletResponse res) throws IOException, ServletException;
    default boolean supportsPasswordLogin() { return true; }
    default boolean sessionBased() { return false; }
}
public final class DenyingAuthenticator implements Authenticator { public DenyingAuthenticator(String authMethod); }
public final class WebSecurity {
    public WebSecurity(ConstraintTable constraints, Authenticator authenticator, SecuritySettings settings);
    public static WebSecurity permitAll(SecuritySettings settings);
    public static Authenticator authenticatorFor(LoginConfigDef loginConfig, IdentityStore store);
    public ConstraintTable constraints();
    public Authenticator authenticator();
    public SecuritySettings settings();
    /** true: the request proceeds; false: the response (401/403/challenge) is produced. REQUEST dispatches only. */
    public boolean admit(HttpServletRequestImpl req, HttpServletResponse res, String canonicalPath)
            throws IOException, ServletException;
}
// VidocqServletContext
public WebSecurity security();          // permitAll(securitySettings()) until the deployer sets one
public void setSecurity(WebSecurity security);
// ServletDispatcher.Mapping(UrlPatternMatcher matcher, Servlet servlet, String servletName, boolean asyncSupported)
```

- [ ] **Step 1: Write the test support and the failing tests.**

`SecureApp.java`:

```java
package io.vidocq.foy.internal.security;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.boot.DeployOptions;
import io.vidocq.foy.internal.boot.Deployment;
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.session.SessionManager;
import io.vidocq.foy.internal.webxml.SecurityDefs.SecurityConstraintDef;
import io.vidocq.foy.internal.webxml.SecurityDefs.WebResourceCollectionDef;
import io.vidocq.foy.spi.security.IdentityStore;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** A deployed application with users alice (admin) and bob (user); cookies kept, redirects not followed. */
final class SecureApp implements AutoCloseable {

    static final IdentityStore USERS = InMemoryIdentityStore.builder()
            .user("alice", "a-secret".toCharArray(), "admin")
            .user("bob", "b-secret".toCharArray(), "user")
            .build();

    final Deployment deployment;
    final Server server;
    final int port;
    final CookieManager cookies = new CookieManager();
    final HttpClient client;

    SecureApp(WebAppModel model) { this(model, SecuritySettings.DEFAULTS.withIdentityStore(USERS)); }

    SecureApp(WebAppModel model, SecuritySettings settings) {
        deployment = WebAppDeployer.deploy(model,
                DeployOptions.defaults(SecureApp.class.getClassLoader()).withSecurity(settings));
        var started = io.vidocq.foy.internal.TestServerLauncherAccess.start(deployment.handler());
        server = started.server();
        port = started.port();
        client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    static SecurityConstraintDef constraint(List<String> patterns, List<String> methods, List<String> omissions,
                                            List<String> roles, String guarantee) {
        return new SecurityConstraintDef(List.of(new WebResourceCollectionDef("c", patterns, methods, omissions)),
                roles, guarantee);
    }

    static String basic(String userColonPassword) {
        return "Basic " + Base64.getEncoder().encodeToString(userColonPassword.getBytes(StandardCharsets.UTF_8));
    }

    HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)); }

    HttpResponse<String> get(String path) throws Exception { return send(request(path).GET()); }

    HttpResponse<String> send(String method, String path, String userColonPassword) throws Exception {
        var b = request(path).method(method, HttpRequest.BodyPublishers.noBody());
        if (userColonPassword != null) b.header("Authorization", basic(userColonPassword));
        return send(b);
    }

    HttpResponse<String> postForm(String path, String urlEncodedForm) throws Exception {
        return send(request(path).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(urlEncodedForm)));
    }

    HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A GET from a separate client that presents only {@code sessionId} (a stale or planted session id). */
    HttpResponse<String> getWithSession(String path, String sessionId) throws Exception {
        var jar = new CookieManager();
        var cookie = new HttpCookie(SessionManager.COOKIE_NAME, sessionId);
        cookie.setPath("/");
        cookie.setVersion(0);
        jar.getCookieStore().add(URI.create("http://127.0.0.1:" + port + "/"), cookie);
        return HttpClient.newBuilder().cookieHandler(jar).followRedirects(HttpClient.Redirect.NEVER).build()
                .send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    String sessionId() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> SessionManager.COOKIE_NAME.equals(c.getName()))
                .map(HttpCookie::getValue).findFirst().orElse(null);
    }

    @Override
    public void close() {
        server.stop();
        deployment.close();
    }
}
```

`WebSecurityEndToEndTest`:

```java
package io.vidocq.foy.internal.security;

import io.vidocq.foy.internal.LogCapture;
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.webxml.SecurityDefs.LoginConfigDef;
import jakarta.servlet.HttpConstraintElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.annotation.ServletSecurity.TransportGuarantee;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.vidocq.foy.internal.security.SecureApp.constraint;
import static org.junit.jupiter.api.Assertions.*;

class WebSecurityEndToEndTest {

    public static class Who extends HttpServlet {
        @Override protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            if ("/boom".equals(req.getServletPath())) throw new IllegalStateException("boom");
            resp.getWriter().write("user=" + req.getRemoteUser() + " type=" + req.getAuthType());
        }
    }

    public static class Forwarder extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            req.getRequestDispatcher("/admin/x").forward(req, resp);
        }
    }

    private static ServletDecl who(String... patterns) {
        return new ServletDecl("who", Who.class, Who::new, List.of(patterns), Map.of(), -1, false);
    }

    private static WebAppModel.Builder adminApp() {
        return WebAppModel.builder("/")
                .servlet(who("/admin/*", "/open", "/form", "/h", "/boom"))
                .servlet(new ServletDecl("fwd", Forwarder.class, Forwarder::new, List.of("/fwd"), Map.of(), -1, false))
                .securityConstraints(List.of(
                        constraint(List.of("/admin/*", "/static/*", "/boom"), List.of(), List.of(), List.of("admin"), null),
                        constraint(List.of("/form"), List.of(), List.of("GET"), List.of("admin"), null),
                        constraint(List.of("/h"), List.of("GET"), List.of(), List.of("admin"), null)))
                .loginConfig(new LoginConfigDef("BASIC", "Corp", null, null));
    }

    @Test
    void descriptorConstraintChallengesForbidsAndAdmits() throws Exception {
        try (var app = new SecureApp(adminApp().build())) {
            var anon = app.get("/admin/x");
            assertEquals(401, anon.statusCode());
            assertEquals("Basic realm=\"Corp\", charset=\"UTF-8\"", anon.headers().firstValue("WWW-Authenticate").orElse(""));
            assertEquals(403, app.send("GET", "/admin/x", "bob:b-secret").statusCode());
            var ok = app.send("GET", "/admin/x", "alice:a-secret");
            assertEquals(200, ok.statusCode());
            assertEquals("user=alice type=BASIC", ok.body());
        }
    }

    @Test
    void theDefaultRealmIsNeutral() throws Exception {
        try (var app = new SecureApp(adminApp().loginConfig(null).build())) {
            assertEquals("Basic realm=\"Restricted\", charset=\"UTF-8\"",
                    app.get("/admin/x").headers().firstValue("WWW-Authenticate").orElse(""));
        }
    }

    @Test
    void constraintAppliesBeforeTheDefaultServlet() throws Exception {
        try (var app = new SecureApp(adminApp().build())) {
            assertEquals(401, app.get("/static/missing.txt").statusCode(), "not 404: checked before the target");
        }
    }

    @Test
    void canonicalPathDrivesTheConstraint() throws Exception {
        try (var app = new SecureApp(adminApp().build())) {
            assertEquals(401, app.get("/%61dmin/x").statusCode());
            assertEquals(401, app.get("/admin;p=1/x").statusCode());
            assertEquals(401, app.get("/admin;jsessionid=abc/x").statusCode());
            var dots = app.get("/open/../admin/x");
            assertTrue(List.of(400, 401, 404).contains(dots.statusCode()), String.valueOf(dots.statusCode()));
            assertFalse(dots.body().contains("user="));
        }
    }

    @Test
    void headIsCheckedAsItsOwnMethod() throws Exception {
        try (var app = new SecureApp(adminApp().denyUncoveredHttpMethods(true).build())) {
            assertEquals(401, app.send("GET", "/h", null).statusCode());
            assertEquals(403, app.send("HEAD", "/h", "alice:a-secret").statusCode(), "HEAD is uncovered, not GET");
        }
    }

    @Test
    void omissionsAndUncoveredMethods() throws Exception {
        try (var log = LogCapture.of(WebAppDeployer.class.getName()); var app = new SecureApp(adminApp().build())) {
            assertEquals(401, app.send("POST", "/form", null).statusCode());
            assertEquals(200, app.send("GET", "/form", null).statusCode(), "GET is uncovered and permitted");
            assertTrue(log.warnings().stream().anyMatch(w -> w.contains("'/form'") && w.contains("[GET]")), log.warnings().toString());
        }
        try (var app = new SecureApp(adminApp().denyUncoveredHttpMethods(true).build())) {
            assertEquals(403, app.send("GET", "/form", "alice:a-secret").statusCode());
            assertEquals(403, app.send("PUT", "/h", "alice:a-secret").statusCode());
        }
    }

    @Test
    void emptyAuthConstraintExcludesEveryone() throws Exception {
        var model = WebAppModel.builder("/").servlet(who("/x"))
                .securityConstraints(List.of(constraint(List.of("/x"), List.of(), List.of(), List.of(), null))).build();
        try (var app = new SecureApp(model)) {
            assertEquals(403, app.send("GET", "/x", "alice:a-secret").statusCode());
            assertEquals(403, app.get("/x").statusCode());
        }
    }

    @Test
    void theDescriptorBeatsTheAnnotationOnAnExactPattern() throws Exception {
        var annotated = new ServletSecurityElement(new HttpConstraintElement(TransportGuarantee.NONE, "admin"));
        var model = WebAppModel.builder("/")
                .servlet(new ServletDecl("who", Who.class, Who::new, List.of("/a", "/b"), Map.of(), -1, false, annotated))
                .securityConstraints(List.of(constraint(List.of("/a"), List.of("GET"), List.of(), null, null))).build();
        try (var app = new SecureApp(model)) {
            assertEquals(200, app.get("/a").statusCode());
            assertEquals(401, app.get("/b").statusCode());
        }
    }

    @Test
    void aForwardIsNotChecked() throws Exception {
        try (var app = new SecureApp(adminApp().build())) {
            var r = app.get("/fwd");
            assertEquals(200, r.statusCode());
            assertEquals("user=null type=null", r.body());
        }
    }

    @Test
    void malformedBasicHeaderIs401Not500() throws Exception {
        try (var app = new SecureApp(adminApp().build())) {
            // "A" * 4000 decodes to NUL bytes (control characters). It stays under chappe's default 8 KiB
            // header limit, which would answer before Foy; the oversize case is covered by BasicAuthenticatorTest.
            for (String h : List.of("Basic %%%%", "Basic", "Basic " + Base64.getEncoder().encodeToString("nocolon".getBytes()),
                    "Basic " + "A".repeat(4000), "Digest x")) {
                var r = app.send(app.request("/admin/x").header("Authorization", h).GET());
                assertEquals(401, r.statusCode(), h);
            }
        }
    }

    @Test
    void credentialsNeverLogged() throws Exception {
        String token = Base64.getEncoder().encodeToString("alice:a-secret".getBytes());
        // "io.vidocq.foy" receives the records of every Foy logger (the bridge logs the failure at
        // ERROR with the exception attached; LogCapture.warnings() alone would see nothing).
        try (var log = LogCapture.of("io.vidocq.foy"); var app = new SecureApp(adminApp().build())) {
            assertEquals(500, app.send("GET", "/boom", "alice:a-secret").statusCode());
            assertFalse(log.thrown().isEmpty(), "the failure was logged");
            List<String> logged = new java.util.ArrayList<>(log.warnings());
            for (Throwable t : log.thrown()) logged.add(String.valueOf(t));
            for (String w : logged) {
                assertFalse(w.contains("a-secret"), w);
                assertFalse(w.contains(token), w);
            }
        }
    }
}
```

- [ ] **Step 2: Run them and confirm they fail.** Run `./mvnw -ntp -pl foy-core install -Dtest=WebSecurityEndToEndTest`. Expected: FAIL, because descriptor constraints are not enforced (`/admin/x` anon → 200).

- [ ] **Step 3: Write the authenticators and the gate.**

`Authenticator.java`:

```java
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
```

`BasicAuthenticator`: add `implements Authenticator`, and:

```java
    @Override public String authType() { return jakarta.servlet.http.HttpServletRequest.BASIC_AUTH; }

    @Override
    public Optional<Identity> authenticate(io.vidocq.foy.internal.bridge.HttpServletRequestImpl req) {
        return tryAuthenticate(req.getHeader("Authorization"));
    }

    @Override
    public void challenge(io.vidocq.foy.internal.bridge.HttpServletRequestImpl req,
                          jakarta.servlet.http.HttpServletResponse res) throws java.io.IOException {
        res.setHeader("WWW-Authenticate", challengeHeaderValue());
        res.sendError(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
    }
```

`DenyingAuthenticator.java`:

```java
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
```

`WebSecurity.java` (the Task 6.5 version; Tasks 6.6 and 6.9 extend `admit`):

```java
package io.vidocq.foy.internal.security;

import io.vidocq.foy.internal.bridge.HttpServletRequestImpl;
import io.vidocq.foy.internal.security.ConstraintTable.Decision;
import io.vidocq.foy.internal.webxml.SecurityDefs.LoginConfigDef;
import io.vidocq.foy.spi.security.IdentityStore;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

/**
 * The security of one web application at request time: its compiled constraints, its
 * authentication mechanism and the embedder's settings. The bridge calls {@link #admit} for every
 * REQUEST dispatch, on the canonical path, before any target. Forward, include, error and async
 * dispatches are never checked again (section 13.8).
 */
public final class WebSecurity {

    private static final System.Logger LOG = System.getLogger(WebSecurity.class.getName());

    private final ConstraintTable constraints;
    private final Authenticator authenticator;
    private final SecuritySettings settings;

    public WebSecurity(ConstraintTable constraints, Authenticator authenticator, SecuritySettings settings) {
        this.constraints = Objects.requireNonNull(constraints, "constraints");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** No constraint, BASIC over the settings' store: a context the deployer did not configure. */
    public static WebSecurity permitAll(SecuritySettings settings) {
        return new WebSecurity(ConstraintTable.EMPTY,
                new BasicAuthenticator(settings.identityStore(), BasicAuthenticator.DEFAULT_REALM), settings);
    }

    /** The mechanism a {@code login-config} names; none or no {@code auth-method} means BASIC (section 13.6). */
    public static Authenticator authenticatorFor(LoginConfigDef loginConfig, IdentityStore store) {
        String method = loginConfig == null || loginConfig.authMethod() == null || loginConfig.authMethod().isBlank()
                ? "BASIC" : loginConfig.authMethod().trim().toUpperCase(Locale.ROOT);
        String realm = loginConfig == null || loginConfig.realmName() == null || loginConfig.realmName().isBlank()
                ? BasicAuthenticator.DEFAULT_REALM : loginConfig.realmName().trim();
        return switch (method) {
            case "BASIC" -> new BasicAuthenticator(store, realm);
            default -> {
                LOG.log(System.Logger.Level.WARNING,
                        "auth-method {0} is not supported; protected resources answer 403", method);
                yield new DenyingAuthenticator(method);
            }
        };
    }

    public ConstraintTable constraints() { return constraints; }
    public Authenticator authenticator() { return authenticator; }
    public SecuritySettings settings() { return settings; }

    /**
     * Applies the constraint of {@code canonicalPath} and the request method. Returns {@code true}
     * when the request proceeds; otherwise the response is produced (401 challenge, 403, or the
     * mechanism's challenge) and {@code false} is returned. Any exception fails closed: the caller
     * does not invoke the target.
     */
    public boolean admit(HttpServletRequestImpl req, HttpServletResponse res, String canonicalPath)
            throws IOException, ServletException {
        Decision decision = constraints.decide(canonicalPath, req.getMethod());
        switch (decision.access()) {
            case UNCHECKED -> { return true; }
            case EXCLUDED -> { res.sendError(HttpServletResponse.SC_FORBIDDEN); return false; }
            case ROLES -> { }
        }
        if (req.identity() == null) {
            authenticator.authenticate(req).ifPresent(id -> req.bindIdentity(id, authenticator.authType()));
        }
        if (req.identity() == null) {
            authenticator.challenge(req, res);
            return false;
        }
        if (!decision.permits(req.identity())) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }
}
```

- [ ] **Step 4: Wire the context, the deployer and the bridge.**
  1. `VidocqServletContext`:

```java
    private volatile io.vidocq.foy.internal.security.WebSecurity security;
    /** The application's security; a permit-all one over {@link #securitySettings()} until the deployer sets it. */
    public io.vidocq.foy.internal.security.WebSecurity security() {
        var s = security;
        return s != null ? s : io.vidocq.foy.internal.security.WebSecurity.permitAll(securitySettings);
    }
    public void setSecurity(io.vidocq.foy.internal.security.WebSecurity security) {
        this.security = java.util.Objects.requireNonNull(security, "security");
    }
```

  2. `ServletDispatcher.Mapping` becomes:

```java
    public record Mapping(UrlPatternMatcher matcher, Servlet servlet, String servletName, boolean asyncSupported) {
        public Mapping {
            Objects.requireNonNull(matcher);
            Objects.requireNonNull(servlet);
            Objects.requireNonNull(servletName);
        }
        public Mapping(UrlPatternMatcher matcher, Servlet servlet, String servletName) {
            this(matcher, servlet, servletName, true);
        }
    }
```

     Then:
     - Remove the `ServletSecurityElement` import.
     - In `WebAppDeployer.initServlets` (~410), drop the `s.security()` argument.
     - `grep -rn "Mapping(" foy-core/src` for any other 5-argument caller and drop the argument there too.
  3. `WebAppDeployer.deploy`: right after `ctx.markInitialized();`, add `ctx.setSecurity(buildSecurity(model, ctx, servlets));` and:

```java
    /**
     * Compiles the application's constraints: the descriptor's first, then each servlet's
     * {@code ServletSecurityElement} (annotation, or {@code setServletSecurity} for a dynamic
     * servlet) on its patterns (section 13.4.1). Without {@code <deny-uncovered-http-methods/>},
     * every uncovered method is reported at WARNING (section 13.8.4).
     */
    private static WebSecurity buildSecurity(WebAppModel model, VidocqServletContext ctx, List<ServletUnit> servlets) {
        var table = ConstraintTable.builder()
                .declaredRoles(ctx.roleMapping().declaredRoles())
                .denyUncoveredHttpMethods(model.denyUncoveredHttpMethods());
        model.securityConstraints().forEach(table::descriptor);
        for (ServletUnit s : servlets) {
            if (s.security() != null) table.servlet(s.patterns(), s.security());
        }
        ConstraintTable built = table.build();
        if (!model.denyUncoveredHttpMethods()) {
            for (String w : built.uncoveredMethodWarnings()) {
                LOG.log(System.Logger.Level.WARNING, "{0}; these methods are not protected "
                        + "(<deny-uncovered-http-methods/> would deny them)", w);
            }
        }
        SecuritySettings settings = ctx.securitySettings();
        return new WebSecurity(built, WebSecurity.authenticatorFor(model.loginConfig(), settings.identityStore()), settings);
    }
```

  4. `ChappeServletBridge`:
     - Remove the `SecurityConstraintEnforcer` import.
     - Add:

```java
    /**
     * Section 13.8: the constraint of the canonical path, for the original request only, before
     * any target (the container default servlet and unmapped paths included). {@code OPTIONS *}
     * names the server, not a resource.
     */
    private boolean admitted(HttpServletRequestImpl req, HttpServletResponseImpl res, String path)
            throws IOException, ServletException {
        return "*".equals(path) || servletContext.security().admit(req, res, path);
    }
```

     - In `handle(Request, List, HttpServletResponseImpl)`, add `final String requestPath = path;` right after the closing brace of the `if ("*".equals(rawPath)) { … } else { … }` block that computes `path`. That is just before `Optional<ServletDispatcher.Mapping> match = dispatcher.find(path);`, so the variable is visible in all three branches and holds the path before welcome-file substitution.
     - In the welcome-redirect branch, insert before `try { res.sendRedirect(...) }`:

```java
                if (!admitted(redirected, res, requestPath)) return completed(redirected, res);
```

     - In the `match.isEmpty()` branch, after `req.accessRequestedSession();` and before `List<Filter> filters = …` (no request listener has fired yet, so nothing needs ending), insert:

```java
            if (!admitted(req, res, path)) return completed(req, res);
```

     - In the main branch, inside the outer `try` that follows `boolean requestDestroyed = false;`, replace everything from `Throwable thrown = null;` to the closing brace of the inner `catch (ServletException | IOException | RuntimeException e) { thrown = e; }` (today: the `SecurityConstraintEnforcer` block, then `invoke`) with:

```java
            Throwable thrown = null;
            boolean allowed = false;
            try {
                // The original path, then the welcome target it resolved to (a constraint on either applies).
                allowed = admitted(req, res, requestPath) && (requestPath.equals(path) || admitted(req, res, path));
                if (allowed) invoke(target, req, res, DispatcherType.REQUEST, requestChain);
            } catch (ServletException | IOException | RuntimeException e) {
                // A failing check fails closed: the target never ran, the error handling below answers.
                thrown = e;
            }
            if (!allowed && thrown == null) {
                // Denied: the mechanism produced the response (401, 403, a challenge or the login page).
                // requestDestroyed fires outside the inner try, so a listener failure is not caught there
                // and fired a second time below.
                requestDestroyed = true;
                registry.fireRequestDestroyed(servletContext, req);
                return completed(req, res);
            }
```

       The rest of the outer `try` (upgrade, `runAsyncCycles`, `requestDestroyed = true; registry.fireRequestDestroyed(…)`, error handling) and the outer `catch (Throwable t)` stay as they are.

  5. Delete `SecurityConstraintEnforcer.java`.
  6. `ServletSecurityEndToEndTest.startWith`:

```java
    private void startWith(HttpServlet servlet, String pattern) {
        var ctx = new VidocqServletContext("/");
        var settings = SecuritySettings.DEFAULTS.withIdentityStore(USERS);
        ctx.setSecuritySettings(settings);
        var element = ClassFileDescriptorReader.read(servlet.getClass())
                .map(WebComponentDescriptor::servletSecurity).orElse(null);
        var table = ConstraintTable.builder();
        if (element != null) table.servlet(List.of(pattern), element);
        ctx.setSecurity(new WebSecurity(table.build(),
                new BasicAuthenticator(USERS, BasicAuthenticator.DEFAULT_REALM), settings));
        var bridge = new ChappeServletBridge(
                new ServletDispatcher(List.of(new ServletDispatcher.Mapping(
                        UrlPatternMatcher.of(pattern), servlet, "S", true))),
                new FilterRegistry(List.of()), ctx, null, "/");
        // ... unchanged
    }
```

- [ ] **Step 5: Run the tests and the gates.**
  1. `./mvnw -ntp install` and `./mvnw -ntp -Ptck -pl foy-tck test`: green.
  2. Run the security TCK classes (log `/tmp/foy-tck-6.5.log`). Expected green:
     - `SecBasicTests` `test1, test2, test3, test4, test6`, with every `_anno` test now green.
     - `MetaDataCompleteTests` `test1, test2, test3, test6`.
     - `AnnotationsTests.test7`.
     - `DenyUncoveredTests` `testPartialDDServlet, testDenySomeUncovered, testExcludeAuthConstraint, testAllMethodsAllowedAnno`, with `testAccessToMethodAllowed` still green.
  3. `SecFormTests` (FORM is still unsupported, so its protected pages answer 403) may lose `test13` until Task 6.6, which follows immediately. Record it in the commit body.
  4. Run `./run-official-tck-servlet6.1.sh --no-install --family spec > /tmp/foy-tck-6.5-spec.log 2>&1`, tally it and join it against the `spec.*` lines of the baseline. The only regression allowed is `SecFormTests` (named in the commit body), and every line reads `reflection=0`.

- [ ] **Step 6: Commit.** Use the message `feat(core): enforce descriptor and annotation constraints before any target`.

---

### Task 6.6: FORM authentication

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/security/FormAuthenticator.java`
- Modify:
  - `foy-core/src/main/java/io/vidocq/foy/internal/security/WebSecurity.java` (`authenticatorFor`, `admit`, new `restoreIdentity`, `loggedIn`, `loggedOut`, `CachedIdentity`)
  - `foy-core/src/main/java/io/vidocq/foy/internal/session/HttpSessionImpl.java` (container notes)
- Test:
  - Create `foy-core/src/test/java/io/vidocq/foy/internal/security/FormAuthenticationEndToEndTest.java`
  - Create `foy-core/src/test/java/io/vidocq/foy/internal/security/FormAuthenticatorTest.java`

**Interfaces:**
- Consumes: `Authenticator`, `WebSecurity` (6.5), `CredentialCheck` (6.1), `HttpServletRequestImpl.changeSessionId()` (existing), `SecuritySettings.rotateSessionIdOnLogin()`.
- Produces:

```java
// HttpSessionImpl: container-private values, not attributes, kept by changeSessionId, cleared on invalidation
public Object note(String key);
public void setNote(String key, Object value);
public Object removeNote(String key);

public final class FormAuthenticator implements Authenticator {
    public static final String LOGIN_ACTION = "/j_security_check";
    static final String IDENTITY_NOTE;      // session note holding a WebSecurity.CachedIdentity
    static final String SAVED_TARGET_NOTE;  // session note holding the saved GET target (String)
    public FormAuthenticator(IdentityStore store, String loginPage, String errorPage);
    public boolean isLoginAction(String canonicalPath);
    void processLogin(HttpServletRequestImpl req, HttpServletResponse res, WebSecurity security) throws IOException, ServletException;
    static String savedTarget(HttpServletRequest req);
    static String safeTarget(String target, String contextPath);
}
// WebSecurity
public void loggedIn(HttpServletRequestImpl req, Identity identity, String authType); // bind, rotate, cache (session-based)
public void loggedOut(HttpServletRequestImpl req);                                    // clear request + cached identity
record CachedIdentity(Identity identity, String authType) {}
```

- [ ] **Step 1: Write the failing tests.**

`FormAuthenticatorTest`:

```java
package io.vidocq.foy.internal.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FormAuthenticatorTest {

    @Test
    void safeTargetKeepsContextPaths() {
        assertEquals("/app/secure/page?x=1", FormAuthenticator.safeTarget("/app/secure/page?x=1", "/app"));
        assertEquals("/secure", FormAuthenticator.safeTarget("/secure", ""));
    }

    @Test
    void safeTargetRefusesForeignTargets() {
        for (String bad : new String[] {null, "http://evil.example/", "//evil.example/x", "/other/x", "/app\\x",
                "/app/x\r\nSet-Cookie: a=b", "/appx"}) {
            assertEquals("/app/", FormAuthenticator.safeTarget(bad, "/app"), String.valueOf(bad));
        }
        assertEquals("/", FormAuthenticator.safeTarget("//evil.example", ""));
    }

    @Test
    void loginActionIsAnySegmentNamedJSecurityCheck() {
        var form = new FormAuthenticator(SecureApp.USERS, "/login", "/error");
        assertTrue(form.isLoginAction("/j_security_check"));
        assertTrue(form.isLoginAction("/sub/j_security_check"));
        assertFalse(form.isLoginAction("/j_security_check2"));
        assertFalse(form.isLoginAction("/xj_security_check"));
    }
}
```

`FormAuthenticationEndToEndTest`:

```java
package io.vidocq.foy.internal.security;

import io.vidocq.foy.internal.LogCapture;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.webxml.SecurityDefs.LoginConfigDef;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static io.vidocq.foy.internal.security.SecureApp.constraint;
import static org.junit.jupiter.api.Assertions.*;

class FormAuthenticationEndToEndTest {

    public static class Page extends HttpServlet {
        @Override protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String p = req.getServletPath();
            var w = resp.getWriter();
            switch (p) {
                case "/login" -> w.write("LOGIN PAGE");
                case "/error" -> w.write("LOGIN ERROR");
                case "/put" -> { req.getSession().setAttribute("cart", "1"); w.write("put"); }
                default -> w.write("user=" + req.getRemoteUser() + " type=" + req.getAuthType()
                        + " cart=" + (req.getSession(false) == null ? null : req.getSession(false).getAttribute("cart")));
            }
        }
    }

    /**
     * The redirect path without the {@code ;jsessionid=} that {@code encodeRedirectURL} appends when
     * the session was created by the login request itself (tracking modes COOKIE and URL, no cookie sent).
     */
    private static String pathOf(URI location) {
        return location.getPath().replaceFirst(";jsessionid=[^/;]*", "");
    }

    private static SecureApp formApp() {
        var model = WebAppModel.builder("/")
                .servlet(new ServletDecl("page", Page.class, Page::new,
                        List.of("/login", "/error", "/put", "/open", "/secure/*"), Map.of(), -1, false))
                .securityConstraints(List.of(constraint(List.of("/secure/*"), List.of(), List.of(), List.of("admin"), null)))
                .loginConfig(new LoginConfigDef("FORM", null, "/login", "/error"))
                .build();
        return new SecureApp(model);
    }

    @Test
    void protectedGetForwardsToTheLoginPage() throws Exception {
        try (var app = formApp()) {
            var r = app.get("/secure/page?x=1");
            assertEquals(200, r.statusCode());
            assertEquals("LOGIN PAGE", r.body());
            assertTrue(r.headers().firstValue("Location").isEmpty());
            assertNotNull(app.sessionId(), "the saved target lives in a session");
        }
    }

    @Test
    void loginRotatesTheSessionAndKeepsItsAttributes() throws Exception {
        try (var app = formApp()) {
            app.get("/put");
            app.get("/secure/page?x=1");
            String before = app.sessionId();
            var login = app.postForm("/j_security_check", "j_username=alice&j_password=a-secret");
            assertEquals(302, login.statusCode());
            URI location = URI.create(login.headers().firstValue("Location").orElseThrow());
            assertEquals("/secure/page", pathOf(location));
            assertEquals("x=1", location.getQuery());
            String after = app.sessionId();
            assertNotEquals(before, after, "session id rotated");
            var page = app.get("/secure/page?x=1");
            assertEquals("user=alice type=FORM cart=1", page.body());
            assertEquals("LOGIN PAGE", app.getWithSession("/secure/page", before).body(),
                    "the old id no longer authenticates");
        }
    }

    @Test
    void wrongPasswordForwardsToTheErrorPage() throws Exception {
        try (var app = formApp()) {
            app.get("/secure/page");
            var r = app.postForm("/j_security_check", "j_username=alice&j_password=nope");
            assertEquals("LOGIN ERROR", r.body());
            assertEquals("LOGIN PAGE", app.get("/secure/page").body());
        }
    }

    @Test
    void theLoginActionIsPostOnly() throws Exception {
        try (var app = formApp()) {
            var r = app.get("/j_security_check?j_username=alice&j_password=a-secret");
            assertEquals(405, r.statusCode());
            assertEquals("POST", r.headers().firstValue("Allow").orElse(""));
            assertEquals("LOGIN PAGE", app.get("/secure/page").body());
        }
    }

    @Test
    void noSavedTargetRedirectsToTheContextRoot() throws Exception {
        try (var app = formApp()) {
            var r = app.send(app.request("/j_security_check")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Referer", "http://evil.example/")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            "j_username=alice&j_password=a-secret&redirect=http%3A%2F%2Fevil.example%2F")));
            assertEquals(302, r.statusCode());
            URI location = URI.create(r.headers().firstValue("Location").orElseThrow());
            assertEquals("127.0.0.1", location.getHost());
            assertEquals("/", pathOf(location));
        }
    }

    @Test
    void protectedPostIsNotReplayed() throws Exception {
        try (var app = formApp()) {
            assertEquals("LOGIN PAGE", app.postForm("/secure/order", "qty=1").body());
            var login = app.postForm("/j_security_check", "j_username=alice&j_password=a-secret");
            assertEquals("/", pathOf(URI.create(login.headers().firstValue("Location").orElseThrow())));
        }
    }

    @Test
    void theIdentityFollowsTheSessionToUnprotectedPages() throws Exception {
        try (var app = formApp()) {
            app.get("/secure/page");
            app.postForm("/j_security_check", "j_username=alice&j_password=a-secret");
            assertEquals("user=alice type=FORM cart=null", app.get("/open").body());
        }
    }

    @Test
    void aUserWithoutTheRoleGets403AfterLogin() throws Exception {
        try (var app = formApp()) {
            app.get("/secure/page");
            app.postForm("/j_security_check", "j_username=bob&j_password=b-secret");
            assertEquals(403, app.get("/secure/page").statusCode());
        }
    }

    @Test
    void aBasicHeaderDoesNotAuthenticateAFormApplication() throws Exception {
        try (var app = formApp()) {
            assertEquals("LOGIN PAGE", app.send("GET", "/secure/page", "alice:a-secret").body());
        }
    }

    @Test
    void passwordNeverLogged() throws Exception {
        // Every Foy logger (bridge, deployer, CredentialCheck, WebSecurity), every level's attached exception.
        try (var log = LogCapture.of("io.vidocq.foy"); var app = formApp()) {
            app.get("/secure/page");
            assertEquals(302, app.postForm("/j_security_check", "j_username=alice&j_password=a-secret").statusCode());
            assertEquals("LOGIN ERROR",
                    app.postForm("/j_security_check", "j_username=alice&j_password=wrong-secret").body());
            List<String> logged = new java.util.ArrayList<>(log.warnings());
            for (Throwable t : log.thrown()) logged.add(String.valueOf(t));
            for (String w : logged) {
                assertFalse(w.contains("a-secret") || w.contains("wrong-secret"), w);
            }
        }
    }
}
```

- [ ] **Step 2: Run them and confirm they fail.** Run `./mvnw -ntp -pl foy-core install -Dtest='FormAuthenticatorTest,FormAuthenticationEndToEndTest'`. Expected: compilation FAIL (`FormAuthenticator` missing).

- [ ] **Step 3: Add the session notes** to `HttpSessionImpl`, next to `attributes`:

```java
    /**
     * Container-private values (authentication state). They are not attributes: no listener, no
     * {@code getAttributeNames}, out of the application's reach. They stay with the session object
     * across {@code changeSessionId} and are dropped on invalidation.
     */
    private final ConcurrentMap<String, Object> notes = new ConcurrentHashMap<>();

    public Object note(String key) { return notes.get(key); }
    public void setNote(String key, Object value) { notes.put(key, value); }
    public Object removeNote(String key) { return notes.remove(key); }
```

In `completeInvalidation()`, where the attributes are cleared, add `notes.clear();`.

- [ ] **Step 4: Implement `FormAuthenticator`.**

```java
package io.vidocq.foy.internal.security;

import io.vidocq.foy.internal.bridge.HttpServletRequestImpl;
import io.vidocq.foy.internal.session.HttpSessionImpl;
import io.vidocq.foy.spi.security.Identity;
import io.vidocq.foy.spi.security.IdentityStore;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Form based authentication (Servlet 6.1 sections 13.6.3 and 13.6.3.1).
 *
 * <ul>
 *   <li><b>Challenge.</b> The target of a GET or HEAD is saved server-side in the session. It is
 *       rebuilt from the request URI (without {@code ;jsessionid}) and the query, never from a
 *       parameter or a header. The request is then forwarded to {@code form-login-page}, so the
 *       browser keeps the original URL. Any other method saves nothing: a protected POST is not
 *       replayed.</li>
 *   <li><b>{@code POST .../j_security_check}.</b> On success, the identity is bound, the session id
 *       rotated, the identity cached in the session and the client redirected (302) to the saved
 *       target, or to the context root when none is saved. On failure, the request is forwarded to
 *       {@code form-error-page}. Any other method gets 405.</li>
 * </ul>
 */
public final class FormAuthenticator implements Authenticator {

    public static final String LOGIN_ACTION = "/j_security_check";
    static final String IDENTITY_NOTE = FormAuthenticator.class.getName() + ".identity";
    static final String SAVED_TARGET_NOTE = FormAuthenticator.class.getName() + ".savedTarget";

    private final IdentityStore store;
    private final String loginPage;
    private final String errorPage;

    public FormAuthenticator(IdentityStore store, String loginPage, String errorPage) {
        this.store = Objects.requireNonNull(store, "store");
        this.loginPage = page(loginPage);
        this.errorPage = page(errorPage);
    }

    private static String page(String p) {
        if (p == null || p.isBlank()) return null;
        String t = p.trim();
        return t.startsWith("/") ? t : "/" + t;
    }

    @Override public String authType() { return HttpServletRequest.FORM_AUTH; }
    @Override public Optional<Identity> authenticate(HttpServletRequestImpl req) { return Optional.empty(); }
    @Override public boolean sessionBased() { return true; }

    public boolean isLoginAction(String canonicalPath) {
        return canonicalPath.endsWith(LOGIN_ACTION);
    }

    @Override
    public void challenge(HttpServletRequestImpl req, HttpServletResponse res) throws IOException, ServletException {
        if (loginPage == null) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        String method = req.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method)) {
            ((HttpSessionImpl) req.getSession(true)).setNote(SAVED_TARGET_NOTE, savedTarget(req));
        } else if (req.getSession(false) instanceof HttpSessionImpl session) {
            session.removeNote(SAVED_TARGET_NOTE);
        }
        forward(req, res, loginPage);
    }

    void processLogin(HttpServletRequestImpl req, HttpServletResponse res, WebSecurity security)
            throws IOException, ServletException {
        if (!"POST".equals(req.getMethod())) {
            res.setHeader("Allow", "POST");
            res.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        String user = req.getParameter("j_username");
        String password = req.getParameter("j_password");
        Optional<Identity> identity = CredentialCheck.validate(store, user,
                password == null ? null : password.toCharArray());
        if (identity.isEmpty()) {
            if (errorPage == null) res.sendError(HttpServletResponse.SC_FORBIDDEN);
            else forward(req, res, errorPage);
            return;
        }
        security.loggedIn(req, identity.get(), authType());
        Object saved = req.getSession(false) instanceof HttpSessionImpl s ? s.removeNote(SAVED_TARGET_NOTE) : null;
        String target = safeTarget(saved instanceof String t ? t : null, req.getContextPath());
        res.sendRedirect(res.encodeRedirectURL(target));
    }

    /** The request URI without any {@code ;jsessionid=} parameter, plus the query: a context path. */
    static String savedTarget(HttpServletRequest req) {
        String uri = req.getRequestURI().replaceAll("(?i);jsessionid=[^;/?#]*", "");
        String query = req.getQueryString();
        return query == null ? uri : uri + '?' + query;
    }

    /**
     * {@code target} when it is a path of this context with no scheme, authority, backslash or
     * control character; the context root otherwise. A defence in depth: the target is built
     * server-side.
     */
    static String safeTarget(String target, String contextPath) {
        String root = contextPath + "/";
        if (target == null || target.startsWith("//") || target.indexOf('\\') >= 0) return root;
        if (!(target.equals(contextPath) && !contextPath.isEmpty()) && !target.startsWith(root)) return root;
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c < 0x20 || c == 0x7f) return root;
        }
        return target;
    }

    private static void forward(HttpServletRequestImpl req, HttpServletResponse res, String page)
            throws IOException, ServletException {
        RequestDispatcher rd = req.getServletContext().getRequestDispatcher(page);
        if (rd == null) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        rd.forward(req, res);
    }
}
```

- [ ] **Step 5: Extend `WebSecurity`.**
  1. In `authenticatorFor`, add before `default`:

```java
            case "FORM" -> {
                if (loginConfig.formLoginPage() == null) {
                    LOG.log(System.Logger.Level.WARNING,
                            "FORM login-config without form-login-page; protected resources answer 403");
                }
                yield new FormAuthenticator(store, loginConfig.formLoginPage(), loginConfig.formErrorPage());
            }
```

  2. Replace the start of `admit` (before `constraints.decide`):

```java
        restoreIdentity(req);
        if (authenticator instanceof FormAuthenticator form && form.isLoginAction(canonicalPath)) {
            form.processLogin(req, res, this);
            return false;
        }
```

  3. Add:

```java
    /** A session-based identity (section 13.7), cached by {@link #loggedIn}. */
    record CachedIdentity(io.vidocq.foy.spi.security.Identity identity, String authType) {}

    /** Binds the identity cached in the session, if any, to a request that has none yet. */
    void restoreIdentity(HttpServletRequestImpl req) {
        if (!authenticator.sessionBased() || req.identity() != null) return;
        if (req.getSession(false) instanceof io.vidocq.foy.internal.session.HttpSessionImpl s
                && s.note(FormAuthenticator.IDENTITY_NOTE) instanceof CachedIdentity c) {
            req.bindIdentity(c.identity(), c.authType());
        }
    }

    /**
     * A successful login (FORM action or {@code HttpServletRequest.login}). It binds the identity
     * to the request and changes the id of an existing session (session fixation;
     * {@link SecuritySettings#rotateSessionIdOnLogin}). For a session-based mechanism, it then
     * caches the identity in the session, creating one if needed.
     */
    public void loggedIn(HttpServletRequestImpl req, io.vidocq.foy.spi.security.Identity identity, String authType) {
        req.bindIdentity(identity, authType);
        if (req.getSession(false) != null && settings.rotateSessionIdOnLogin()) req.changeSessionId();
        if (authenticator.sessionBased()) {
            ((io.vidocq.foy.internal.session.HttpSessionImpl) req.getSession(true))
                    .setNote(FormAuthenticator.IDENTITY_NOTE, new CachedIdentity(identity, authType));
        }
    }

    /** {@code HttpServletRequest.logout}: the request's and the session's identity are cleared; the session stays. */
    public void loggedOut(HttpServletRequestImpl req) {
        req.bindIdentity(null, null);
        if (req.getSession(false) instanceof io.vidocq.foy.internal.session.HttpSessionImpl s) {
            s.removeNote(FormAuthenticator.IDENTITY_NOTE);
        }
    }
```

- [ ] **Step 6: Run the tests and the gates.**
  1. `./mvnw -ntp install` and `./mvnw -ntp -Ptck -pl foy-tck test`: green.
  2. Run the security TCK classes (log `/tmp/foy-tck-6.6.log`). Expected green: `SecFormTests` `test1, test3, test4, test6, test10, test11, test14, test1_anno, test3_anno, test4_anno, test6_anno, test14_anno`. `test17` may already pass (identity restored, then `logout()` clears it).
  3. `test13` is green again, and every pass from Task 6.5 is kept.
  4. `--family spec` tallied and joined against the baseline: zero regression, `reflection=0`.

- [ ] **Step 7: Commit.** Use the message `feat(core): FORM authentication with server-side saved target and session id rotation`.

---

### Task 6.7: Programmatic `login`, `logout`, `authenticate` per configured mechanism

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java` (`authenticate`/`login`/`logout`/`identityStore()` from Task 6.1)
- Test: create `foy-core/src/test/java/io/vidocq/foy/internal/security/ProgrammaticSecurityEndToEndTest.java`

**Interfaces:**
- Consumes: `VidocqServletContext.security()`, `WebSecurity.authenticator()/settings()/loggedIn()/loggedOut()` (6.5, 6.6), `Authenticator.supportsPasswordLogin()`, `CredentialCheck.validate`.
- Produces:
  - `login` authType = the configured mechanism's `authType()`.
  - `logout` delegates to `WebSecurity.loggedOut`.
  - `authenticate` throws `IllegalStateException` on a committed response.

- [ ] **Step 1: Write the failing tests.**

```java
package io.vidocq.foy.internal.security;

import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.webxml.SecurityDefs.LoginConfigDef;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProgrammaticSecurityEndToEndTest {

    public static class Ops extends HttpServlet {
        @Override protected void service(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            var w = new StringBuilder();
            switch (req.getServletPath()) {
                case "/login" -> {
                    req.getSession().setAttribute("cart", "1");
                    String before = req.getSession().getId();
                    req.login("alice", "a-secret");
                    w.append("user=").append(req.getRemoteUser()).append(" type=").append(req.getAuthType())
                            .append(" rotated=").append(!before.equals(req.getSession().getId()));
                }
                case "/twice" -> {
                    req.login("alice", "a-secret");
                    try { req.login("alice", "a-secret"); w.append("no exception"); }
                    catch (ServletException e) { w.append("second login refused"); }
                }
                case "/session" -> { req.getSession(); w.append("session"); }
                case "/bad" -> {
                    try { req.login("alice", "wrong-secret"); w.append("no exception"); }
                    catch (ServletException e) {
                        w.append("refused:").append(e.getMessage().contains("wrong-secret")).append(':').append(e.getMessage());
                    }
                }
                case "/logout" -> {
                    // FORM restores the identity cached by the previous /login request.
                    if (req.getRemoteUser() == null) req.login("alice", "a-secret");
                    String id = req.getSession().getId();
                    req.logout();
                    w.append("user=").append(req.getRemoteUser()).append(" type=").append(req.getAuthType())
                            .append(" principal=").append(req.getUserPrincipal())
                            .append(" sameSession=").append(id.equals(req.getSession(false).getId()))
                            .append(" cart=").append(req.getSession(false).getAttribute("cart"));
                }
                case "/authenticate" -> {
                    if (!req.authenticate(resp)) return;
                    w.append("authenticated=").append(req.getRemoteUser());
                }
                case "/after-login" -> {
                    req.login("alice", "a-secret");
                    w.append("authenticate=").append(req.authenticate(resp));
                }
                case "/committed" -> {
                    resp.getWriter().write("x");
                    resp.flushBuffer();
                    try { req.authenticate(resp); } catch (IllegalStateException e) { resp.getWriter().write(" ISE"); }
                    return;
                }
                default -> w.append("user=").append(req.getRemoteUser());
            }
            resp.getWriter().write(w.toString());
        }
    }

    private static SecureApp app(String authMethod) {
        var model = WebAppModel.builder("/")
                .servlet(new ServletDecl("ops", Ops.class, Ops::new,
                        List.of("/login", "/session", "/twice", "/bad", "/logout", "/authenticate", "/after-login",
                                "/committed", "/whoami", "/loginpage"), Map.of(), -1, false))
                .loginConfig(new LoginConfigDef(authMethod, null, "/loginpage", "/loginpage"))
                .build();
        return new SecureApp(model);
    }

    @Test
    void loginUsesTheConfiguredMechanismAndRotatesAnExistingSession() throws Exception {
        try (var app = app("FORM")) {
            assertEquals("user=alice type=FORM rotated=true", app.get("/login").body());
            assertEquals("user=alice", app.get("/whoami").body(), "FORM: the identity is kept in the session");
        }
        try (var app = app("BASIC")) {
            assertEquals("user=alice type=BASIC rotated=true", app.get("/login").body());
            assertEquals("user=null", app.get("/whoami").body(), "BASIC: request-scoped");
        }
    }

    @Test
    void loginRotatesAnExistingSession() throws Exception {
        try (var app = app("FORM")) {
            app.get("/session");
            String before = app.sessionId();
            assertNotNull(before);
            app.get("/login");
            String after = app.sessionId();
            assertNotEquals(before, after, "the client received the new id");
            assertEquals("user=alice", app.get("/whoami").body());
            assertEquals("user=null", app.getWithSession("/whoami", before).body(), "the old id is dead");
        }
    }

    @Test
    void loginTwiceAndWrongPasswordThrowWithoutEchoingThePassword() throws Exception {
        try (var app = app("BASIC")) {
            assertEquals("second login refused", app.get("/twice").body());
            assertEquals("refused:false:login failed", app.get("/bad").body());
        }
    }

    @Test
    void logoutClearsTheIdentityButKeepsTheSession() throws Exception {
        try (var app = app("FORM")) {
            app.get("/login");
            assertEquals("user=null type=null principal=null sameSession=true cart=1", app.get("/logout").body());
            assertEquals("user=null", app.get("/whoami").body());
        }
    }

    @Test
    void authenticateFollowsTheMechanism() throws Exception {
        try (var app = app("BASIC")) {
            var challenge = app.get("/authenticate");
            assertEquals(401, challenge.statusCode());
            assertTrue(challenge.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Basic realm="));
            assertEquals("authenticated=alice", app.send("GET", "/authenticate", "alice:a-secret").body());
            assertEquals("authenticate=true", app.get("/after-login").body());
            assertEquals("x ISE", app.get("/committed").body());
        }
        try (var app = app("FORM")) {
            assertEquals("user=null", app.get("/authenticate").body(), "forwarded to the login page (/loginpage)");
        }
    }

    @Test
    void loginUnderAnUnsupportedMechanismThrows() throws Exception {
        try (var app = app("CLIENT-CERT")) {
            assertEquals("refused:false:the configured authentication mechanism does not support username/password login",
                    app.get("/bad").body());
        }
    }
}
```

`rotated=true` proves the id change inside the request. `loginRotatesAnExistingSession` proves that a session created by an earlier request gets a new id, that the client receives it, and that the old id no longer authenticates.

- [ ] **Step 2: Run them and confirm they fail.** Run `./mvnw -ntp -pl foy-core install -Dtest=ProgrammaticSecurityEndToEndTest`. Expected: FAIL (`type=BASIC` under FORM; `/whoami` null after a FORM `login()`; logout leaves the cached identity).

- [ ] **Step 3: Implement.** Replace the Task 6.1 methods of `HttpServletRequestImpl`:

```java
    @Override public boolean authenticate(jakarta.servlet.http.HttpServletResponse response)
            throws IOException, ServletException {
        if (identity != null) return true;
        if (response.isCommitted()) throw new IllegalStateException("the response is already committed");
        var security = security();
        var mechanism = security.authenticator();
        var found = mechanism.authenticate(this);
        if (found.isPresent()) {
            bindIdentity(found.get(), mechanism.authType());
            return true;
        }
        mechanism.challenge(this, response);
        return false;
    }
    @Override public void login(String username, String password) throws ServletException {
        if (identity != null) throw new ServletException("an identity is already established for this request");
        var security = security();
        var mechanism = security.authenticator();
        if (!mechanism.supportsPasswordLogin()) {
            throw new ServletException("the configured authentication mechanism does not support username/password login");
        }
        Identity found = io.vidocq.foy.internal.security.CredentialCheck.validate(
                security.settings().identityStore(), username, password == null ? null : password.toCharArray())
                .orElse(null);
        if (found == null) throw new ServletException("login failed");
        security.loggedIn(this, found, mechanism.authType());
    }
    @Override public void logout() { security().loggedOut(this); }

    private io.vidocq.foy.internal.security.WebSecurity security() {
        return servletContext instanceof VidocqServletContext v ? v.security()
                : io.vidocq.foy.internal.security.WebSecurity.permitAll(
                        io.vidocq.foy.internal.security.SecuritySettings.DEFAULTS);
    }
```

Remove the now unused private `identityStore()` helper.

- [ ] **Step 4: Run the tests and the gates.**
  1. `./mvnw -ntp install` and `./mvnw -ntp -Ptck -pl foy-tck test`: green.
  2. Run the security TCK classes (log `/tmp/foy-tck-6.7.log`). Expected green: `SecFormTests` `test16, test17, test18`. Only `test2`/`test2_anno` stay red in `SecFormTests` (25 run, 2 bad).
  3. `--family spec` joined against the baseline: zero regression, `reflection=0`. `spec.*` is now 196/207 with 7 skipped. The only security failures left are the two CLIENT-CERT tests and SecForm `test2`/`test2_anno`.

- [ ] **Step 5: Commit.** Use the message `feat(core): programmatic login, logout and authenticate follow the configured mechanism`.

---

### Task 6.8: `setServletSecurity`, `declareRoles` and run-as metadata

**Files:**
- Modify:
  - `foy-core/src/main/java/io/vidocq/foy/internal/container/DynamicServletRegistration.java` (`setServletSecurity`, ~118-121)
  - `foy-core/src/main/java/io/vidocq/foy/internal/container/VidocqServletContext.java` (`declareRoles` ~810, descriptor-pattern set, `registeredServletSecurity`)
  - `foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppModel.java` (`runAsRoles` component plus Builder)
  - `foy-core/src/main/java/io/vidocq/foy/internal/boot/DescriptorMerger.java` (`mergeServlets`: run-as)
  - `foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppDeployer.java` (`newContext`, `registerStatic`, `deploy`, `buildSecurity`)
- Test:
  - Create `foy-core/src/test/java/io/vidocq/foy/internal/security/ServletSecurityRegistrationEndToEndTest.java`
  - Modify `foy-core/src/test/java/io/vidocq/foy/internal/boot/DescriptorMergerTest.java`

**Interfaces:**
- Consumes: `ConstraintTable.Builder.servlet` (6.4), `RoleMapping.withDeclaredRoles` (6.3), `buildSecurity` (6.5).
- Produces:

```java
// VidocqServletContext
public void setDescriptorConstrainedPatterns(Set<String> patterns);
public boolean isDescriptorConstrained(String pattern);
public ServletSecurityElement registeredServletSecurity(String servletName); // set through setServletSecurity, else null
public Set<String> programmaticDeclaredRoles();
// WebAppModel: new last component Map<String, String> runAsRoles; Builder.runAsRole(String servletName, String role)
```

- [ ] **Step 1: Write the failing tests.**

```java
package io.vidocq.foy.internal.security;

import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import jakarta.servlet.HttpConstraintElement;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.annotation.ServletSecurity.TransportGuarantee;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.vidocq.foy.internal.security.SecureApp.constraint;
import static org.junit.jupiter.api.Assertions.*;

class ServletSecurityRegistrationEndToEndTest {

    public static class Hello extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("hello " + req.getRemoteUser());
        }
    }

    @Test
    void setServletSecurityAppliesToTheMappingsTheDescriptorDoesNotConstrain() throws Exception {
        var conflicts = new AtomicReference<Set<String>>();
        var nullThrows = new AtomicReference<Boolean>();
        ServletContainerInitializer sci = (classes, ctx) -> {
            var reg = ctx.addServlet("dyn", new Hello());
            reg.addMapping("/dyn", "/dd");
            try { reg.setServletSecurity(null); nullThrows.set(false); }
            catch (IllegalArgumentException e) { nullThrows.set(true); }
            conflicts.set(reg.setServletSecurity(
                    new ServletSecurityElement(new HttpConstraintElement(TransportGuarantee.NONE, "admin"))));
            ctx.declareRoles("auditor");
        };
        var model = WebAppModel.builder("/").initializer(sci)
                .securityConstraints(List.of(constraint(List.of("/dd"), List.of("GET"), List.of(), null, null),
                        constraint(List.of("/star"), List.of(), List.of(), List.of("*"), null)))
                .servlet(new ServletDecl("star", Hello.class, Hello::new, List.of("/star"), Map.of(), -1, false))
                .build();
        var store = InMemoryIdentityStore.builder().user("ann", "x".toCharArray(), "auditor").build();
        try (var app = new SecureApp(model, SecuritySettings.DEFAULTS.withIdentityStore(store))) {
            assertTrue(nullThrows.get());
            assertEquals(Set.of("/dd"), conflicts.get());
            assertEquals(401, app.get("/dyn").statusCode());
            assertEquals(200, app.get("/dd").statusCode(), "the descriptor wins on its exact pattern");
            assertEquals(200, app.send("GET", "/star", "ann:x").statusCode(), "* covers a role declared by declareRoles");
            var ctx = app.deployment.servletContext();
            var reg = (jakarta.servlet.ServletRegistration.Dynamic) ctx.getServletRegistration("dyn");
            assertThrows(IllegalStateException.class, () -> reg.setServletSecurity(
                    new ServletSecurityElement(new HttpConstraintElement())));
            assertThrows(IllegalStateException.class, () -> ctx.declareRoles("late"));
        }
    }

    @Test
    void runAsIsReportedByTheRegistration() throws Exception {
        var model = WebAppModel.builder("/")
                .servlet(new ServletDecl("ra", Hello.class, Hello::new, List.of("/ra"), Map.of(), -1, false))
                .runAsRole("ra", "admin").build();
        try (var app = new SecureApp(model)) {
            assertEquals("admin", app.deployment.servletContext().getServletRegistration("ra").getRunAsRole());
        }
    }
}
```

`DescriptorMergerTest`, new test `runAsComesFromTheDescriptorThenTheAnnotation`:
- web.xml `<run-as><role-name>x</role-name></run-as>` on servlet A gives `model.runAsRoles().get("A") == "x"`.
- A servlet class annotated `@jakarta.annotation.security.RunAs("y")`, declared without `run-as`, gives `"y"`.
- Under `metadata-complete="true"`, the annotated one gives no entry.

`Deployment.servletContext()` exists and returns the `VidocqServletContext` used above.

- [ ] **Step 2: Run them and confirm they fail.** Run `./mvnw -ntp -pl foy-core install -Dtest='ServletSecurityRegistrationEndToEndTest,DescriptorMergerTest'`. Expected: FAIL (`setServletSecurity` returns an empty set and its element is ignored for static units; `runAsRole` missing).

- [ ] **Step 3: Implement.**
  1. `DynamicServletRegistration`:

```java
    /**
     * Section 13.4.2 and the {@code ServletRegistration.Dynamic} Javadoc. The element applies to
     * this servlet's mappings, including ones added later, and overrides its {@code @ServletSecurity}.
     * The returned patterns are already the exact target of a descriptor {@code security-constraint}
     * and are left unaffected.
     */
    @Override public Set<String> setServletSecurity(ServletSecurityElement constraint) {
        if (constraint == null) throw new IllegalArgumentException("constraint is null");
        if (owner != null && owner.isInitializedInternal()) {
            throw new IllegalStateException("the ServletContext is already initialized");
        }
        this.security = constraint;
        Set<String> conflicts = new LinkedHashSet<>();
        if (owner != null) for (String p : mappings) if (owner.isDescriptorConstrained(p)) conflicts.add(p);
        return conflicts;
    }
```

  2. `VidocqServletContext`:

```java
    private volatile Set<String> descriptorConstrainedPatterns = Set.of();
    public void setDescriptorConstrainedPatterns(Set<String> patterns) {
        this.descriptorConstrainedPatterns = Set.copyOf(patterns);
    }
    public boolean isDescriptorConstrained(String pattern) { return descriptorConstrainedPatterns.contains(pattern); }

    /** The element given to {@code setServletSecurity} on the registration of {@code servletName}, else {@code null}. */
    public jakarta.servlet.ServletSecurityElement registeredServletSecurity(String servletName) {
        var d = dynamicServlets.get(servletName);
        if (d != null && d.getServletSecurity() != null) return d.getServletSecurity();
        var s = staticServlets.get(servletName);
        return s == null ? null : s.getServletSecurity();
    }

    private final Set<String> programmaticDeclaredRoles = new java.util.LinkedHashSet<>();
    public Set<String> programmaticDeclaredRoles() { return Set.copyOf(programmaticDeclaredRoles); }

    @Override public void declareRoles(String... roleNames) {
        if (programmaticListenerActive) throw programmaticForbidden();
        if (initialized) throw alreadyInitialized();
        for (String r : roleNames) {
            if (r == null || r.isEmpty()) throw new IllegalArgumentException("a role name is null or empty");
        }
        programmaticDeclaredRoles.addAll(java.util.List.of(roleNames));
    }
```

  3. `WebAppModel`:
     - Append the component `Map<String, String> runAsRoles` (compact constructor: `runAsRoles = copyOf(runAsRoles);`).
     - Builder: `private final Map<String, String> runAsRoles = new LinkedHashMap<>();` and

```java
        public Builder runAsRole(String servletName, String role) {
            runAsRoles.put(Objects.requireNonNull(servletName, "servletName"), Objects.requireNonNull(role, "role"));
            return this;
        }
```

     - `build()` passes `runAsRoles` last.
  4. `DescriptorMerger.mergeServlets`:
     - In the web.xml loop: `String runAs = def.runAs() != null ? def.runAs() : classDescriptor == null ? null : classDescriptor.runAs(); if (runAs != null) target.runAsRole(def.name(), runAs);`.
     - In the annotated-only loop: `String runAs = descriptor(factory, a.type(), "servlet", a.name()).runAs(); if (runAs != null) target.runAsRole(a.name(), runAs);`. This is the descriptor already read for `declaredRoles` in Task 6.3: read it once into a local.
  5. `WebAppDeployer`:
     - In `newContext`, add:

```java
        Set<String> constrained = new LinkedHashSet<>();
        for (var c : model.securityConstraints()) for (var col : c.collections()) constrained.addAll(col.urlPatterns());
        ctx.setDescriptorConstrainedPatterns(constrained);
```

     - In `registerStatic`, keep the returned registration: `var reg = ctx.registerStaticServlet(...); String runAs = model.runAsRoles().get(s.name()); if (runAs != null) reg.setRunAsRole(runAs);`.
     - In `deploy`, just before `ctx.setSecurity(buildSecurity(...))`: `ctx.setRoleMapping(ctx.roleMapping().withDeclaredRoles(ctx.programmaticDeclaredRoles()));`.
     - In `buildSecurity`, the loop becomes:

```java
        for (ServletUnit s : servlets) {
            ServletSecurityElement element = ctx.registeredServletSecurity(s.name());
            if (element == null) element = s.security();
            if (element != null) table.servlet(s.patterns(), element);
        }
```

- [ ] **Step 4: Run the tests and the gates.**
  1. `./mvnw -ntp install` and `./mvnw -ntp -Ptck -pl foy-tck test`: green.
  2. Run the security TCK classes, which must be unchanged from Task 6.7.
  3. Run `./run-official-tck-servlet6.1.sh --no-install -Dtest='RegistrationTests,ServletContextTests' > /tmp/foy-tck-6.8.log 2>&1` and tally it. The simple names select five classes, which must read exactly their baseline lines:
     - `servlet.tck.api.jakarta_servlet.registration.RegistrationTests 10 0 0`
     - `servlet.tck.pluggability.api.jakarta_servlet.registration.RegistrationTests 10 0 0`
     - `servlet.tck.api.jakarta_servlet.servletcontext.ServletContextTests 28 0 0`
     - `servlet.tck.pluggability.api.jakarta_servlet.servletcontext.ServletContextTests 24 0 0`
     - `servlet.tck.spec.servletcontext.ServletContextTests 1 0 0`

- [ ] **Step 5: Commit.** Use the message `feat(core): setServletSecurity, declareRoles and run-as follow the specification`.

---

### Task 6.9: Transport guarantee

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/security/WebSecurity.java` (`admit`, new `refuseInsecure`, `isPlainHost`)
- Test:
  - Create `foy-core/src/test/java/io/vidocq/foy/internal/security/TransportGuaranteeTest.java` (unit, fake chappe request)
  - Create `foy-core/src/test/java/io/vidocq/foy/internal/security/TransportGuaranteeEndToEndTest.java`

**Interfaces:**
- Consumes: `Decision.confidential()` (6.4), `SecuritySettings.confidentialPort()` (6.1), `HttpServletRequestImpl.isSecure()/getServerName()/getRequestURI()/getQueryString()` (existing).
- Produces: `static boolean WebSecurity.isPlainHost(String host)`. A confidential decision on a non-secure request answers 403, or 302 `https://<host>[:<port>]<uri>[?<query>]` when `confidentialPort` is set. The check runs before authentication.

- [ ] **Step 1: Write the failing tests.**

`TransportGuaranteeTest` (mirror `RequestIdentityTest.FakeRequest` for the chappe `Request`):

```java
package io.vidocq.foy.internal.security;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.foy.internal.bridge.HttpServletRequestImpl;
import io.vidocq.foy.internal.bridge.HttpServletResponseImpl;
import io.vidocq.foy.internal.container.VidocqServletContext;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static io.vidocq.foy.internal.security.SecureApp.constraint;
import static org.junit.jupiter.api.Assertions.*;

class TransportGuaranteeTest {

    record FakeRequest(boolean isSecure, String host) implements Request {
        @Override public HttpVersion version() { return HttpVersion.HTTP_1_1; }
        @Override public InetSocketAddress remoteAddress() { return new InetSocketAddress("127.0.0.1", 40000); }
        @Override public InetSocketAddress localAddress() { return new InetSocketAddress("127.0.0.1", 8080); }
        @Override public HttpMethod method() { return HttpMethod.GET; }
        @Override public URI uri() { return URI.create("http://localhost/conf/x?y=1"); }
        @Override public String path() { return "/conf/x"; }
        @Override public String query() { return "y=1"; }
        @Override public Headers headers() { return Headers.of("Host", host); }
        @Override public Body body() { return Body.empty(); }
        @Override public Map<String, String> pathParams() { return Map.of(); }
        @Override public Map<String, String> queryParams() { return Map.of(); }
    }

    private static WebSecurity security(int port) {
        var settings = SecuritySettings.DEFAULTS.withConfidentialPort(port);
        var table = ConstraintTable.builder()
                .descriptor(constraint(List.of("/conf/*"), List.of(), List.of(), null, "CONFIDENTIAL")).build();
        return new WebSecurity(table, new BasicAuthenticator(settings.identityStore(), "r"), settings);
    }

    private static HttpServletRequestImpl request(boolean secure, String host) {
        return new HttpServletRequestImpl(new FakeRequest(secure, host), new VidocqServletContext("/"), "", "/conf", "/x");
    }

    @Test
    void secureRequestsProceed() throws Exception {
        assertTrue(security(-1).admit(request(true, "example.org"), new HttpServletResponseImpl(), "/conf/x"));
    }

    @Test
    void plainRequestsAreRefusedWithoutAPort() throws Exception {
        var res = new HttpServletResponseImpl();
        assertFalse(security(-1).admit(request(false, "example.org"), res, "/conf/x"));
        assertEquals(403, res.getStatus());
    }

    @Test
    void plainRequestsAreRedirectedToTheConfiguredPort() throws Exception {
        var res = new HttpServletResponseImpl();
        assertFalse(security(8443).admit(request(false, "example.org:8080"), res, "/conf/x"));
        assertEquals(302, res.getStatus());
        assertEquals("https://example.org:8443/conf/x?y=1", res.getHeader("Location"));
    }

    @Test
    void aHostileHostHeaderIsRefusedNotRedirected() throws Exception {
        for (String host : List.of("evil.example/@x", "a b", "user@evil.example", "[::1]:8080")) {
            var res = new HttpServletResponseImpl();
            assertFalse(security(8443).admit(request(false, host), res, "/conf/x"), host);
            assertEquals(403, res.getStatus(), host);
        }
    }

    @Test
    void plainHosts() {
        assertTrue(WebSecurity.isPlainHost("example.org"));
        assertTrue(WebSecurity.isPlainHost("127.0.0.1"));
        assertFalse(WebSecurity.isPlainHost(""));
        assertFalse(WebSecurity.isPlainHost(null));
        assertFalse(WebSecurity.isPlainHost("a/b"));
    }
}
```

No binding is needed: `HttpServletResponseImpl.sendRedirect` keeps an absolute location as it is (`toAbsoluteRedirectUrl` returns it unchanged when no request is bound, and when the URI is already absolute).

`TransportGuaranteeEndToEndTest`, over real HTTP (servlet `Who` from Task 6.5 mapped to both patterns):

```java
package io.vidocq.foy.internal.security;

import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.vidocq.foy.internal.security.SecureApp.constraint;
import static org.junit.jupiter.api.Assertions.*;

class TransportGuaranteeEndToEndTest {

    private static WebAppModel model(String guarantee) {
        return WebAppModel.builder("/")
                .servlet(new ServletDecl("who", WebSecurityEndToEndTest.Who.class, WebSecurityEndToEndTest.Who::new,
                        List.of("/conf/*", "/plain/*"), Map.of(), -1, false))
                .securityConstraints(List.of(
                        constraint(List.of("/conf/*"), List.of(), List.of(), null, guarantee),
                        constraint(List.of("/plain/*"), List.of(), List.of(), null, "NONE")))
                .build();
    }

    @Test
    void aConfidentialConstraintRefusesPlainHttpByDefault() throws Exception {
        try (var app = new SecureApp(model("CONFIDENTIAL"))) {
            assertEquals(403, app.get("/conf/x").statusCode());
            assertEquals(200, app.get("/plain/x").statusCode());
        }
    }

    @Test
    void withAnHttpsPortPlainHttpIsRedirectedForConfidentialAndIntegral() throws Exception {
        for (String guarantee : List.of("CONFIDENTIAL", "INTEGRAL")) {
            var settings = SecuritySettings.DEFAULTS.withIdentityStore(SecureApp.USERS).withConfidentialPort(8443);
            try (var app = new SecureApp(model(guarantee), settings)) {
                var r = app.get("/conf/x?y=1");
                assertEquals(302, r.statusCode(), guarantee);
                assertEquals("https://127.0.0.1:8443/conf/x?y=1", r.headers().firstValue("Location").orElse(""), guarantee);
                assertEquals(200, app.get("/plain/x").statusCode(), guarantee);
            }
        }
    }
}
```

- [ ] **Step 2: Run them and confirm they fail.** Run `./mvnw -ntp -pl foy-core install -Dtest='TransportGuaranteeTest,TransportGuaranteeEndToEndTest'`. Expected: FAIL (200 on plain HTTP; `isPlainHost` missing).

- [ ] **Step 3: Implement.** In `WebSecurity.admit`, right after `Decision decision = constraints.decide(...)`:

```java
        if (decision.confidential() && !req.isSecure()) {
            refuseInsecure(req, res);
            return false;
        }
```

and add:

```java
    /**
     * A CONFIDENTIAL/INTEGRAL constraint on a plain request (section 13.8). With a configured HTTPS
     * port, the client is redirected to the same URI over HTTPS. Otherwise, or when the Host header
     * is not a plain host name, the request is refused with 403.
     */
    private void refuseInsecure(HttpServletRequestImpl req, HttpServletResponse res) throws IOException {
        int port = settings.confidentialPort();
        String host = req.getServerName();
        if (port < 0 || !isPlainHost(host)) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        String query = req.getQueryString();
        res.sendRedirect("https://" + host + (port == 443 ? "" : ":" + port) + req.getRequestURI()
                + (query == null ? "" : "?" + query));
    }

    /** Letters, digits, {@code '.'} and {@code '-'} only: no userinfo, path, port, space or CR/LF can reach a Location header. */
    static boolean isPlainHost(String host) {
        if (host == null || host.isEmpty() || host.length() > 253) return false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-';
            if (!ok) return false;
        }
        return true;
    }
```

An IPv6 literal host is refused with 403 rather than redirected. Record this in the docs (Task 6.10).

- [ ] **Step 4: Run the tests and the gates.**
  1. `./mvnw -ntp install` and `./mvnw -ntp -Ptck -pl foy-tck test`: green.
  2. Run the security TCK classes, which must be unchanged. The CLIENT-CERT tests still fail at deployment: no `https` Arquillian container is defined.

- [ ] **Step 5: Commit.** Use the message `feat(core): enforce the transport guarantee with an optional HTTPS redirect`.

---

### Task 6.10: Phase exit — full TCK, baseline, documentation, follow-ups

**Files:**
- Modify:
  - `foy-tck/tck-baseline.txt` (refresh from the full run)
  - `TCK.md` (new `## 0. Phase 6 exit: security (<date>)` above the Phase 5 one; the existing `0.`–`0.3` headings shift by one, so Phase 5 becomes `## 0.1`, Phase 4 `## 0.2`, and so on, as the earlier exits did)
  - `docs/superpowers/plans/2026-10-07-servlet-completion-roadmap.md`:
    - in "Phase 6 — Security": logout clears the identity only, CLIENT-CERT deferred, DIGEST out;
    - a new "Phase 6 exit / follow-ups" section after Phase 5's.
  - `docs/en/modules/ROOT/pages/{usage,concepts,internals,reference,tck,migration,whats-new}.adoc`
  - `README.md` (feature list, if it mentions security or `SecurityProvider`)
  - `BUG.md` (known gaps below; close any bug opened during the phase with its commit)

- [ ] **Step 1: Run the builds.** `./mvnw -ntp install` (whole reactor) and `./mvnw -ntp -Ptck -pl foy-tck test` must be green.

- [ ] **Step 2: Run the full TCK.**
  1. `./run-official-tck-servlet6.1.sh --all > /tmp/foy-tck-phase6.log 2>&1`. It takes about 11 minutes: run it in the background and poll for `BUILD`.
  2. `./run-official-tck-servlet6.1.sh --no-install --family api > /tmp/foy-tck-phase6-api.log 2>&1`, for `GetServletRegistrationsTest`, which only runs in family mode.
  3. Tally and check:

```bash
bash foy-tck/tck-tally.sh /tmp/foy-tck-phase6.log > /tmp/tally-phase6.txt
LC_ALL=C join foy-tck/tck-baseline.txt /tmp/tally-phase6.txt \
  | awk '$5 != $2 || $6 > $3 || $7 > $4 || $5-$6-$7 < $2-$3-$4 {print "REGRESSION", $0}'   # must print nothing
LC_ALL=C join -v1 foy-tck/tck-baseline.txt /tmp/tally-phase6.txt                           # must print nothing
LC_ALL=C join -v2 foy-tck/tck-baseline.txt /tmp/tally-phase6.txt                           # must print nothing
awk '{r+=$2; b+=$3; s+=$4} END {print r - b - s " passing / " r " run, " s " skipped"}' /tmp/tally-phase6.txt
grep 'tiers=Stats' /tmp/foy-tck-phase6.log | grep -v 'reflection=0'                       # must print nothing
```

  4. Expected: **1692 passing / 1714 run, 7 skipped**, 15 bad (sum of the third column). The security lines of `/tmp/tally-phase6.txt` read exactly:
     - `servlet.tck.spec.security.annotations.AnnotationsTests 7 0 0`
     - `servlet.tck.spec.security.clientcert.ClientCertTests 1 1 0`
     - `servlet.tck.spec.security.clientcertanno.ClientCertAnnoTests 1 1 0`
     - `servlet.tck.spec.security.denyUncovered.DenyUncoveredTests 5 0 0`
     - `servlet.tck.spec.security.metadatacomplete.MetaDataCompleteTests 6 0 0`
     - `servlet.tck.spec.security.secbasic.SecBasicTests 14 0 0`
     - `servlet.tck.spec.security.secform.SecFormTests 25 2 0`

     Every other line equals its line in `foy-tck/tck-baseline.txt` at 776b708 (the 15 bad: these 4, `servlet.tck.api.jakarta_servlet.servletcontext40.ServletContext40Tests 12 3 0`, `servlet.tck.api.jakarta_servlet_http.part.PartTests 7 4 0` and `servlet.tck.api.jakarta_servlet_http.part1.Part1Tests 7 4 0`).
  5. Per family: `api.*` 848/859 (849/860 in family mode), `pluggability.*` 646/646, `spec.*` 196/207 with 7 skipped, `compat.*` 2/2.
  6. Any other figure is investigated before going on. A new failure is a regression, not a gap.

- [ ] **Step 3: Refresh the baseline.** `cp /tmp/tally-phase6.txt foy-tck/tck-baseline.txt`, then commit with the message `build(tck): refresh the baseline to the Phase 6 exit tally`.

- [ ] **Step 4: Write the documentation.** English only; say "Java Modules", never the abbreviation.
  - **`TCK.md` §0:**
    - Date, branch at its commit, figures overall and per family, and the surefire summary.
    - The join result: zero regression, `join -v` empty, 7 classes improved (33 tests), `reflection=0`.
    - Accepted gaps with their reasons: the CLIENT-CERT pair (deferred, needs chappe client auth and peer certificates plus an `https` Arquillian container) and SecForm `test2`/`test2_anno` (they need `error.jsp` executed).
  - **`usage.adoc`:**
    - Declaring users (`FoyChappeBoot.builder().user("alice", pw, "admin")`) or plugging an `IdentityStore`.
    - `confidentialPort`, `rotateSessionIdOnLogin`.
    - web.xml `security-constraint`/`login-config` BASIC and FORM, with an example, and `@ServletSecurity`.
    - Programmatic `login`/`logout`/`authenticate`.
  - **`concepts.adoc`:** the constraint model (best pattern, per-method combination, uncovered methods, descriptor beats annotation on an exact pattern), the mechanisms, session-cached identity for FORM, role refs of the executing servlet.
  - **`internals.adoc`:**
    - `ConstraintTable` compiled at deploy.
    - `WebSecurity.admit` order: restore the session identity, then the FORM action, then the transport guarantee, then exclusion, then authentication, then roles.
    - Where the bridge calls it (all three branches, the original path and the welcome target).
    - Session notes vs attributes; `enterServlet`/`leaveServlet`.
    - Fail-closed rules.
    - `j_password` exists as a `String` in the request parameter map: it cannot be wiped.
  - **`reference.adoc`:**
    - `IdentityStore`/`Identity` (contract: constant-time comparison, no logging, exception = refusal).
    - The `InMemoryIdentityStore` behaviour (salted SHA-256, decoy digest; not a password database).
    - `FoyChappeBoot` security setters.
    - Supported `auth-method`s: BASIC, FORM; others fail closed.
    - Default realm `Restricted`, `j_security_check` POST-only, CONFIDENTIAL/INTEGRAL behaviour (IPv6 literal hosts get 403).
    - No group mapping; `run-as` is reported by `getRunAsRole()` but not propagated (no EJB container).
  - **`tck.adoc`:** the security status and accepted gaps, as in `TCK.md`.
  - **`migration.adoc`**: in `== Known gaps`, replace the "Security enforcement" bullet (no longer true) and the "Form-based / Digest authentication" bullet with one bullet "CLIENT-CERT deferred, DIGEST not supported; both fail closed (403)". Then add a `=== Security` subsection to the "Behaviour changes for `FoyChappeBoot` embedders" section, after `=== Request listeners` and before `== Common pitfalls`, with the same one-line lead sentence as its siblings and these bullets:
    - `SecurityProvider`/`AuthenticatedUser` are replaced by `IdentityStore`/`Identity` (API break, with a before/after snippet).
    - web.xml `security-constraint`s are now enforced, as are `deny-uncovered-http-methods` and `transport-guarantee`. A previously reachable resource may now answer 401/403.
    - Annotation constraints apply per url-pattern, and the descriptor wins on exact patterns.
    - The BASIC realm is now `login-config/realm-name` or `Restricted`, no longer `vidocq`.
    - `isUserInRole` follows `security-role-ref`.
    - `login()` sets the configured mechanism as auth type, rotates the session id and, under FORM, keeps the identity in the session.
    - `logout()` keeps the session.
    - An unsupported `auth-method` now answers 403.
    - `setServletSecurity` returns the descriptor-constrained patterns and throws after initialisation.
    - `declareRoles` is implemented.
  - **`whats-new.adoc`:** a Phase 6 entry.
  - **Roadmap "Phase 6 exit / follow-ups":** shipped items, figures, remaining failures by phase (CLIENT-CERT 2 deferred, JSP 2 + 3, multipart 8), open bugs, deferred minors. The minors to list:
    - no `Cache-Control: no-store` on the login page;
    - no FORM landing-page setting;
    - `isUserInRole` in an async `start()` thread sees no executing servlet;
    - a FORM error-page forward keeps the POST method;
    - rotation on BASIC `login()` when a session exists.
  - **`BUG.md`**, two entries numbered per day as the file does: at main 776b708 the last id is BUG-20261010-08, so written on 2026-10-10 they are BUG-20261010-09 and BUG-20261010-10; written on a later day D, BUG-D-01 and BUG-D-02 (unless entries of day D exist: continue after the last). Statuses `DEFERRED` or `ACCEPTED GAP`:
    - CLIENT-CERT unsupported (`ClientCertTests`, `ClientCertAnnoTests`; repro: any `auth-method` CLIENT-CERT application answers 403);
    - SecForm `test2`/`test2_anno` blocked by JSP (repro: failed `j_security_check` forwards to `/error.jsp`, which lands on `*.jsp`).

- [ ] **Step 5: Commit.** Use the message `docs: document security enforcement, mechanisms and the identity SPI (Phase 6 exit)`.

---

## Self-review

- **Spec coverage:**

  | Requirement | Task |
  |---|---|
  | `login-config` BASIC | 6.5 |
  | `login-config` FORM | 6.6 |
  | CLIENT-CERT/DIGEST fail closed | 6.5 |
  | web.xml `security-constraint` and annotation merge | 6.4, 6.5 |
  | `setServletSecurity` | 6.8 |
  | Transport guarantee | 6.9 |
  | `deny-uncovered-http-methods` | 6.4, 6.5 |
  | Role refs and `@DeclareRoles` | 6.3 |
  | `declareRoles` and `@RunAs`/run-as | 6.8 |
  | `login()`/`logout()`/`authenticate()` | 6.7 |
  | Identity SPI and in-memory store | 6.1 |
  | `FoyChappeBoot` configuration | 6.1 |
  | TCK users and properties | 6.2 |
  | Session id rotation | 6.6, 6.7 |
  | Logout keeps the session | 6.6, 6.7 |
  | Exit gates and docs | 6.10 |

  The CLIENT-CERT harness work is out by decision.
- **Placeholders:** none. The two look-ups Task 6.8 used to leave open (registration class names, `Deployment` context accessor) were resolved on main 776b708.
- **Type consistency:** these names are used identically across tasks:
  - `Identity`, `IdentityStore.validate`, `CredentialCheck.validate`, `SecuritySettings`;
  - `bindIdentity`/`identity()`, `enterServlet`/`leaveServlet`, `RoleMapping.isUserInRole(servletName, identity, role)`;
  - `ConstraintTable.Builder.descriptor/servlet/build`, `Decision.permits`;
  - `WebSecurity.admit/loggedIn/loggedOut/authenticatorFor/permitAll`;
  - `FormAuthenticator.IDENTITY_NOTE/SAVED_TARGET_NOTE/processLogin/safeTarget`, `HttpSessionImpl.note/setNote/removeNote`;
  - `VidocqServletContext.security()/setSecurity()/roleMapping()/registeredServletSecurity()`.
- **Review Focus:** each of the five lines has a named test in its owning task (6.1, 6.5, 6.6, 6.7). The Host-header injection risk of the HTTPS redirect is pinned in Task 6.9 (`aHostileHostHeaderIsRefusedNotRedirected`).
