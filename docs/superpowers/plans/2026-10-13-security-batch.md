# Security Batch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the four open security-side bugs in `foy/BUG.md`:

| Bug | Problem |
|---|---|
| BUG-20261011-05 | Constraint bypass through letter case. |
| BUG-20261011-08 | A fragment `security-role-ref` conflict is resolved silently. |
| BUG-20261011-09 | The password digest collides on lone surrogates. |
| BUG-20261010-06 | `upgrade()` during an ASYNC dispatch is dropped silently. |

**Architecture:** There are four independent fixes in `foy-core`, one task each. Each task follows TDD and closes its `BUG.md` entry.

**Tech Stack:** Java 25, Jakarta Servlet 6.1, JUnit 5, Maven 3.9.16 (`./mvnw`). foy-core tests run on the module path.

**Spec:** the `BUG.md` entries named above. Each one is the requirement, including its "Fix envisagé" section, except where a task below decides differently. This plan argues from those entries.

## Global Constraints

- **Language and terms:** English for code, Javadoc, comments, commit messages and Markdown. Write "Java Modules", never "JPMS".
- **Dependencies:** zero new runtime dependencies.
- **TDD:** write the failing test, run it, and see it fail for the expected reason. Then fix.
- **Commits:**
  - `git commit -S -s`, Conventional Commits, English.
  - Stage explicit paths only, never `git add -A`.
  - The last paragraph of each message is:
    ```
    Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
    Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
    ```
- **BUG.md:** set each closed bug's status to `FIXED (<short description>)`. Add a final investigation line naming the test that pins it.
- **Builds:**
  - Use `./mvnw -ntp -o -Dmaven.repo.local=/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq-foy/714bb865-5c9f-4aac-a489-970c2a1d362e/scratchpad/m2 -Dmaven.repo.local.tail=$HOME/.m2/repository ...`.
  - If classes look stale (the IDE writes into `target/`), delete the affected `target/classes` or `target/test-classes` files and rebuild.
- **Verification:** each task ends with `./mvnw ... -pl foy-core install` green.

## Review Focus

1. **Case check and symbolic links.** The check must not reject in-root symbolic links whose name the request spells exactly. `toRealPath(LinkOption.NOFOLLOW_LINKS)` keeps the link's own name. It was verified on macOS with Java 25: it returns the on-disk case.
2. **Case check on case-sensitive file systems.** The check is a no-op there (Linux CI): the real name always equals the requested one. The case test must skip itself, through `assumeTrue`, when the file system is case-sensitive.
3. **Directory requests.** A welcome-file or redirect path such as `/STATIC/` must not reach the file through a case-folded directory either. The provider-level check covers every segment, directories included.
4. **Refused password and timing.** A password that cannot be encoded is refused by `validate`. The cost stays the same, because the decoy is digested instead. Registering such a password fails fast.
5. **Async-dispatch flag.** The flag must be restored in a `finally` block. Otherwise a later dispatch of the same request object would wrongly refuse `upgrade()`.

---

### Task 1: Refuse a resource whose real name differs in case (BUG-20261011-05)

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/container/ClassPathResourceProvider.java`. The fix point is `DirLoc.resolve`, around lines 376-386.
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/container/DefaultServlet.java`. The fix point is `locate`, around lines 272-310.
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/container/ResourcePaths.java`, to add the shared helper. If that class does not fit, put it in a new package-private `CaseCheck` class in the same package.
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/container/ClassPathResourceProviderTest.java`
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/security/WebSecurityEndToEndTest.java`, which uses `SecureApp` with a directory root.
- Modify: `foy/BUG.md`

**Interfaces:**
- Produces: `static boolean ResourcePaths.realNameMatches(Path file, String relative)`. It returns true when the last `n` name segments of `file.toRealPath(LinkOption.NOFOLLOW_LINKS)` equal the `n` non-empty `/`-separated segments of `relative`, with `String.equals`. It returns true when the file does not exist: the caller handles absence. It returns false on an `IOException` (fail closed).

**Why two places.**
- `DirLoc.resolve` is the production provider. Fixing it covers `getResource`, `getResourceAsStream`, `getRealPath`, the resource metadata and the default servlet in one place.
- `DefaultServlet.locate` covers any other provider whose `toUrl` returns a `file:` URL: the TCK's `WarResourceProvider` mirror, and the test providers. Without it, the constraint bypass would come back with any custom provider.

- [ ] **Step 1: Write the failing provider test** in `ClassPathResourceProviderTest`. Use the existing `@TempDir` style of `symbolicLinksCannotLeaveAnExplodedRoot`.

```java
@Test
void aPathThatDiffersInCaseFromTheRealNameIsMissing() throws Exception {
    Path res = tmp.resolve("app/META-INF/resources/static");
    Files.createDirectories(res);
    Files.writeString(res.resolve("x.txt"), "x");
    assumeTrue(Files.exists(res.resolveSibling("STATIC").resolve("X.TXT")),
            "only meaningful on a case-insensitive file system");
    var p = /* build the provider over tmp.resolve("app") exactly as the neighbouring tests do */;
    assertNotNull(p.openStream("/static/x.txt"));
    assertNull(p.openStream("/STATIC/x.txt"));
    assertNull(p.openStream("/static/X.txt"));
    assertNull(p.metadata("/STATIC/x.txt"));
    assertNull(p.toUrl("/STATIC/x.txt"));
}
```

Look at the neighbouring tests for the provider constructor and the exact `openStream`, `metadata` and `toUrl` signatures, and close any opened stream. If one of these methods throws instead of returning null for a missing resource, assert what the neighbouring "missing" tests assert.

- [ ] **Step 2: Add the symbolic-link guard test.** An in-root symbolic link `link -> static` stays reachable as `/link/x.txt` and is refused as `/LINK/x.txt`. Use `assumeTrue` for both the case-insensitive file system and `Files.createSymbolicLink` succeeding (catch `UnsupportedOperationException` or `IOException` and skip).

- [ ] **Step 3: Write the failing end-to-end test** in `WebSecurityEndToEndTest`, next to the static-resource tests around lines 248-263. Build the same app with a `/static/*` constraint carrying an `auth-constraint`, and a directory root holding `static/x.txt`. Then check two requests:
  - `GET /static/x.txt` answers 401.
  - `GET /STATIC/x.txt` answers **404**: before the fix it is a 200 with the body, which is the bypass.

  Add `assumeTrue` on a case-insensitive file system. `SecureApp.DirectoryResources` is a test provider, so this test exercises the `DefaultServlet.locate` check.

- [ ] **Step 4: Run the tests to verify they fail.** Run `./mvnw ... -pl foy-core test -Dtest='ClassPathResourceProviderTest,WebSecurityEndToEndTest'`. Expected: the new assertions fail on macOS, with `/STATIC/x.txt` found or answered 200.

- [ ] **Step 5: Implement the helper.**

```java
/**
 * Whether the real name of {@code file}, as stored by the file system, spells the last segments
 * exactly as {@code relative} does. A case-insensitive file system (macOS, Windows) finds
 * {@code /STATIC/x} at {@code static/x}, while the container matches security constraints on
 * the requested path case-sensitively: serving that file would bypass a {@code /static/*}
 * constraint (BUG-20261011-05). Symbolic links are not followed, so an in-root link keeps its
 * own name. A missing file matches (the caller reports absence); an I/O failure does not.
 */
static boolean realNameMatches(Path file, String relative) {
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return true;
    String[] want = Arrays.stream(relative.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
    Path real;
    try {
        real = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
    } catch (IOException e) {
        return false;
    }
    int n = real.getNameCount();
    if (want.length > n) return false;
    for (int i = 0; i < want.length; i++) {
        if (!real.getName(n - want.length + i).toString().equals(want[i])) return false;
    }
    return true;
}
```

  It must be public if `DefaultServlet` lives in another package. Both classes are in `io.vidocq.foy.internal.container`, so package-private is enough.

- [ ] **Step 6: Call it in `DirLoc.resolve`.** After the existing escape checks, and before `return new DirLoc(...)`:

```java
if (!ResourcePaths.realNameMatches(p, rel)) return new Missing();
```

  `rel` is what `resolve` receives. If it carries a leading `/`, the helper ignores empty segments. If `rel` is chained (`path` is not `base`), only the `rel` segments are compared, which is correct because the earlier segments were checked when they were resolved.

- [ ] **Step 7: Call it in `DefaultServlet.locate`.**
  - This applies when a `file:` URL is resolved: in the `meta == null` branch after `Path.of(url.toURI())`.
  - In the `meta != null` branch, also resolve `ctx.getResource(path)`. If it is a `file:` URL, apply the same check.
  - Return `null` (404) on a mismatch. Use `path` without its leading `/` as `relative`.
  - Keep the code as one small private method, `boolean caseMatches(ServletContext ctx, String path)`, called once before the metadata or URL branch.
  - It may swallow `MalformedURLException`, `URISyntaxException` and `IllegalArgumentException` and return true for non-`file:` URLs. Jar entries are already case-sensitive.

- [ ] **Step 8: Run the tests to verify they pass.** Run the same command as Step 4, then the whole `foy-core` suite: `./mvnw ... -pl foy-core install`.

- [ ] **Step 9: Update BUG.md.** BUG-20261011-05 becomes `**Statut** : FIXED (real-name case check in ClassPathResourceProvider and DefaultServlet)`. Add an investigation line naming both tests.

- [ ] **Step 10: Commit.**

```bash
git add foy-core/src/main/java/io/vidocq/foy/internal/container/ClassPathResourceProvider.java \
        foy-core/src/main/java/io/vidocq/foy/internal/container/DefaultServlet.java \
        foy-core/src/main/java/io/vidocq/foy/internal/container/ResourcePaths.java \
        foy-core/src/test/java/io/vidocq/foy/internal/container/ClassPathResourceProviderTest.java \
        foy-core/src/test/java/io/vidocq/foy/internal/security/WebSecurityEndToEndTest.java BUG.md
git commit -S -s -m "fix(container): refuse a resource whose real name differs in case (BUG-20261011-05)" -m "<body + trailers>"
```

---

### Task 2: Fail the deployment on a fragment security-role-ref conflict (BUG-20261011-08)

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/webxml/ComponentMerger.java`. The changes are around lines 64, 70, 110, 121 and 131.
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/webxml/FragmentMergerTest.java`
- Modify: `foy/BUG.md`

**Interfaces:**
- Consumes: `MergeSlots.Keyed<V>(String element, String owner)`, with `webXml(Map)`, `fragment(String fragmentId, Map) throws ServletException` and `values()`.

- [ ] **Step 1: Write the failing tests** in `FragmentMergerTest`, next to `initParamConflictBetweenFragmentsFails` (around line 149). Use the `roleRef(name, link)` helper from around line 143.

```java
@Test
void securityRoleRefConflictBetweenFragmentsFails() {
    var e = assertThrows(ServletException.class, () -> FragmentMerger.merge(web(""), List.of(
            frag("F1", servlet("s", "a.S", roleRef("MGR", "admin"))),
            frag("F2", servlet("s", "a.S", roleRef("MGR", "user"))))));
    assertConflict(e, "<security-role-ref>", "'MGR'", "'s'", "F1", "F2");
}

@Test
void securityRoleRefConflictBetweenFragmentsIsResolvedByWebXml() throws Exception {
    // web.xml references MGR: the fragments' different links are not a conflict.
    // Build web.xml with servlet s and roleRef("MGR", "boss"), the same way as
    // securityRoleRefsWebXmlWinsPerRoleNameAndFragmentsAddTheOthers. Assert the merged ref is "boss".
}

@Test
void anEqualSecurityRoleRefInTwoFragmentsIsNotAConflict() throws Exception {
    // F1 and F2 both declare MGR -> admin: the merge succeeds with MGR -> admin.
}
```

  Fill the two bodies in the style of `securityRoleRefsWebXmlWinsPerRoleNameAndFragmentsAddTheOthers`, using the same builders and accessors. If the helpers take a different argument shape, adapt them, but keep the three behaviours.

- [ ] **Step 2: Run the tests to verify they fail.** Run `./mvnw ... -pl foy-core test -Dtest=FragmentMergerTest`. Expected: `securityRoleRefConflictBetweenFragmentsFails` fails because nothing is thrown. The other two may already pass, and they guard the fix.

- [ ] **Step 3: Implement.** In `ComponentMerger.Component`, replace `final Map<String,String> roleRefs = new LinkedHashMap<>();` with:

```java
final Keyed<String> roleRefs;
```

  Initialise it in the constructor next to `params`, as `new Keyed<>("<security-role-ref>", " for " + kind + " '" + name + "'")`.
  - Line ~110: `c.roleRefs.webXml(d.securityRoleRefs());`
  - Line ~121: `c.roleRefs.fragment(f.id(), d.securityRoleRefs());`. Use the same fragment-id expression that the `params.fragment` call uses at line ~91, and propagate `ServletException` as that call does.
  - Line ~131: pass `c.roleRefs.values()`. If `ServletDef` stores the map, wrap it in `Map.copyOf` or keep the existing copy semantics.
  - Update the comment at line ~120: web.xml wins per role-name, and two fragments that disagree on a role-name fail the deployment (§8.2.3).

- [ ] **Step 4: Run the tests to verify they pass.** Run the same command as Step 2, then `./mvnw ... -pl foy-core install`.

- [ ] **Step 5: Update BUG.md.** BUG-20261011-08 becomes `FIXED (fragment role refs merged through MergeSlots.Keyed)`.

- [ ] **Step 6: Commit** with `fix(webxml): fail the deployment on a fragment security-role-ref conflict (BUG-20261011-08)`. Stage the explicit paths and add the trailers.

---

### Task 3: Encode the password strictly in InMemoryIdentityStore (BUG-20261011-09)

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/security/InMemoryIdentityStore.java`. The changes are around lines 59, 74-84, 107-123 and 133-140.
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/security/InMemoryIdentityStoreTest.java`
- Modify: `foy/BUG.md`

**Interfaces:**
- `static byte[] digest(byte[] salt, char[] password)` keeps its signature. It now throws `IllegalArgumentException("the password is not valid UTF-16")` when the password does not encode, for example on a lone surrogate.
- `Builder.user(...)` rejects such a password with an `IllegalArgumentException`.
- `validate(...)` refuses it (`Optional.empty()`, or whatever the method returns for a wrong password) after digesting the decoy, so the timing stays the same.

- [ ] **Step 1: Write the failing tests** in `InMemoryIdentityStoreTest`.

```java
@Test
void aPasswordWithALoneSurrogateDoesNotDigest() {
    // Before the fix both encode to "a?" and digest to the same value.
    assertThrows(IllegalArgumentException.class, () -> InMemoryIdentityStore.digest(new byte[16], "a\uD800".toCharArray()));
    assertThrows(IllegalArgumentException.class, () -> InMemoryIdentityStore.digest(new byte[16], "a\uDC00".toCharArray()));
}

@Test
void aMalformedPasswordCannotBeRegistered() {
    assertThrows(IllegalArgumentException.class,
            () -> InMemoryIdentityStore.builder().user("u", "a\uD800".toCharArray(), Set.of()));
}

@Test
void aMalformedCandidateIsRefused() {
    var store = InMemoryIdentityStore.builder().user("u", "a?".toCharArray(), Set.of()).build();
    // Before the fix "a\uD800" encodes to "a?" and authenticates as u.
    assertTrue(store.validate("u", "a\uD800".toCharArray()).isEmpty());
}

@Test
void aValidNonAsciiPasswordStillAuthenticates() {
    var store = InMemoryIdentityStore.builder().user("u", "pässwörd😀".toCharArray(), Set.of("r")).build();
    assertTrue(store.validate("u", "pässwörd😀".toCharArray()).isPresent());
}
```

  Adapt the builder and `validate` calls to the real API used by the existing tests (`validCredentialsYieldThePrincipalAndTheRoles`). The name, password type and roles type must match. If `user(...)` takes a `String` password, pass strings. The `validate` return type is whatever the existing tests assert on.

- [ ] **Step 2: Run the tests to verify they fail.** Run `./mvnw ... -pl foy-core test -Dtest=InMemoryIdentityStoreTest`. Expected: the first three fail. `digest` does not throw, and `aMalformedCandidateIsRefused` authenticates.

- [ ] **Step 3: Implement `digest`.**

```java
static byte[] digest(byte[] salt, char[] password) {
    CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    // Exact upper bound: the encoder never has to grow, so no unwiped intermediate copy exists.
    byte[] buffer = new byte[(int) Math.ceil(encoder.maxBytesPerChar() * (double) password.length)];
    ByteBuffer out = ByteBuffer.wrap(buffer);
    try {
        CoderResult r = encoder.encode(CharBuffer.wrap(password), out, true);
        if (!r.isUnderflow()) throw new IllegalArgumentException("the password is not valid UTF-16");
        r = encoder.flush(out);
        if (!r.isUnderflow()) throw new IllegalArgumentException("the password is not valid UTF-16");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(salt);
        md.update(buffer, 0, out.position());
        return md.digest();
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 is required of every Java platform", e);
    } finally {
        Arrays.fill(buffer, (byte) 0);
    }
}
```

  Add the imports: `java.nio.charset.CharsetEncoder`, `java.nio.charset.CoderResult` and `java.nio.charset.CodingErrorAction`. Remove the imports that become unused.

- [ ] **Step 4: Implement `validate`.** It must digest the decoy when the candidate does not encode, then refuse:

```java
byte[] candidate;
boolean encodable = true;
try {
    candidate = digest(user == null ? decoySalt : user.salt(), password);
} catch (IllegalArgumentException e) {
    candidate = digest(decoySalt, DECOY_PASSWORD); // same cost; never matches
    encodable = false;
}
// existing comparison; succeed only when encodable && user != null && MessageDigest.isEqual(...)
```

  Keep the existing structure and the order of the null checks, and reuse whatever the decoy uses at line ~59. Give the decoy password a name, for example a `private static final char[]` constant, if it is not one already.

- [ ] **Step 5: `Builder.user`.** Calling `digest` there already throws on a malformed password. Make sure the exception message names neither the password nor its characters. Make sure the caller's array is still wiped or left alone, consistently with `theDeclaredArrayIsNotRetained`.

- [ ] **Step 6: Run the tests to verify they pass.** Run the same command as Step 2, then `./mvnw ... -pl foy-core install`.

- [ ] **Step 7: Update BUG.md.** BUG-20261011-09 becomes `FIXED (strict UTF-8 encoder into an exact-size wiped buffer)`.

- [ ] **Step 8: Commit** with `fix(security): encode the password strictly before digesting it (BUG-20261011-09)`.

---

### Task 4: Refuse upgrade() during an async dispatch (BUG-20261010-06)

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java`. The changes are around `upgrade` (lines ~1222-1250) and the dispatcher-type field (line ~809).
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/ChappeServletBridge.java`. The changes are around the two async-dispatch invocations (lines ~821-837).
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/ServletUpgradeEndToEndTest.java`
- Modify: `foy/BUG.md`

**Interfaces:**
- Produces: `void HttpServletRequestImpl.asyncDispatch(boolean inAsyncDispatch)` (package-private or public, as `setDispatcherType` is). It is a plain flag, not a dispatcher-type change: the impl's `getDispatcherType()` must keep its current behaviour, because other code reads it.

- [ ] **Step 1: Write the failing test** in `ServletUpgradeEndToEndTest`, modelled on `upgradeAfterStartAsyncThrowsIse` (around line 291):
  - Servlet A, mapped `/a` and async-supported, calls `req.startAsync()` then `ac.dispatch("/b")`.
  - Servlet B, mapped `/b`, calls `resp.setStatus(101)`, `resp.setHeader("Upgrade", "x")`, then `req.upgrade(Probe.class)`. Use the test's existing upgrade handler class. On an `IllegalStateException`, B resets the status to 200 and writes `"ise"`.
  - Assert `HTTP/1.1 200`, a body ending in `ise`, and the probe's `destroyCalls == 0` (and no init).

  Use the same `start(...)`, `Client` and `tckHead().replace("Connection: Upgrade", "Connection: close")` scaffolding. If `start` takes a single servlet, follow how other tests in the file deploy two mapped servlets. If none does, build the model with `WebAppModel` as the neighbouring end-to-end tests do.

- [ ] **Step 2: Run the test to verify it fails.** Run `./mvnw ... -pl foy-core test -Dtest=ServletUpgradeEndToEndTest`. Expected: the body does not end with `ise`. `upgrade` returned a handler and the response is empty or 101.

- [ ] **Step 3: Implement.**
  - In `HttpServletRequestImpl`, add `private boolean inAsyncDispatch;` with the setter `asyncDispatch(boolean)`.
  - In `upgrade`, after the `isAsyncStarted()` check, add:

```java
// An async dispatch belongs to a cycle the container ends itself: it cannot leave HTTP either.
if (inAsyncDispatch) throw new IllegalStateException("upgrade() during an async dispatch");
```

  - In `ChappeServletBridge`, wrap both async-dispatch invocations (cross-context ~821-823 and same-context ~835-837):

```java
req.asyncDispatch(true);
try {
    /* existing invoke */
} finally {
    req.asyncDispatch(false);
}
```

  `req` is the `HttpServletRequestImpl` that the bridge already holds there. Keep any existing try/finally and nest or merge it, without changing other behaviour.

- [ ] **Step 4: Run the tests to verify they pass.** Run the same command as Step 2, then `./mvnw ... -pl foy-core install`.

- [ ] **Step 5: Update BUG.md.** BUG-20261010-06 becomes `FIXED (upgrade() throws IllegalStateException during an async dispatch)`.

- [ ] **Step 6: Commit** with `fix(bridge): refuse upgrade() during an async dispatch (BUG-20261010-06)`.

---

### Task 5: Documentation and full build

**Files:**
- Modify: the pages under `docs/en/modules/ROOT/pages/` that describe these behaviours, if any. Look for:
  - static resources and security constraints (`usage.adoc` or `reference.adoc`);
  - fragment merging rules;
  - `InMemoryIdentityStore` and the in-memory identity store;
  - `upgrade`.

  Use `grep -rn "security-role-ref\|InMemoryIdentityStore\|upgrade(\|case" docs/en`.

- [ ] **Step 1: Update the documentation.** Add one sentence per changed behaviour where the docs describe the area:
  - A resource is served only under its exact name, and a request that differs in case gets 404.
  - Fragments that disagree on a `security-role-ref` fail the deployment.
  - A password that is not valid UTF-16 cannot be registered and never authenticates.
  - `upgrade()` throws during an async dispatch.

  Skip any area the docs do not cover. Do not create new sections.
- [ ] **Step 2: Run the full build.** Run `./mvnw ... install` from the foy root. Report the total test count.
- [ ] **Step 3: Commit** with `docs: describe the case check, role-ref conflicts, strict password encoding and async upgrade`. Only commit if there are doc changes.
