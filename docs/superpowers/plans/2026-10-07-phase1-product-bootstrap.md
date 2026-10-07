# Phase 1 — Product Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** move the Servlet deployment lifecycle out of the TCK harness into
foy-core, so the product boot path (`FoyChappeBoot`) does what the TCK
harness does today, and fix the quick spec wins (form POST parameters).

**Architecture:** a single immutable deployment description (`WebAppModel`)
is produced by any front end (CDI discovery, `web.xml`, the TCK harness,
later the generated components of Phase 2) and handed to `WebAppDeployer`,
which runs the Servlet 6.1 §4.4 / §2.3 lifecycle and returns a `Deployment`
(Chappe `Handler` + `close()`). Instantiation goes through a
`ComponentFactory` seam (reflective in this phase, replaced by the
generated-code registry in Phase 2) and `@HandlesTypes` through a
`HandlesTypesResolver` seam (null set in this phase, filled in Phase 2/3).

**Tech Stack:** Java 25, Jakarta Servlet 6.1 API, chappe 0.4.0-SNAPSHOT,
JUnit 5, `java.net.http.HttpClient` for end-to-end tests.

**Spec:** `docs/superpowers/plans/2026-10-07-servlet-completion-roadmap.md`
(Phase 0 + Phase 1) and Jakarta Servlet 6.1 §2.3 (lifecycle), §3.1.1 (form
parameters), §4.4 (configuration methods), §8.2.3 (annotation / descriptor
merge).

## Global Constraints

- Java 25 (Temurin), Maven 3.9.16, run from `foy/` with `./mvnw`.
- foy-core runtime dependencies: Jakarta Servlet / CDI / Annotation APIs and
  `io.vidocq.chappe.api` only. No new runtime dependency.
- Java Modules: every new package exported from `foy-core/src/main/java/module-info.java`
  only if another Foy module consumes it. Say "Java Modules", never "JPMS".
- English for code, comments, Javadoc, commit messages.
- Conventional Commits, GPG-signed (`git commit -S -s`), trailer
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- TDD: each task's test is written and seen failing before the code.
- **Behavioural neutrality gate:** after Task 1.4 and at the end of the plan,
  `./run-official-tck-servlet6.1.sh --all` must report **≥ 921 passing**
  (baseline 2026-10-07) and no previously passing TCK class may regress.

## Review Focus

1. A servlet mapped to several url-patterns must be **one** instance with
   **one** `init()` call (the harness keys init params by instance identity
   today — Task 1.1 test `oneInstancePerDeclaration`).
2. A servlet whose `init()` throws must answer 500 (re-throwing the
   `ServletException` so `<error-page>` for `ServletException` fires),
   `UnavailableException` permanent → 404, temporary → 503 — never be
   silently dropped (Task 1.3 tests).
3. Dynamic registration after deployment must throw `IllegalStateException`
   (§4.4) — Task 1.3 test `addServletAfterDeployThrows`.
4. Form POST: once parameters were parsed from the body, `getInputStream()`
   must not hand the consumed body back as if unread; and if the application
   read the stream first, `getParameter` must not block or throw (Task 0.2).
5. `close()` must destroy filters then servlets in **reverse** init order and
   then fire `contextDestroyed`, even if one `destroy()` throws (Task 1.3 test
   `closeDestroysInReverseOrder`).

---

### Task 0.1: Hygiene — TCK runner docs and script

**Files:**
- Modify: `foy-tck/README.md` (full rewrite of the "Why This Module Is Out of the Main Reactor" and "Running the TCK" sections)
- Modify: `run-official-tck-servlet6.1.sh:35-43`
- Modify: `TCK.md` (new §0 entry at the top of "## 0.")

- [ ] **Step 1: Rewrite `foy-tck/README.md`**

Replace the title/intro and the out-of-reactor section with:

```markdown
# foy-tck

Conformance harness and runner for the **Jakarta Servlet 6.1 official TCK**
against Foy (transport chappe, CDI vauban).

`foy-tck` is an **in-reactor module gated behind the `tck` Maven profile**:
a plain `./mvnw install` neither downloads nor runs anything TCK-related.
Run it through `../run-official-tck-servlet6.1.sh` (recommended) or
`./mvnw -Ptck,tck-official -pl foy-tck test`.

Historical note: the module used to live out of the reactor because
ShrinkWrap Maven Resolver 3.3 could not parse Model 4.1.0 POMs; that
constraint disappeared with the move to Maven 3.9.16 / Model 4.0.0.
```

Update "Running the TCK" to say "From the root of the **foy** repository"
and drop the list of `vidocq-*` modules the script used to install. Replace
the "Jakarta Servlet 6.1 TCK Conformance Status" table with a single line:
"Current figures: see `../TCK.md`."

- [ ] **Step 2: Fix the script**

In `run-official-tck-servlet6.1.sh`, replace

```bash
echo "📂 [2/3] Navigation vers foy-tck (POM Model 4.0.0 standalone hors reactor)..."
# foy-tck est in-reactor, activé par le profil Maven `tck`
# (harmonisation TCK, même pattern que les runners vidocq-runtime-tck-*).
```

with

```bash
echo "📂 [2/3] foy-tck is in-reactor, enabled by the 'tck' Maven profile"
```

and replace both `mvn ` invocations with `./mvnw -ntp `. Translate the
remaining French comments of the header to English.

- [ ] **Step 3: Add the TCK.md re-measurement entry**

Insert above "## 0. Re-run after the trailer/idle-timeout fixes (2026-06-12)":

```markdown
## 0. Re-measurement on 0.4.0-SNAPSHOT (2026-10-07)

`main` @ `65b1197`, chappe/vauban 0.4.0-SNAPSHOT, `./run-official-tck-servlet6.1.sh --all`:
1714 run, **921 pass**, 793 errors, 11 min 12 s — identical to 2026-06-12.
Per family: `api.*` 821/859, `pluggability.*` 5/646, `spec.*` 95/207,
`compat.*` 0/2.

**Caveat discovered by the 2026-10-07 audit:** the TCK harness
(`ServletTestHarness`, `VidocqDeployableContainer`) performs the deployment
lifecycle itself (init/destroy, web.xml, SCI, dynamic registrations). The
product boot path does not yet. Scores above measure engine + harness;
Phase 1 of `docs/superpowers/plans/` moves that lifecycle into foy-core.
```

- [ ] **Step 4: Commit a per-class TCK baseline and the diff script**

Create `foy-tck/tck-tally.sh` (used by every later phase):

```bash
#!/bin/bash
# Usage: foy-tck/tck-tally.sh <surefire-log>  → "<class> <run> <failures+errors>" lines, sorted.
grep -E 'Tests run: .* -- in servlet' "$1" \
  | sed -E 's/.*Tests run: ([0-9]+), Failures: ([0-9]+), Errors: ([0-9]+).* -- in (.*)/\4 \1 \2 \3/' \
  | awk '{print $1, $2, $3 + $4}' | sort
```

Run `./run-official-tck-servlet6.1.sh --all > /tmp/foy-tck-baseline.log 2>&1`
on the unchanged `main`, then
`foy-tck/tck-tally.sh /tmp/foy-tck-baseline.log > foy-tck/tck-baseline.txt`.
Expected: 1714 total run, 793 total bad
(`awk '{r+=$2; b+=$3} END {print r, b}' foy-tck/tck-baseline.txt`).

Regression check used by later tasks:

```bash
foy-tck/tck-tally.sh /tmp/foy-tck-after.log > /tmp/after.txt
join foy-tck/tck-baseline.txt /tmp/after.txt | awk '$5 > $3 {print "REGRESSION", $0}'
```

- [ ] **Step 5: Commit**

```bash
chmod +x foy-tck/tck-tally.sh
git add foy-tck/README.md foy-tck/tck-tally.sh foy-tck/tck-baseline.txt run-official-tck-servlet6.1.sh TCK.md
git commit -S -s -m "docs(tck): describe the in-reactor runner and record the 2026-10-07 baseline" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 0.2: Form POST parameters (Servlet 6.1 §3.1.1)

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java:185-260`
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/ServletFormParametersEndToEndTest.java`
- Modify: `BUG.md` (new entry, status FIXED at the end)

**Interfaces:**
- Consumes: `ServletDispatcher`, `ServletDispatcher.Mapping`, `UrlPatternMatcher.of`,
  `ChappeServletBridge(ServletDispatcher, VidocqServletContext, String)`, `TestServerLauncher.start(Handler)`.
- Produces: nothing new public.

- [ ] **Step 1: Write the failing test**

```java
package io.vidocq.foy.internal;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServletFormParametersEndToEndTest {

    private Server server;
    private int port;

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

    private void start(HttpServlet servlet) {
        var dispatcher = new ServletDispatcher(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/form"), servlet, "Form")));
        var r = TestServerLauncher.start(new ChappeServletBridge(dispatcher, new VidocqServletContext("/"), "/"));
        server = r.server;
        port = r.port;
    }

    private String post(String query, String contentType, String body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/form" + query))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void urlEncodedBodyParametersAreVisibleAfterQueryParameters() throws Exception {
        start(new HttpServlet() {
            @Override protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getWriter().write(Arrays.toString(req.getParameterValues("p")) + "|" + req.getParameter("q"));
            }
        });
        assertEquals("[fromQuery, fromBody]|café",
                post("?p=fromQuery", "application/x-www-form-urlencoded; charset=UTF-8", "p=fromBody&q=caf%C3%A9"));
    }

    @Test
    void bodyIsNotParsedForOtherContentTypes() throws Exception {
        start(new HttpServlet() {
            @Override protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getWriter().write(String.valueOf(req.getParameter("p")));
            }
        });
        assertEquals("null", post("", "text/plain", "p=x"));
    }

    @Test
    void streamReadFirstLeavesParametersQueryOnly() throws Exception {
        start(new HttpServlet() {
            @Override protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                String raw = new String(req.getInputStream().readAllBytes());
                resp.getWriter().write(raw + "|" + req.getParameter("p"));
            }
        });
        assertEquals("p=fromBody|null", post("", "application/x-www-form-urlencoded", "p=fromBody"));
    }

    @Test
    void consumedBodyIsEmptyForTheStream() throws Exception {
        start(new HttpServlet() {
            @Override protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                req.getParameter("p");
                resp.getWriter().write("[" + new String(req.getInputStream().readAllBytes()) + "]");
            }
        });
        assertEquals("[]", post("", "application/x-www-form-urlencoded", "p=fromBody"));
    }

    @Test
    void defaultBodyCharsetIsIso88591() throws Exception {
        start(new HttpServlet() {
            @Override protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setCharacterEncoding("UTF-8");
                resp.getWriter().write(req.getParameter("q"));
            }
        });
        assertEquals("café", post("", "application/x-www-form-urlencoded", "q=caf%E9"));
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -ntp -pl foy-core test -Dtest=ServletFormParametersEndToEndTest`
Expected: FAIL — `urlEncodedBodyParametersAreVisibleAfterQueryParameters` gets `[fromQuery]|null`, `defaultBodyCharsetIsIso88591` gets `null`.

- [ ] **Step 3: Implement**

In `HttpServletRequestImpl`, rename the section comment to
`// ---- Parameters (query string, then form body — Servlet 6.1 §3.1) ----`
and replace `parameters()` with:

```java
    private Map<String, List<String>> parsedParams;
    /** True once the form body was consumed by parameter parsing (§3.1.1). */
    private boolean bodyConsumedByParameters;

    private Map<String, List<String>> parameters() {
        if (parsedParams != null) return parsedParams;
        var out = new java.util.LinkedHashMap<String, List<String>>();
        decodeInto(out, chappe.query(), StandardCharsets.UTF_8);
        if (isFormPost() && inputStream == null && reader == null) {
            try {
                byte[] raw = chappe.body().asInputStream().readAllBytes();
                bodyConsumedByParameters = true;
                Charset cs = getCharacterEncoding() != null
                        ? Charset.forName(getCharacterEncoding())
                        : StandardCharsets.ISO_8859_1;
                decodeInto(out, new String(raw, StandardCharsets.ISO_8859_1), cs);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("cannot read form body", e);
            }
        }
        return parsedParams = out;
    }

    private boolean isFormPost() {
        if (!"POST".equalsIgnoreCase(getMethod())) return false;
        String ct = getContentType();
        if (ct == null) return false;
        int semi = ct.indexOf(';');
        String mime = (semi < 0 ? ct : ct.substring(0, semi)).trim();
        return "application/x-www-form-urlencoded".equalsIgnoreCase(mime);
    }

    private static void decodeInto(Map<String, List<String>> out, String encoded, Charset cs) {
        if (encoded == null || encoded.isEmpty()) return;
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            if (k.isEmpty()) continue;
            k = java.net.URLDecoder.decode(k, cs);
            v = java.net.URLDecoder.decode(v, cs);
            out.computeIfAbsent(k, _ -> new ArrayList<>()).add(v);
        }
    }
```

Note: the body bytes are turned into a String with ISO-8859-1 first (a
lossless byte→char map, since the payload is %-encoded ASCII), and
`URLDecoder.decode(.., cs)` then applies the real charset to the
percent-decoded bytes. Import `java.nio.charset.Charset` if it is not already imported.

In `getInputStream()` and `getReader()`, where `trackedBody` is created from
`chappe.body().asInputStream()`, substitute an empty stream when the body was
consumed:

```java
            java.io.InputStream source = bodyConsumedByParameters
                    ? java.io.InputStream.nullInputStream()
                    : chappe.body().asInputStream();
            trackedBody = new ServletInputStreamImpl(source);
```

- [ ] **Step 4: Run tests**

Run: `./mvnw -ntp -pl foy-core test`
Expected: all foy-core tests PASS (including the 5 new ones).

- [ ] **Step 5: Log in BUG.md and commit**

Append to `BUG.md`:

```markdown
## BUG-20261007-01 — form POST parameters ignored

- **Date** : 2026-10-07
- **Statut** : FIXED (this commit)
- **Module touché** : `foy-core` / `HttpServletRequestImpl`
- **Symptôme** : `getParameter*` only read the query string; an
  `application/x-www-form-urlencoded` POST body never became parameters (§3.1.1).
- **Reproduction minimale** : `ServletFormParametersEndToEndTest`.
- **Hypothèse de cause** : M1 shortcut ("query-string only for this milestone").
```

```bash
git add foy-core BUG.md
git commit -S -s -m "fix(core): expose form-urlencoded POST bodies as request parameters" \
  -m "Servlet 6.1 §3.1.1: query-string values first, then body values; body
charset defaults to ISO-8859-1; a body consumed by parameter parsing is
no longer readable through getInputStream/getReader." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 1.1: `WebAppModel` — the deployment description

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppModel.java`
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/boot/WebAppModelTest.java`

**Interfaces:**
- Produces (used by Tasks 1.3–1.6 and Phase 2):

```java
public record WebAppModel(String contextPath,
                          String displayName,                 // nullable
                          Map<String, String> contextParams,
                          List<ServletDecl> servlets,
                          List<FilterDecl> filters,
                          List<FilterMappingDecl> filterMappings,   // declaration order = chain order
                          List<ListenerDecl> listeners,
                          List<ServletContainerInitializer> initializers,
                          ErrorPageRegistry errorPages,       // existing type, io.vidocq.foy.internal.error
                          int sessionTimeoutMinutes,          // -1 = container default
                          Map<String, String> localeEncodingMappings,
                          int effectiveMajorVersion,
                          int effectiveMinorVersion)
```

Nested records:

```java
public record ServletDecl(String name, Class<? extends Servlet> type,
                          Supplier<? extends Servlet> factory,
                          List<String> urlPatterns, Map<String, String> initParams,
                          int loadOnStartup,          // Integer.MIN_VALUE = absent
                          boolean asyncSupported) {}
public record FilterDecl(String name, Class<? extends Filter> type,
                         Supplier<? extends Filter> factory,
                         Map<String, String> initParams, boolean asyncSupported) {}
public record FilterMappingDecl(String filterName,
                                String urlPattern,        // nullable when servletName is set
                                String servletName,       // nullable when urlPattern is set
                                Set<DispatcherType> dispatcherTypes) {}
public record ListenerDecl(Class<? extends EventListener> type,
                           Supplier<? extends EventListener> factory) {}
public static Builder builder(String contextPath)
```

`Builder` methods: `displayName(String)`, `contextParam(String,String)`,
`servlet(ServletDecl)`, `filter(FilterDecl)`, `filterMapping(FilterMappingDecl)`,
`listener(ListenerDecl)`, `initializer(ServletContainerInitializer)`,
`errorPages(ErrorPageRegistry)`, `sessionTimeoutMinutes(int)`,
`localeEncodingMappings(Map)`, `effectiveVersion(int,int)`, `build()`.
`build()` rejects duplicate servlet names, duplicate filter names, and a
filter mapping whose filter name is not declared (`IllegalStateException`
naming the offender). All collections are defensively copied (`List.copyOf`,
`Map.copyOf` preserving order via `Collections.unmodifiableMap(new LinkedHashMap<>(..))`).

- [ ] **Step 1: Write the failing test**

```java
package io.vidocq.foy.internal.boot;

import io.vidocq.foy.internal.boot.WebAppModel.*;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebAppModelTest {

    static final class S extends HttpServlet {}
    static final class F implements Filter {
        @Override public void doFilter(jakarta.servlet.ServletRequest q, jakarta.servlet.ServletResponse r,
                                       jakarta.servlet.FilterChain c) {}
    }

    static ServletDecl servlet(String name, String... patterns) {
        return new ServletDecl(name, S.class, S::new, List.of(patterns), Map.of(), Integer.MIN_VALUE, true);
    }

    @Test
    void keepsDeclarationOrderOfFilterMappings() {
        var m = WebAppModel.builder("/app")
                .filter(new FilterDecl("a", F.class, F::new, Map.of(), false))
                .filter(new FilterDecl("b", F.class, F::new, Map.of(), false))
                .filterMapping(new FilterMappingDecl("b", "/*", null, EnumSet.of(DispatcherType.REQUEST)))
                .filterMapping(new FilterMappingDecl("a", "/*", null, EnumSet.of(DispatcherType.REQUEST)))
                .build();
        assertEquals(List.of("b", "a"), m.filterMappings().stream().map(FilterMappingDecl::filterName).toList());
    }

    @Test
    void rejectsDuplicateServletNames() {
        var b = WebAppModel.builder("/").servlet(servlet("x", "/a")).servlet(servlet("x", "/b"));
        var e = assertThrows(IllegalStateException.class, b::build);
        assertTrue(e.getMessage().contains("x"));
    }

    @Test
    void rejectsMappingOfUnknownFilter() {
        var b = WebAppModel.builder("/")
                .filterMapping(new FilterMappingDecl("ghost", "/*", null, EnumSet.of(DispatcherType.REQUEST)));
        assertThrows(IllegalStateException.class, b::build);
    }

    @Test
    void isImmutableAgainstCallerCollections() {
        var patterns = new ArrayList<>(List.of("/a"));
        var params = new LinkedHashMap<String, String>(Map.of("k", "v"));
        var m = WebAppModel.builder("/")
                .servlet(new ServletDecl("s", S.class, S::new, patterns, params, 1, true)).build();
        patterns.add("/b");
        params.put("k2", "v2");
        assertEquals(List.of("/a"), m.servlets().getFirst().urlPatterns());
        assertEquals(Map.of("k", "v"), m.servlets().getFirst().initParams());
        assertThrows(UnsupportedOperationException.class, () -> m.servlets().add(servlet("t")));
    }

    @Test
    void defaultsMatchTheHarnessDefaults() {
        var m = WebAppModel.builder("/").build();
        assertEquals(-1, m.sessionTimeoutMinutes());
        assertEquals(6, m.effectiveMajorVersion());
        assertEquals(1, m.effectiveMinorVersion());
        assertNotNull(m.errorPages());
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -ntp -pl foy-core test -Dtest=WebAppModelTest`
Expected: compilation failure, `WebAppModel` does not exist.

- [ ] **Step 3: Implement `WebAppModel`**

Write the record and builder exactly as specified in **Interfaces**. The
compact constructors of `ServletDecl` and `FilterDecl` copy their collections
with `List.copyOf` and `Collections.unmodifiableMap(new LinkedHashMap<>(..))`
and `requireNonNull` name/type/factory. `FilterMappingDecl`'s compact
constructor requires exactly one of `urlPattern` / `servletName`, and
defaults an empty `dispatcherTypes` to `EnumSet.of(REQUEST)` (same rule as
`FilterMapping`). Builder defaults: `sessionTimeoutMinutes = -1`,
`effectiveVersion(6, 1)`, `errorPages = new ErrorPageRegistry()`. Add the
license header copied from `FoyChappeBoot.java`.

- [ ] **Step 4: Run tests**

Run: `./mvnw -ntp -pl foy-core test -Dtest=WebAppModelTest`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppModel.java \
        foy-core/src/test/java/io/vidocq/foy/internal/boot/WebAppModelTest.java
git commit -S -s -m "feat(core): add WebAppModel, the immutable deployment description" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 1.2: Instantiation and `@HandlesTypes` seams

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/boot/ComponentFactory.java`
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/boot/HandlesTypesResolver.java`
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/boot/ComponentFactoryTest.java`

**Interfaces:**
- Produces:

```java
/** Loads and instantiates web components. Phase 1: reflective; Phase 2 replaces
 *  the default with the generated-code registry (APT → Class-File → reflection). */
public interface ComponentFactory {
    Class<?> load(String className) throws ClassNotFoundException;
    <T> T newInstance(Class<T> type) throws jakarta.servlet.ServletException;
    /** False for classes the deployment must ignore (TCK war isolation). */
    default boolean isVisible(Class<?> type) { return true; }

    static ComponentFactory reflective(ClassLoader loader) { ... }
    static ComponentFactory reflective(ClassLoader loader, java.util.Set<String> visibleClassNames) { ... }
}

/** Computes the class set passed to ServletContainerInitializer.onStartup (§8.2.4). */
@FunctionalInterface
public interface HandlesTypesResolver {
    /** @return the matching classes, or {@code null} when none / not resolvable. */
    java.util.Set<Class<?>> resolve(jakarta.servlet.ServletContainerInitializer sci);
    HandlesTypesResolver NONE = sci -> null;
}
```

`reflective(..).newInstance` wraps `ReflectiveOperationException` in
`ServletException("cannot instantiate " + type.getName(), e)`; `load` uses
`Class.forName(name, true, loader)`. `isVisible` with a name set returns
`visibleClassNames.contains(type.getName())`.

- [ ] **Step 1: Write the failing test**

```java
package io.vidocq.foy.internal.boot;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ComponentFactoryTest {

    public static final class Ok extends HttpServlet {}
    public static final class Boom extends HttpServlet {
        public Boom() { throw new IllegalStateException("nope"); }
    }

    private final ComponentFactory f = ComponentFactory.reflective(getClass().getClassLoader());

    @Test
    void loadsAndInstantiates() throws Exception {
        Class<?> c = f.load(Ok.class.getName());
        assertInstanceOf(Ok.class, f.newInstance(c));
    }

    @Test
    void constructorFailureBecomesServletException() {
        var e = assertThrows(ServletException.class, () -> f.newInstance(Boom.class));
        assertTrue(e.getMessage().contains(Boom.class.getName()));
    }

    @Test
    void visibilityRestrictsToTheGivenNames() {
        var restricted = ComponentFactory.reflective(getClass().getClassLoader(), Set.of(Ok.class.getName()));
        assertTrue(restricted.isVisible(Ok.class));
        assertFalse(restricted.isVisible(Boom.class));
        assertTrue(f.isVisible(Boom.class));
    }

    @Test
    void noneResolverYieldsNull() {
        assertNull(HandlesTypesResolver.NONE.resolve((c, ctx) -> {}));
    }
}
```

- [ ] **Step 2: Run it to see it fail** — `./mvnw -ntp -pl foy-core test -Dtest=ComponentFactoryTest` → compilation failure.

- [ ] **Step 3: Implement** both interfaces as in **Interfaces** (private static
nested `Reflective` record implementing `ComponentFactory`, holding the loader
and a nullable name set).

- [ ] **Step 4: Run** — same command, 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add foy-core/src/main/java/io/vidocq/foy/internal/boot/{ComponentFactory,HandlesTypesResolver}.java \
        foy-core/src/test/java/io/vidocq/foy/internal/boot/ComponentFactoryTest.java
git commit -S -s -m "feat(core): add ComponentFactory and HandlesTypesResolver seams" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 1.3: `WebAppDeployer` + `Deployment` — the lifecycle in foy-core

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/boot/DeployOptions.java`
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/boot/Deployment.java`
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppDeployer.java`
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/boot/InitFailureServlet.java`
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/boot/WebAppDeployerEndToEndTest.java`
- Reference (logic to port, do not modify yet): `foy-tck/src/main/java/io/vidocq/foy/tck/ServletTestHarness.java:117-132` (close) and `:270-508` (start, materialize*)

**Interfaces:**
- Consumes: `WebAppModel` (1.1), `ComponentFactory`, `HandlesTypesResolver` (1.2),
  `VidocqServletContext` (`registerStaticServlet`, `registerStaticFilter`,
  `reserve*`, `dynamicServletRegistrations()`, `dynamicFilterRegistrations()`,
  `markInitialized()`, setters used by the harness), `ListenerRegistry`,
  `SessionManager(SessionStore, VidocqServletContext, int seconds)`,
  `InMemorySessionStore`, `ChappeServletBridge(ServletDispatcher, FilterRegistry, VidocqServletContext, SessionManager, String)`,
  `CrossContextRegistry.register/unregister`, `ServletConfigImpl`, `FilterConfigImpl`.
- Produces:

```java
public record DeployOptions(io.vidocq.foy.spi.security.SecurityProvider securityProvider, // nullable
                            VidocqServletContext.ResourceProvider resourceProvider,         // nullable
                            String servletContextName,                                     // nullable
                            Set<String> reservedServletNames,
                            Set<String> reservedFilterNames,
                            Set<String> reservedUrlPatterns,
                            ComponentFactory componentFactory,
                            HandlesTypesResolver handlesTypes) {
    public static DeployOptions defaults(ClassLoader loader) { ... } // reflective factory, NONE resolver, empty sets
    public DeployOptions withComponentFactory(ComponentFactory f) { ... }
    public DeployOptions withSecurityProvider(SecurityProvider p) { ... }
    public DeployOptions withResourceProvider(VidocqServletContext.ResourceProvider p) { ... }
    public DeployOptions withServletContextName(String n) { ... }
    public DeployOptions withReserved(Set<String> servletNames, Set<String> filterNames, Set<String> urlPatterns) { ... }
}

public final class Deployment implements AutoCloseable {
    public io.vidocq.chappe.api.Handler handler();
    public VidocqServletContext servletContext();
    public ListenerRegistry listeners();
    public List<Servlet> initializedServlets();   // init order
    public List<Filter> initializedFilters();     // init order
    @Override public void close();                // idempotent
}

public final class WebAppDeployer {
    public static Deployment deploy(WebAppModel model, DeployOptions options);
}
```

Deploy algorithm (port of `ServletTestHarness.start()`; keep the comments'
spec references, translated to English):

1. Build `VidocqServletContext(model.contextPath())`; apply error pages,
   locale mappings, effective version, resource provider, context name,
   session timeout (if `> 0`), reserved names/patterns, security provider.
2. Instantiate every `ServletDecl` / `FilterDecl` **once** via its `factory`
   (skip with a WARNING when `!options.componentFactory().isVisible(type)`).
3. `registerStaticServlet(name, type, urlPatterns, initParams, asyncSupported)`
   and `registerStaticFilter(name, type, initParams)` (port of
   `materializeStaticRegistrations`).
4. Context init params, then the `jakarta.servlet.context.tempdir` attribute (§4.8.1).
5. `ListenerRegistry` with the instantiated listeners; attach to context;
   `SessionManager` with `InMemorySessionStore`, 1800 s, attached registry.
6. For each initializer: `sci.onStartup(options.handlesTypes().resolve(sci), ctx)`;
   a `ServletException` is logged WARNING and deployment continues (as today).
7. `registry.fireContextInitialized(ctx)`.
8. Materialise dynamic registrations (port of `materializeDynamicRegistrations`,
   with `Class.forName` → `componentFactory.load`, `getDeclaredConstructor().newInstance()`
   → `componentFactory.newInstance`, `warClassNames` → `isVisible`). Names already
   declared statically win.
9. Build filter mappings in model declaration order, then dynamic ones; a
   servlet-name mapping expands to every url-pattern of that servlet (as today).
10. `init()` servlets: first those with `loadOnStartup >= 0` sorted ascending
    (stable on declaration order), then the others in declaration order
    (eager init of the rest is allowed by §2.3.1 and is the current behaviour).
    Failure → `InitFailureServlet(cause)` mapped on the same patterns
    (`UnavailableException` permanent → `sendError(404)`, temporary → `sendError(503)`,
    other → rethrow the original `ServletException`).
11. `init()` filters in declaration order; failures logged WARNING and the filter excluded.
12. `ctx.markInitialized()`; build `ChappeServletBridge`; `CrossContextRegistry.register(ctx)`.

`Deployment.close()`: filters destroyed in reverse init order, then servlets
in reverse init order (each `destroy()` in its own try/catch, RuntimeException
logged), then `fireContextDestroyed`, then `CrossContextRegistry.unregister`.
A second call is a no-op.

- [ ] **Step 1: Write the failing test**

```java
package io.vidocq.foy.internal.boot;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.boot.WebAppModel.*;
import jakarta.servlet.*;
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
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class WebAppDeployerEndToEndTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    private Server server;
    private int port;
    private Deployment deployment;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        if (deployment != null) deployment.close();
        EVENTS.clear();
    }

    private void deploy(WebAppModel model) {
        deployment = WebAppDeployer.deploy(model, DeployOptions.defaults(getClass().getClassLoader()));
        var r = io.vidocq.foy.internal.TestServerLauncherAccess.start(deployment.handler());
        server = r.server();
        port = r.port();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    public static class Recording extends HttpServlet {
        final String id;
        public Recording() { this("default"); }
        Recording(String id) { this.id = id; }
        @Override public void init() { EVENTS.add("init:" + id); }
        @Override public void destroy() { EVENTS.add("destroy:" + id); }
        @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
            r.getWriter().write(id + ":" + getServletConfig().getInitParameter("k"));
        }
    }

    static ServletDecl decl(String id, int los, String... patterns) {
        return new ServletDecl(id, Recording.class, () -> new Recording(id), List.of(patterns),
                Map.of("k", "v-" + id), los, true);
    }

    @Test
    void oneInstancePerDeclaration() throws Exception {
        deploy(WebAppModel.builder("/").servlet(decl("a", Integer.MIN_VALUE, "/x", "/y")).build());
        assertEquals("a:v-a", get("/x").body());
        assertEquals("a:v-a", get("/y").body());
        assertEquals(List.of("init:a"), EVENTS);
    }

    @Test
    void loadOnStartupOrdersInitThenDeclarationOrder() {
        deploy(WebAppModel.builder("/")
                .servlet(decl("lazy", Integer.MIN_VALUE, "/l"))
                .servlet(decl("two", 2, "/2"))
                .servlet(decl("zero", 0, "/0"))
                .servlet(decl("alsoTwo", 2, "/22"))
                .build());
        assertEquals(List.of("init:zero", "init:two", "init:alsoTwo", "init:lazy"), EVENTS);
    }

    public static class FailingInit extends HttpServlet {
        final ServletException failure;
        FailingInit(ServletException failure) { this.failure = failure; }
        @Override public void init() throws ServletException { throw failure; }
    }

    static ServletDecl failing(String name, String pattern, ServletException e) {
        return new ServletDecl(name, FailingInit.class, () -> new FailingInit(e), List.of(pattern),
                Map.of(), Integer.MIN_VALUE, true);
    }

    @Test
    void initFailuresAnswer500Or404Or503() throws Exception {
        deploy(WebAppModel.builder("/")
                .servlet(failing("plain", "/plain", new ServletException("x")))
                .servlet(failing("perm", "/perm", new UnavailableException("gone")))
                .servlet(failing("temp", "/temp", new UnavailableException("later", 30)))
                .build());
        assertEquals(500, get("/plain").statusCode());
        assertEquals(404, get("/perm").statusCode());
        assertEquals(503, get("/temp").statusCode());
    }

    @Test
    void sciDynamicServletIsServedAndListenerSeesContextInitialized() throws Exception {
        ServletContainerInitializer sci = (classes, ctx) -> {
            EVENTS.add("sci:" + classes);
            ctx.addServlet("dyn", new Recording("dyn")).addMapping("/dyn");
            ctx.addListener(new ServletContextListener() {
                @Override public void contextInitialized(ServletContextEvent e) { EVENTS.add("ctxInit"); }
                @Override public void contextDestroyed(ServletContextEvent e) { EVENTS.add("ctxDestroyed"); }
            });
        };
        deploy(WebAppModel.builder("/").initializer(sci).build());
        assertEquals("dyn:null", get("/dyn").body());
        assertEquals(List.of("sci:null", "ctxInit", "init:dyn"), EVENTS);
    }

    @Test
    void addServletAfterDeployThrows() {
        deploy(WebAppModel.builder("/").build());
        assertThrows(IllegalStateException.class,
                () -> deployment.servletContext().addServlet("late", new Recording("late")));
    }

    public static class ThrowingDestroy extends Recording {
        public ThrowingDestroy() { super("throwing"); }
        @Override public void destroy() { EVENTS.add("destroy:throwing"); throw new IllegalStateException(); }
    }

    @Test
    void closeDestroysInReverseOrder() {
        ServletContainerInitializer sci = (c, ctx) -> ctx.addListener(new ServletContextListener() {
            @Override public void contextDestroyed(ServletContextEvent e) { EVENTS.add("ctxDestroyed"); }
        });
        deploy(WebAppModel.builder("/")
                .servlet(decl("first", 1, "/1"))
                .servlet(new ServletDecl("throwing", ThrowingDestroy.class, ThrowingDestroy::new,
                        List.of("/t"), Map.of(), 2, true))
                .initializer(sci)
                .build());
        EVENTS.clear();
        deployment.close();
        deployment.close();
        assertEquals(List.of("destroy:throwing", "destroy:first", "ctxDestroyed"), EVENTS);
    }

    @Test
    void tempDirAttributeIsSet() {
        deploy(WebAppModel.builder("/").build());
        assertInstanceOf(java.io.File.class,
                deployment.servletContext().getAttribute(ServletContext.TEMPDIR));
    }
}
```

`TestServerLauncher` is package-private in `io.vidocq.foy.internal`. Add
next to it (test sources) a public accessor used by tests in sub-packages:

```java
package io.vidocq.foy.internal;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Server;

/** Public bridge to {@link TestServerLauncher} for tests in sub-packages. */
public final class TestServerLauncherAccess {
    public record Started(Server server, int port) {}
    public static Started start(Handler handler) {
        var r = TestServerLauncher.start(handler);
        return new Started(r.server, r.port);
    }
    private TestServerLauncherAccess() {}
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -ntp -pl foy-core test -Dtest=WebAppDeployerEndToEndTest`
Expected: compilation failure (`WebAppDeployer`, `Deployment`, `DeployOptions` missing).

- [ ] **Step 3: Implement**

`InitFailureServlet`:

```java
package io.vidocq.foy.internal.boot;

import jakarta.servlet.GenericServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** Stands in for a servlet whose init() failed (Servlet 6.1 §2.3.2.1, §2.3.3.2). */
final class InitFailureServlet extends GenericServlet {
    private final ServletException failure;
    InitFailureServlet(ServletException failure) { this.failure = failure; }

    @Override
    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
        if (failure instanceof UnavailableException ue) {
            ((HttpServletResponse) res).sendError(ue.isPermanent() ? 404 : 503, ue.getMessage());
            return;
        }
        // Re-throw so an <error-page> mapped on ServletException is dispatched.
        throw failure;
    }
}
```

`DeployOptions`, `Deployment`, `WebAppDeployer`: implement the algorithm of
**Interfaces** step by step, porting the code of
`ServletTestHarness.start()` / `materializeStaticRegistrations()` /
`materializeDynamicRegistrations()` / `close()`. Keep `WebAppDeployer`
under ~250 lines by splitting private static methods per step
(`newContext`, `registerStatic`, `runInitializers`, `materializeDynamic`,
`initServlets`, `initFilters`). Use `System.getLogger(WebAppDeployer.class.getName())`.

- [ ] **Step 4: Run tests**

Run: `./mvnw -ntp -pl foy-core test`
Expected: all PASS, including the 7 new tests.

- [ ] **Step 5: Commit**

```bash
git add foy-core
git commit -S -s -m "feat(core): add WebAppDeployer, the Servlet deployment lifecycle" \
  -m "Ports the lifecycle that only lived in the TCK harness: static and dynamic
registrations, SCI onStartup, contextInitialized, load-on-startup ordered
init with failure stubs (§2.3), markInitialized (§4.4), cross-context
registration, and a Deployment whose close() destroys in reverse order." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 1.4: The TCK harness delegates to `WebAppDeployer`

**Files:**
- Modify: `foy-tck/src/main/java/io/vidocq/foy/tck/ServletTestHarness.java` (Builder internals, `start()`, `close()`; delete `materializeStaticRegistrations`, `materializeDynamicRegistrations`)

**Interfaces:**
- Consumes: `WebAppModel.builder`, `ServletDecl`, `FilterDecl`, `FilterMappingDecl`,
  `ListenerDecl`, `WebAppDeployer.deploy`, `DeployOptions.defaults(..).with*`,
  `ComponentFactory.reflective(loader, warClassNames)`.
- Produces: unchanged public `ServletTestHarness` / `Builder` API (the
  `VidocqDeployableContainer` and existing callers must compile untouched).

- [ ] **Step 1: Pin current behaviour with a harness test**

Create `foy-tck/src/test/java/io/vidocq/foy/tck/ServletTestHarnessTest.java`
(runs in the default build, no TCK artefacts needed — check that
`foy-tck`'s surefire config without `-Ptck-official` runs plain unit tests;
if it excludes everything, add the test to an `<includes>` of the default
profile):

```java
package io.vidocq.foy.tck;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServletTestHarnessTest {

    @Test
    void servletRegisteredOnTwoPatternsIsInitialisedOnceWithItsParams() throws Exception {
        var inits = new AtomicInteger();
        HttpServlet s = new HttpServlet() {
            @Override public void init() { inits.incrementAndGet(); }
            @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
                r.getWriter().write(getInitParameter("k"));
            }
        };
        try (var h = ServletTestHarness.builder()
                .servlet("/a", s, "S", Map.of("k", "v"))
                .servlet("/b", s, "S", Map.of("k", "v"))
                .contextPath("/app").start()) {
            assertEquals("v", h.get("/app/a").body());
            assertEquals("v", h.get("/app/b").body());
            assertEquals(1, inits.get());
        }
    }
}
```

Run: `./mvnw -ntp -pl foy-core,foy-tck install -DskipTests=false -Dtest=ServletTestHarnessTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS on the current code (this pins behaviour; it is not a red test).

- [ ] **Step 2: Refactor the Builder to accumulate a `WebAppModel`**

Keep the public `servlet(..)` / `filter(..)` overloads, but record into:
- `LinkedHashMap<String, ServletDecl-in-progress>` keyed by servlet name
  (same name + same instance → append the url-pattern; same name + a
  different instance → keep the first and log WARNING, matching today's
  "first registration wins").
- filters: `LinkedHashMap<String, FilterDecl>` keyed by filter name plus an
  ordered `List<FilterMappingDecl>` (one per `filter(..)` call).
- factories are `() -> instance` (the harness receives instances).

`start()` becomes:

```java
        public ServletTestHarness start() {
            var model = toModel();                     // builds WebAppModel from the accumulated state
            var cl = Thread.currentThread().getContextClassLoader();
            var factory = warClassNames == null
                    ? ComponentFactory.reflective(cl)
                    : ComponentFactory.reflective(cl, warClassNames);
            var options = DeployOptions.defaults(cl)
                    .withComponentFactory(factory)
                    .withSecurityProvider(securityProvider)
                    .withResourceProvider(resourceProvider)
                    .withServletContextName(servletContextName)
                    .withReserved(reservedServletNames, reservedFilterNames, reservedUrlPatterns);
            Deployment d = WebAppDeployer.deploy(model, options);
            int port = startServerWithRetry(d.handler());
            return new ServletTestHarness(currentServer, port, contextPath, d);
        }
```

The private constructor stores the `Deployment`; `close()` becomes
`server.stop(); deployment.close();`. Accessors that exposed
`initializedServlets`/`listenerRegistry`/`servletContext` delegate to the
`Deployment`.

- [ ] **Step 3: Run the harness test and foy-core**

Run: `./mvnw -ntp install -Dtest='ServletTestHarnessTest,*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 4: Behavioural-neutrality gate — full TCK**

Run: `./run-official-tck-servlet6.1.sh --all > /tmp/foy-tck-after.log 2>&1; grep -E 'Tests run: [0-9]+, .*Skipped: [0-9]+$' /tmp/foy-tck-after.log | tail -1`
Expected: `Errors:` ≤ 793 (≥ 921 passing). Then diff per-class tallies
against the committed baseline (Task 0.1 Step 4):

```bash
foy-tck/tck-tally.sh /tmp/foy-tck-after.log > /tmp/after.txt
join foy-tck/tck-baseline.txt /tmp/after.txt | awk '$5 > $3 {print "REGRESSION", $0}'
```

Expected: no `REGRESSION` line. Any regression is fixed in the deployer
(not by restoring harness logic) before committing.

- [ ] **Step 5: Commit**

```bash
git add foy-tck
git commit -S -s -m "refactor(tck): ServletTestHarness delegates the lifecycle to WebAppDeployer" \
  -m "TCK: <N> passing (baseline 921), no class regression." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 1.5: `web.xml` in the product path, with the §8.2.3 merge rules

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/boot/DescriptorMerger.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/webxml/WebXmlParser.java` (parse `metadata-complete` and `<load-on-startup>`)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/webxml/WebAppDescriptor.java` (`boolean metadataComplete()`, `ServletDef.loadOnStartup()`)
- Delete: `foy-core/src/main/java/io/vidocq/foy/internal/webxml/WebXmlContributor.java` (dead code)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/boot/DescriptorMergerTest.java`
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/webxml/WebXmlParserTest.java` (add cases)

**Interfaces:**
- Consumes: `WebAppDescriptor`, `WebAppModel.Builder`, `ComponentFactory`.
- Produces:

```java
public final class DescriptorMerger {
    /** Annotation-derived declarations (from CDI discovery now, generated components in Phase 2). */
    public record AnnotatedComponents(List<ServletDecl> servlets, List<FilterDecl> filters,
                                      List<FilterMappingDecl> filterMappings, List<ListenerDecl> listeners) {
        public static AnnotatedComponents none() { ... }
    }
    /** Fills {@code target} from web.xml + annotations following Servlet 6.1 §8.2.3. */
    public static void merge(WebAppDescriptor webXml, AnnotatedComponents annotated,
                             ComponentFactory factory, WebAppModel.Builder target)
            throws jakarta.servlet.ServletException;
}
```

Merge rules (§8.2.3) implemented and tested:
- `metadata-complete="true"` → annotated components ignored entirely.
- Same servlet name in both: web.xml class wins; url-patterns from web.xml
  **replace** the annotation's when web.xml declares a `<servlet-mapping>`
  for that name, otherwise the annotation's patterns are kept; init params
  are the union with web.xml values winning; `load-on-startup` from web.xml
  if present.
- Same filter name: same rules for init params; web.xml `<filter-mapping>`s
  replace the annotation's mappings for that filter.
- Listeners: union, web.xml first, de-duplicated by class.
- web.xml servlets/filters/listeners are loaded and instantiated with
  `factory.load` + `factory.newInstance` inside the `Supplier`.

- [ ] **Step 1: Write the failing tests**

`WebXmlParserTest` — add:

```java
    @Test
    void parsesMetadataCompleteAndLoadOnStartup() throws Exception {
        String xml = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1" metadata-complete="true">
              <servlet><servlet-name>s</servlet-name><servlet-class>a.S</servlet-class>
                <load-on-startup>3</load-on-startup></servlet>
            </web-app>""";
        var d = WebXmlParser.parse(new java.io.ByteArrayInputStream(xml.getBytes()));
        assertTrue(d.metadataComplete());
        assertEquals(3, d.servlets().getFirst().loadOnStartup());
    }

    @Test
    void loadOnStartupAbsentIsMinValue() throws Exception {
        String xml = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <servlet><servlet-name>s</servlet-name><servlet-class>a.S</servlet-class></servlet>
            </web-app>""";
        var d = WebXmlParser.parse(new java.io.ByteArrayInputStream(xml.getBytes()));
        assertFalse(d.metadataComplete());
        assertEquals(Integer.MIN_VALUE, d.servlets().getFirst().loadOnStartup());
    }
```

`DescriptorMergerTest`:

```java
package io.vidocq.foy.internal.boot;

import io.vidocq.foy.internal.boot.DescriptorMerger.AnnotatedComponents;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.webxml.WebXmlParser;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DescriptorMergerTest {

    public static class Annotated extends HttpServlet {}
    public static class FromXml extends HttpServlet {}

    private static final ComponentFactory F = ComponentFactory.reflective(DescriptorMergerTest.class.getClassLoader());

    private static WebAppModel merge(String webXml, AnnotatedComponents ann) throws Exception {
        var d = WebXmlParser.parse(new ByteArrayInputStream(webXml.getBytes()));
        var b = WebAppModel.builder("/");
        DescriptorMerger.merge(d, ann, F, b);
        return b.build();
    }

    private static AnnotatedComponents annotatedServlet(String name, String pattern, Map<String, String> params) {
        return new AnnotatedComponents(List.of(new ServletDecl(name, Annotated.class, Annotated::new,
                List.of(pattern), params, Integer.MIN_VALUE, true)), List.of(), List.of(), List.of());
    }

    private static final String HEAD = "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\"";

    @Test
    void metadataCompleteIgnoresAnnotations() throws Exception {
        var m = merge(HEAD + " metadata-complete=\"true\"></web-app>",
                annotatedServlet("a", "/a", Map.of()));
        assertTrue(m.servlets().isEmpty());
    }

    @Test
    void webXmlMappingReplacesAnnotationPatternsAndParamsMergeWithXmlWinning() throws Exception {
        var m = merge(HEAD + """
                >
                  <servlet><servlet-name>s</servlet-name>
                    <servlet-class>%s</servlet-class>
                    <init-param><param-name>k</param-name><param-value>xml</param-value></init-param>
                  </servlet>
                  <servlet-mapping><servlet-name>s</servlet-name><url-pattern>/xml</url-pattern></servlet-mapping>
                </web-app>""".formatted(FromXml.class.getName()),
                annotatedServlet("s", "/ann", Map.of("k", "ann", "only", "ann")));
        var s = m.servlets().getFirst();
        assertEquals(FromXml.class, s.type());
        assertEquals(List.of("/xml"), s.urlPatterns());
        assertEquals(Map.of("k", "xml", "only", "ann"), s.initParams());
        assertInstanceOf(FromXml.class, s.factory().get());
    }

    @Test
    void annotationPatternsKeptWhenWebXmlHasNoMappingForTheName() throws Exception {
        var m = merge(HEAD + """
                >
                  <servlet><servlet-name>s</servlet-name><servlet-class>%s</servlet-class></servlet>
                </web-app>""".formatted(FromXml.class.getName()),
                annotatedServlet("s", "/ann", Map.of()));
        assertEquals(List.of("/ann"), m.servlets().getFirst().urlPatterns());
    }

    @Test
    void distinctNamesAreBothKept() throws Exception {
        var m = merge(HEAD + """
                >
                  <servlet><servlet-name>x</servlet-name><servlet-class>%s</servlet-class></servlet>
                  <servlet-mapping><servlet-name>x</servlet-name><url-pattern>/x</url-pattern></servlet-mapping>
                </web-app>""".formatted(FromXml.class.getName()),
                annotatedServlet("a", "/a", Map.of()));
        assertEquals(List.of("x", "a"), m.servlets().stream().map(ServletDecl::name).toList());
    }
}
```

- [ ] **Step 2: Run them to see them fail** — `./mvnw -ntp -pl foy-core test -Dtest='DescriptorMergerTest,WebXmlParserTest'` → compilation failure.

- [ ] **Step 3: Implement**

Parser: read `metadata-complete` on the root element
(`Boolean.parseBoolean(root.getAttribute("metadata-complete"))`) and
`<load-on-startup>` (trimmed int; empty element → `0`, per the schema "any
non-negative"; absent → `Integer.MIN_VALUE`). Add the fields to
`WebAppDescriptor` / `ServletDef` (keep the existing convenience
constructors, defaulting `loadOnStartup = Integer.MIN_VALUE`,
`metadataComplete = false`). Implement `DescriptorMerger` per the rules
above. Delete `WebXmlContributor.java`; `grep -rn WebXmlContributor .` must
return nothing.

- [ ] **Step 4: Run** — `./mvnw -ntp -pl foy-core test` → all PASS.

- [ ] **Step 5: Commit**

```bash
git add -A foy-core
git commit -S -s -m "feat(core): merge web.xml and annotations per Servlet 6.1 §8.2.3" \
  -m "Parses metadata-complete and load-on-startup, adds DescriptorMerger and
removes the unused WebXmlContributor." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 1.6: `FoyChappeBoot` runs the real lifecycle

**Files:**
- Modify: `foy-chappe/src/main/java/io/vidocq/foy/chappe/FoyChappeBoot.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppDiscovery.java` (return `AnnotatedComponents` instead of instances)
- Modify: `foy-chappe/pom.xml` (test dependencies: junit-jupiter, chappe-core if not present)
- Test: `foy-chappe/src/test/java/io/vidocq/foy/chappe/FoyChappeBootTest.java`

**Interfaces:**
- Consumes: `WebAppDeployer`, `DescriptorMerger`, `WebXmlParser`, `DeployOptions`.
- Produces (public API change, no consumer exists in vidocq/cassini as of 2026-10-07):

```java
public static final class Builder {
    public Builder beanManager(BeanManager bm);              // optional now
    public Builder contextPath(String path);
    public Builder sessionTimeoutSeconds(int seconds);
    public Builder classLoader(ClassLoader cl);              // where WEB-INF/web.xml / META-INF/web.xml are looked up
    public Builder webXml(java.io.InputStream in);           // explicit descriptor, wins over classLoader lookup
    public Optional<Mounted> build() throws jakarta.servlet.ServletException;
}
public record Mounted(Handler handler, String mountPrefix, Deployment deployment) implements AutoCloseable {
    public VidocqServletContext servletContext();            // deployment.servletContext()
    @Override public void close();                           // deployment.close()
}
```

`fireContextInitialized()` / `fireContextDestroyed()` are removed: the
deployer fires `contextInitialized` before the handler exists (§4.4 order),
and `close()` fires `contextDestroyed`.

`WebAppDiscovery.discover(BeanManager)` returns `AnnotatedComponents` whose
factories are `() -> bm.getReference(bean, type, bm.createCreationalContext(bean))`
(called once by the deployer) — reading the annotation values (url
patterns, **init params**, **loadOnStartup**, **asyncSupported**,
filter **servletNames** and **dispatcherTypes**) that it ignores today. It
still uses `getAnnotation`; Phase 2 replaces it with the generated index.

- [ ] **Step 1: Write the failing test** (no CDI: exercises the web.xml path)

```java
package io.vidocq.foy.chappe;

import io.vidocq.chappe.api.Server;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FoyChappeBootTest {

    public static final AtomicInteger INITS = new AtomicInteger();
    public static final AtomicInteger DESTROYS = new AtomicInteger();

    public static class Hello extends HttpServlet {
        @Override public void init() { INITS.incrementAndGet(); }
        @Override public void destroy() { DESTROYS.incrementAndGet(); }
        @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws IOException {
            r.getWriter().write("hello " + getInitParameter("who"));
        }
    }

    @Test
    void webXmlOnlyApplicationIsInitialisedServedAndDestroyed() throws Exception {
        String xml = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <servlet><servlet-name>h</servlet-name><servlet-class>%s</servlet-class>
                <init-param><param-name>who</param-name><param-value>foy</param-value></init-param>
                <load-on-startup>1</load-on-startup></servlet>
              <servlet-mapping><servlet-name>h</servlet-name><url-pattern>/hello</url-pattern></servlet-mapping>
            </web-app>""".formatted(Hello.class.getName());

        var mounted = FoyChappeBoot.builder().contextPath("/")
                .classLoader(getClass().getClassLoader())
                .webXml(new ByteArrayInputStream(xml.getBytes()))
                .build().orElseThrow();
        assertEquals(1, INITS.get(), "load-on-startup servlet initialised at deploy time");

        int port;
        try (var s = new ServerSocket(0)) { port = s.getLocalPort(); }
        Server server = Server.builder().host("127.0.0.1").port(port).handler(mounted.handler()).build();
        server.start();
        try {
            var resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/hello")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("hello foy", resp.body());
        } finally {
            server.stop();
            mounted.close();
        }
        assertEquals(1, DESTROYS.get());
    }

    @Test
    void emptyApplicationYieldsEmpty() throws Exception {
        assertTrue(FoyChappeBoot.builder().classLoader(new ClassLoader(null) {}).build().isEmpty());
    }
}
```

- [ ] **Step 2: Run it to see it fail** — `./mvnw -ntp -pl foy-core,foy-chappe install -Dtest=FoyChappeBootTest -Dsurefire.failIfNoSpecifiedTests=false` → compilation failure (`classLoader`, `webXml`, `close` missing).

- [ ] **Step 3: Implement**

`build()`:
1. `annotated = beanManager == null ? AnnotatedComponents.none() : WebAppDiscovery.discover(beanManager)`.
2. Descriptor: explicit `webXml` stream, else `classLoader.getResourceAsStream("WEB-INF/web.xml")`,
   else `"META-INF/web.xml"`, else `WebAppDescriptor.empty()`.
3. If descriptor empty and annotated empty → log INFO (existing message) and `Optional.empty()`.
4. `var b = WebAppModel.builder(contextPath).sessionTimeoutMinutes(...)`;
   `DescriptorMerger.merge(descriptor, annotated, ComponentFactory.reflective(classLoader), b)`.
5. `Deployment d = WebAppDeployer.deploy(b.build(), DeployOptions.defaults(classLoader))`.
6. Keep the INFO mapping logs, sourced from the model.

Note: `sessionTimeoutSeconds` is converted to minutes (rounding up) for the
model; the web.xml value, when present, wins.

- [ ] **Step 4: Run** — `./mvnw -ntp install` → all modules' tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A foy-core foy-chappe
git commit -S -s -m "feat(chappe)!: FoyChappeBoot deploys through WebAppDeployer" \
  -m "The product boot path now initialises and destroys servlets and filters,
honours web.xml (WEB-INF/ or META-INF/), runs initializers and dynamic
registrations, and reads init params, load-on-startup, asyncSupported and
filter servlet-names/dispatcher types from the annotations.

BREAKING CHANGE: Mounted.fireContextInitialized()/fireContextDestroyed() are
replaced by Mounted.close(); contextInitialized now fires during build()." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 1.7: Phase exit — TCK re-run and documentation

**Files:**
- Modify: `TCK.md` (new §0 entry), `docs/en/modules/ROOT/pages/internals.adoc` (boot section), `docs/en/modules/ROOT/pages/usage.adoc` (FoyChappeBoot example)

- [ ] **Step 1:** `./run-official-tck-servlet6.1.sh --all`, diff per-class tallies as in Task 1.4 Step 4. Expected ≥ 921, no regression.
- [ ] **Step 2:** `TCK.md` entry "Product bootstrap (Phase 1)" with the figures and the sentence "the lifecycle is now executed by foy-core's `WebAppDeployer`; the harness only adapts Arquillian archives".
- [ ] **Step 3:** Update `internals.adoc` (describe `WebAppModel` → `WebAppDeployer` → `Deployment`) and `usage.adoc` (new `FoyChappeBoot` example with `close()`); remove any mention of `fireContextInitialized`.
- [ ] **Step 4: Commit** — `docs: describe the foy-core deployment lifecycle (Phase 1 exit)`.
