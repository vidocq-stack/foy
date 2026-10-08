# Phase 3 — Deployment Descriptors and Pluggability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Foy assembles an application from `web.xml`, every `META-INF/web-fragment.xml` (ordered per §8.2.2, merged per §8.2.3), annotations, `ServletContainerInitializer`s and `META-INF/resources`, in the native war-less layout, and the TCK harness feeds WARs through the same product merge.

**Architecture:** the parser learns the fragment root and the missing schema elements; a pure `FragmentOrderer` implements §8.2.2; a `FragmentMerger` folds web.xml plus the ordered fragments into one effective `WebAppDescriptor` (conflicts → `ServletException`), which the existing `DescriptorMerger` then merges with annotations. `FoyChappeBoot` discovers fragments, SCIs and `META-INF/resources` on the class/module path; `VidocqDeployableContainer` opens `WEB-INF/lib` jars and calls the same code instead of its private registration.

**Tech Stack:** Java 25, Jakarta Servlet 6.1 (`web-app_6_1.xsd`, `web-fragment_6_1.xsd`), JDK DOM parser, JUnit 5, foy-core `TestServerLauncher`, official Servlet TCK via Arquillian.

**Spec:** `docs/superpowers/plans/2026-10-07-servlet-completion-roadmap.md` §"Phase 3", the Phase 1 follow-ups line "Phase 3", the "Phase 2 exit / follow-ups" section; Jakarta Servlet 6.1 §8.1, §8.2.1–§8.2.4, §4.6, §10.7–§10.13. Research behind the task sizes: TCK pluggability analysis (2026-10-08, summarised in "Evidence").

## Evidence (Phase 2 exit run, 928/1714)

- `pluggability.*` 5/646. ~622 failures share one cause: the 53 `pluggability.api` WARs have **no** `web.xml`; every servlet, mapping, filter, listener, error page and session-config lives in `WEB-INF/lib/fragment-1.jar!/META-INF/web-fragment.xml`, which nothing reads (`servlets=[]`, 610 × "Unexpected 404"). Fragments also carry `session-config`, `error-page`, `context-param`, `mime-mapping`, `welcome-file-list`, `locale-encoding-mapping-list`.
- 10 more need ordering: `pluggability.aordering*` (5, absolute), `pluggability.fragment.FragmentTests` (4, relative), `spec.pluggability.ordering.PluggabilityOrderingTests` (1). They also need **first-wins duplicate `<init-param>`** (`WebXmlParser.parseInitParams` uses `put`; the TCK declares `msg1=first` then `msg1=ignore` and expects `first`).
- `spec.async.AsyncTests` 22/39 failing and `spec.annotationservlet.webfilter.WebFilterTests` 1: the harness bypasses `DescriptorMerger` (filters forced `asyncSupported=true`, annotated filter `servletNames` ignored, `metadata-complete` and `load-on-startup` dropped) and the async flag is not recomputed on dispatch.
- Not exercised by the TCK (Foy's own tests must cover them): `@HandlesTypes`, `META-INF/resources`, SCIs in library jars, cookie-config, tracking-mode, request/response encodings, multipart-config, ordering cycles.
- Expected exit: pluggability ≈ 635/646, overall ≈ 1585/1714 (≈ 92 %). Residual ~9 pluggability tests are Phase 4/6 bugs their `api.*` twins already show.

## Global Constraints

- Runtime modules (foy-api, foy-core, foy-chappe, foy-cdi-vauban) keep zero dependency beyond Jakarta APIs + chappe/vauban. No XML library: JDK DOM only, DOCTYPE disallowed (as today).
- Static codegen doctrine: no new `getAnnotation(` / `getDeclaredConstructor(` / `Class.forName(` in foy-core main outside the `NoProductReflectionTest` allow-list; instances through `ComponentFactory` / `WebComponentRegistry`.
- Java Modules: say "Java Modules", never "JPMS". `META-INF/...` resources are not encapsulated, so `ClassLoader.getResources("META-INF/web-fragment.xml")` is the native discovery mechanism on both class path and module path.
- Virtual threads for anything scheduled.
- English everywhere; Conventional Commits; `git commit -S -s`; trailers `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi`.
- Gate (phase exit): full TCK ≥ 928/1714 with zero per-class regression against `foy-tck/tck-baseline.txt`, `reflection=0` in every `tiers=Stats` line, `pluggability` ≥ 600/646.

## Review Focus

1. **Unnamed fragment under `<absolute-ordering>` without `<others/>`** — the fragment is excluded (its servlets, listeners, SCIs and annotations are absent), the deployment succeeds (Task 3.3 test `unnamedFragmentExcludedWithoutOthers`, Task 3.7 end-to-end).
2. **Two fragments declare the same servlet name (or context-param) with different values and web.xml does not** — deployment fails with a `ServletException` naming the element and both fragment ids (§8.2.3); if web.xml declares it, web.xml wins silently (Task 3.4 tests `conflictBetweenFragmentsFails`, `webXmlResolvesConflict`).
3. **`metadata-complete="true"` on one fragment** — only the annotations of classes from *that* jar are ignored; other jars' annotations still apply; `metadata-complete="true"` on web.xml ignores every annotation and every fragment descriptor, but SCIs still run (§8.2.1, §8.2.4) (Task 3.4 and 3.6 tests).
4. **The same jar reachable twice** (duplicate class-path entry, or class path + module path) — its fragment is merged once (dedup by normalised URL) (Task 3.6 test `duplicateJarMergedOnce`).
5. **Malformed fragment / wrong root** — a `web-fragment.xml` that is not well-formed, or a `web.xml` whose root is `<web-fragment>`, fails the deployment with a `ServletException` naming the source URL, never an NPE or a silently empty app (Task 3.1 test `wrongRootIsRejected`, Task 3.6 test `malformedFragmentNamesTheJar`).

---

### Task 3.0: Residuals from Phase 2 and roadmap bookkeeping

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppDeployer.java` (dynamic `addServlet/addFilter(name, String className)` instantiate path)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/dispatcher/FilterMapping.java` (4-argument constructor)
- Modify: `docs/en/modules/ROOT/pages/usage.adoc` (~line 28, filter order sentence)
- Modify: `docs/superpowers/plans/2026-10-07-servlet-completion-roadmap.md` ("Known vauban gaps": now filed as Vidocq/vauban#130, #131, #132 and recorded in vauban `BUG.md` BUG-20261008-02..04)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/boot/WebAppDeployerTest.java` (or the existing deployer end-to-end test class)

**Interfaces:**
- Produces: dynamic registration of a class name that is not a `Servlet`/`Filter` is skipped with one WARNING naming the registration and the class (same policy as web.xml components, Task 2.8b item 3) instead of a raw `ClassCastException`; `FilterMapping`'s short constructor defaults `asyncSupported` to **false**.

- [ ] **Step 1: Failing test** — in the deployer test class, an SCI calls `ctx.addServlet("bad", "java.lang.Object")` then `ctx.addServlet("good", GoodServlet.class.getName())` with a mapping for each; assert deployment succeeds, `GET /good` → 200, `GET /bad` → 404, and exactly one WARNING containing `bad` and `java.lang.Object` (use `io.vidocq.foy.internal.LogCapture`).
- [ ] **Step 2: Run** `./mvnw -ntp -pl foy-core test -Dtest=WebAppDeployerTest` → FAIL (`ClassCastException`).
- [ ] **Step 3: Implement** — catch `ClassCastException` where the dynamic class is cast (`asSubclass`) and apply the skip+WARNING path; change the `FilterMapping` 4-arg constructor to pass `false`, and grep its callers (`onRequest`…) to confirm none relied on `true` (a caller that must be async-capable passes the flag explicitly).
- [ ] **Step 4: Docs** — `usage.adoc:28`: "Foy uses the discovery order: the `CdiWebComponents` index order first, then any web-annotated beans missing from the indexes (BeanManager order)." Roadmap: replace "issues not opened" by the three issue links.
- [ ] **Step 5: Run** `./mvnw -ntp -pl foy-core,foy-chappe install` → PASS.
- [ ] **Step 6: Commit** — `fix(core): skip dynamic registrations of the wrong type and default filter mappings to non-async`.

---

### Task 3.1: Parser — fragment root, ordering elements, tri-state async, robust values

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/webxml/WebXmlParser.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/webxml/WebAppDescriptor.java`
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/webxml/Ordering.java`
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/webxml/WebXmlParserFragmentTest.java`
- Modify (callers of the changed records): `foy-core/.../boot/DescriptorMerger.java`, `foy-tck/.../VidocqDeployableContainer.java`, `foy-tck/.../ServletTestHarness.java`, any test constructing `ServletDef`/`FilterDef` (`grep -rn "new ServletDef\|new FilterDef"`).

**Interfaces:**
- Produces:

```java
package io.vidocq.foy.internal.webxml;

/** §8.2.2 ordering data of one descriptor. */
public record Ordering(List<String> before, boolean beforeOthers,
                       List<String> after, boolean afterOthers) {
    public static final Ordering NONE = new Ordering(List.of(), false, List.of(), false);
    public Ordering { before = List.copyOf(before); after = List.copyOf(after); }
}

public final class WebAppDescriptor {
    public enum Kind { WEB_APP, WEB_FRAGMENT }
    /** Marker inside {@link #absoluteOrdering()} for {@code <others/>}. */
    public static final String OTHERS = "\u0000others";

    public Kind kind();                                   // from the root element
    public String fragmentName();                         // <name>, null when absent (fragments only)
    public Ordering ordering();                           // fragments only, Ordering.NONE when absent
    /** web.xml only; null when there is no <absolute-ordering>; may contain OTHERS. */
    public List<String> absoluteOrdering();

    // tri-state async: null = element absent
    public record ServletDef(String name, String className, Map<String, String> initParams,
                             Boolean asyncSupported, int loadOnStartup) { ... }
    public record FilterDef(String name, String className, Map<String, String> initParams,
                            Boolean asyncSupported) { ... }
}

public final class WebXmlParser {
    /** Parses a web.xml; a {@code <web-fragment>} root is rejected. */
    public static WebAppDescriptor parse(InputStream in) throws IOException;
    /** Parses a web-fragment.xml; a {@code <web-app>} root is rejected. */
    public static WebAppDescriptor parseFragment(InputStream in) throws IOException;
}
```

Rules: root local name must be `web-app` (for `parse`) or `web-fragment` (for `parseFragment`), else `IOException("expected <web-app> root, found <web-fragment>")`; duplicate `<init-param>` names keep the **first** value (`putIfAbsent`); `<load-on-startup>` that is empty → `Integer.MIN_VALUE` (lazy: `web-common_6_1.xsd` allows an empty element, meaning the container loads it whenever it chooses); not an integer → `Integer.MIN_VALUE` plus one WARNING naming the servlet and the value; an unknown `<dispatcher>` value → `IOException` naming the value; `<async-supported>` absent → `null`. `<absolute-ordering>` in a fragment and `<ordering>` in a web.xml are ignored with a WARNING (§8.2.2). Existing convenience constructors keep their meaning with `Boolean.FALSE` replaced by `null` where the old code meant "absent".

- [ ] **Step 1: Write the failing test**

```java
package io.vidocq.foy.internal.webxml;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebXmlParserFragmentTest {

    private static ByteArrayInputStream xml(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesFragmentNameAndRelativeOrdering() throws IOException {
        var d = WebXmlParser.parseFragment(xml("""
                <web-fragment xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
                  <name>A</name>
                  <ordering>
                    <after><name>B</name></after>
                    <before><others/></before>
                  </ordering>
                </web-fragment>"""));
        assertEquals(WebAppDescriptor.Kind.WEB_FRAGMENT, d.kind());
        assertEquals("A", d.fragmentName());
        assertEquals(List.of("B"), d.ordering().after());
        assertFalse(d.ordering().afterOthers());
        assertTrue(d.ordering().beforeOthers());
    }

    @Test
    void parsesAbsoluteOrderingWithOthers() throws IOException {
        var d = WebXmlParser.parse(xml("""
                <web-app version="6.1"><absolute-ordering>
                  <name>B</name><others/><name>A</name>
                </absolute-ordering></web-app>"""));
        assertEquals(List.of("B", WebAppDescriptor.OTHERS, "A"), d.absoluteOrdering());
    }

    @Test
    void noAbsoluteOrderingIsNull() throws IOException {
        assertNull(WebXmlParser.parse(xml("<web-app version=\"6.1\"/>")).absoluteOrdering());
    }

    @Test
    void wrongRootIsRejected() {
        var e = assertThrows(IOException.class,
                () -> WebXmlParser.parse(xml("<web-fragment version=\"6.1\"/>")));
        assertTrue(e.getMessage().contains("web-fragment"), e.getMessage());
        assertThrows(IOException.class, () -> WebXmlParser.parseFragment(xml("<web-app/>")));
    }

    @Test
    void duplicateInitParamKeepsTheFirstValue() throws IOException {
        var d = WebXmlParser.parse(xml("""
                <web-app><servlet><servlet-name>s</servlet-name><servlet-class>x.S</servlet-class>
                  <init-param><param-name>msg1</param-name><param-value>first</param-value></init-param>
                  <init-param><param-name>msg1</param-name><param-value>ignore</param-value></init-param>
                </servlet></web-app>"""));
        assertEquals(Map.of("msg1", "first"), d.servlets().getFirst().initParams());
    }

    @Test
    void asyncSupportedIsTriState() throws IOException {
        var d = WebXmlParser.parse(xml("""
                <web-app>
                  <servlet><servlet-name>a</servlet-name><servlet-class>x.A</servlet-class></servlet>
                  <servlet><servlet-name>f</servlet-name><servlet-class>x.F</servlet-class>
                    <async-supported>false</async-supported></servlet>
                  <filter><filter-name>t</filter-name><filter-class>x.T</filter-class>
                    <async-supported>true</async-supported></filter>
                </web-app>"""));
        assertNull(d.servlets().get(0).asyncSupported());
        assertEquals(Boolean.FALSE, d.servlets().get(1).asyncSupported());
        assertEquals(Boolean.TRUE, d.filters().getFirst().asyncSupported());
    }

    @Test
    void malformedLoadOnStartupIsLazy() throws IOException {
        var d = WebXmlParser.parse(xml("""
                <web-app><servlet><servlet-name>s</servlet-name><servlet-class>x.S</servlet-class>
                  <load-on-startup>soon</load-on-startup></servlet></web-app>"""));
        assertEquals(Integer.MIN_VALUE, d.servlets().getFirst().loadOnStartup());
    }

    @Test
    void unknownDispatcherIsRejected() {
        assertThrows(IOException.class, () -> WebXmlParser.parse(xml("""
                <web-app><filter-mapping><filter-name>f</filter-name><url-pattern>/*</url-pattern>
                  <dispatcher>TELEPORT</dispatcher></filter-mapping></web-app>""")));
    }
}
```

- [ ] **Step 2: Run** `./mvnw -ntp -pl foy-core test -Dtest=WebXmlParserFragmentTest` → compilation failure.
- [ ] **Step 3: Implement** the parser/descriptor changes; update `DescriptorMerger` to the tri-state rule **now** (§8.2.3: a web.xml value overrides the annotation; `null` means "take the annotation"): `boolean async = def.asyncSupported() != null ? def.asyncSupported() : (annotated != null && annotated.asyncSupported());` for servlets and filters (replaces the `||`). Fix every compile error in callers.
- [ ] **Step 4: Run** `./mvnw -ntp -pl foy-core,foy-chappe install` and `./mvnw -ntp -Ptck -pl foy-tck test-compile` → PASS. Add a `DescriptorMergerTest` case `webXmlAsyncFalseOverridesAnnotationTrue`.
- [ ] **Step 5: Commit** — `feat(webxml): parse web-fragment.xml roots, ordering and tri-state async-supported`.

---

### Task 3.2: Parser — the remaining `web-app_6_1` elements

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/webxml/WebXmlParser.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/webxml/WebAppDescriptor.java`
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/webxml/SecurityDefs.java` (records only)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/webxml/WebXmlParserSchemaTest.java`

**Interfaces:**
- Produces on `WebAppDescriptor` (all immutable, empty/null when absent):

```java
public List<String> welcomeFiles();                       // declaration order
public Map<String, String> mimeMappings();                // extension (no dot, lower-case) -> mime type
public String requestCharacterEncoding();                 // null when absent
public String responseCharacterEncoding();
public String defaultContextPath();                       // null when absent
public boolean denyUncoveredHttpMethods();
public CookieConfigDef cookieConfig();                    // null when absent
public Set<SessionTrackingMode> trackingModes();          // empty when absent
public List<SecurityDefs.SecurityConstraintDef> securityConstraints();
public SecurityDefs.LoginConfigDef loginConfig();         // null when absent
public List<String> securityRoles();

public record CookieConfigDef(String name, String domain, String path, String comment,
                              Boolean httpOnly, Boolean secure, Integer maxAge,
                              Map<String, String> attributes) {}
// ServletDef gains: MultipartConfigDef multipartConfig (nullable), boolean enabled (default true),
//                   String runAs (nullable), String jspFile (nullable)
public record MultipartConfigDef(String location, long maxFileSize, long maxRequestSize,
                                 int fileSizeThreshold) {}
```

```java
package io.vidocq.foy.internal.webxml;

/** Security elements, parsed in Phase 3 and enforced in Phase 6. */
public final class SecurityDefs {
    public record WebResourceCollectionDef(String name, List<String> urlPatterns,
                                           List<String> httpMethods, List<String> httpMethodOmissions) {}
    /** rolesAllowed null = no auth-constraint; empty = deny all (§13.8.1). */
    public record SecurityConstraintDef(List<WebResourceCollectionDef> collections,
                                        List<String> rolesAllowed, String transportGuarantee) {}
    public record LoginConfigDef(String authMethod, String realmName,
                                 String formLoginPage, String formErrorPage) {}
    private SecurityDefs() {}
}
```

Defaults per `web-common_6_1.xsd`: `multipart-config` max sizes `-1`, threshold `0`; `cookie-config/max-age` absent → `null`; `<attribute>` (Servlet 6.0+) collected as name→value. Mime extensions are stored lower-case without a leading dot. Elements of the Jakarta EE environment (`env-entry`, `*-ref`, `data-source`, `jsp-config`, …) stay ignored; `jsp-file` is stored only (no JSP engine). These elements also parse inside fragments.

- [ ] **Step 1: Write the failing test** — one test method per group (welcome/mime/encodings/default-context-path/deny-uncovered; cookie-config incl. `<attribute>` + tracking-mode; multipart-config/enabled/run-as/jsp-file; security-constraint with two collections, `http-method-omission`, empty `<auth-constraint/>` → empty roles, user-data-constraint CONFIDENTIAL; login-config FORM pages; security-role list), each parsing a literal descriptor and asserting every field; plus `absentElementsHaveEmptyDefaults` on `<web-app/>`.
- [ ] **Step 2: Run** `./mvnw -ntp -pl foy-core test -Dtest=WebXmlParserSchemaTest` → FAIL.
- [ ] **Step 3: Implement.** Keep `WebXmlParser` readable: one private `parseX(Element)` per element group; if the class passes ~400 lines, move the session/security group into `WebXmlSecurityParser` (package-private) and say so in the report.
- [ ] **Step 4: Run** `./mvnw -ntp -pl foy-core test` → PASS.
- [ ] **Step 5: Commit** — `feat(webxml): parse welcome files, mime mappings, encodings, cookie config, multipart and security elements`.

---

### Task 3.3: `FragmentOrderer` — §8.2.2 absolute and relative ordering

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/webxml/FragmentOrderer.java`
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/webxml/FragmentOrdererTest.java`

**Interfaces:**
- Consumes: `WebAppDescriptor.fragmentName()`, `ordering()`, `absoluteOrdering()`, `OTHERS` (3.1).
- Produces:

```java
package io.vidocq.foy.internal.webxml;

/** One web fragment and the jar it came from. */
public record Fragment(String id, java.net.URL jar, WebAppDescriptor descriptor) {}

public final class FragmentOrderer {
    /**
     * @param absoluteOrdering web.xml's list (may contain {@link WebAppDescriptor#OTHERS}), or null
     * @param fragments        in discovery order (deterministic: the caller sorts by jar URL)
     * @return the fragments to merge, in merge order; excluded fragments are absent
     * @throws jakarta.servlet.ServletException on duplicate fragment names or a relative-ordering cycle
     */
    public static List<Fragment> order(List<String> absoluteOrdering, List<Fragment> fragments)
            throws jakarta.servlet.ServletException;
}
```

Rules (§8.2.2):
- **Duplicate `<name>`** among fragments → `ServletException("duplicate web-fragment name 'X' in <jar1> and <jar2>")`.
- **Absolute ordering present** (non-null): relative `<ordering>` is ignored. Walk the list: a name matching a fragment emits it (first occurrence only; a repeated name is ignored with a WARNING); a name with no fragment is ignored (FINE log); `OTHERS` emits, at that position, every fragment not named anywhere in the list, in discovery order. Fragments not named and with no `OTHERS` are **excluded** (unnamed fragments included).
- **No absolute ordering**: relative ordering. A fragment with `before others` goes into the head group, `after others` into the tail group, else the middle group; within the whole sequence every `before X` / `after X` constraint must hold; names referring to absent fragments are ignored. Algorithm: build a directed graph over all fragments with edges from `before`/`after` names, plus group edges (head → middle → tail); topological sort that always picks the earliest-discovered available node (Kahn with a priority on discovery index) — deterministic; a remaining node set after the sort = cycle → `ServletException` naming the fragments involved. A fragment that is both `before others` and `after others` is a conflict → `ServletException`.
- No fragment declares any ordering and no absolute ordering → discovery order unchanged.

- [ ] **Step 1: Write the failing test**

```java
package io.vidocq.foy.internal.webxml;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FragmentOrdererTest {

    private static Fragment f(String name, Ordering o) throws Exception {
        var d = WebAppDescriptor.fragmentForTest(name, o);  // test factory added in Task 3.1/3.3
        return new Fragment(name == null ? "anon" : name,
                URI.create("file:/jars/" + (name == null ? "anon" : name) + ".jar").toURL(), d);
    }

    private static List<String> names(List<Fragment> fs) {
        var out = new ArrayList<String>();
        for (var x : fs) out.add(x.descriptor().fragmentName() == null ? "anon" : x.descriptor().fragmentName());
        return out;
    }

    private static Ordering after(String... n) { return new Ordering(List.of(), false, List.of(n), false); }
    private static Ordering before(String... n) { return new Ordering(List.of(n), false, List.of(), false); }
    private static final Ordering BEFORE_OTHERS = new Ordering(List.of(), true, List.of(), false);
    private static final Ordering AFTER_OTHERS = new Ordering(List.of(), false, List.of(), true);

    @Test
    void noOrderingKeepsDiscoveryOrder() throws Exception {
        var in = List.of(f("A", Ordering.NONE), f("B", Ordering.NONE), f("C", Ordering.NONE));
        assertEquals(List.of("A", "B", "C"), names(FragmentOrderer.order(null, in)));
    }

    @Test
    void absoluteOrderingWithOthers() throws Exception {
        var in = List.of(f("A", Ordering.NONE), f("B", Ordering.NONE), f("C", Ordering.NONE), f(null, Ordering.NONE));
        var out = FragmentOrderer.order(List.of("C", WebAppDescriptor.OTHERS, "A"), in);
        assertEquals(List.of("C", "B", "anon", "A"), names(out));
    }

    @Test
    void unnamedFragmentExcludedWithoutOthers() throws Exception {
        var in = List.of(f("A", Ordering.NONE), f(null, Ordering.NONE), f("B", Ordering.NONE));
        assertEquals(List.of("B", "A"), names(FragmentOrderer.order(List.of("B", "Missing", "A"), in)));
    }

    @Test
    void absoluteOrderingIgnoresRelativeOrdering() throws Exception {
        var in = List.of(f("A", after("B")), f("B", Ordering.NONE));
        assertEquals(List.of("A", "B"), names(FragmentOrderer.order(List.of("A", "B"), in)));
    }

    @Test
    void relativeBeforeAfterAndOthers() throws Exception {
        // TCK FragmentTests shape: Fragment1 after Fragment2; Fragment3 before others
        var in = List.of(f("Fragment1", after("Fragment2")), f("Fragment2", Ordering.NONE),
                         f("Fragment3", BEFORE_OTHERS), f("Fragment4", AFTER_OTHERS));
        assertEquals(List.of("Fragment3", "Fragment2", "Fragment1", "Fragment4"),
                names(FragmentOrderer.order(null, in)));
    }

    @Test
    void beforeNamedFragment() throws Exception {
        var in = List.of(f("A", Ordering.NONE), f("B", before("A")));
        assertEquals(List.of("B", "A"), names(FragmentOrderer.order(null, in)));
    }

    @Test
    void cycleFails() throws Exception {
        var in = List.of(f("A", after("B")), f("B", after("A")));
        var e = assertThrows(ServletException.class, () -> FragmentOrderer.order(null, in));
        assertTrue(e.getMessage().contains("A") && e.getMessage().contains("B"), e.getMessage());
    }

    @Test
    void duplicateNameFails() throws Exception {
        var in = List.of(f("A", Ordering.NONE), f("A", Ordering.NONE));
        assertThrows(ServletException.class, () -> FragmentOrderer.order(null, in));
    }

    @Test
    void beforeAndAfterOthersTogetherFails() throws Exception {
        var both = new Ordering(List.of(), true, List.of(), true);
        assertThrows(ServletException.class, () -> FragmentOrderer.order(null, List.of(f("A", both))));
    }
}
```

`WebAppDescriptor.fragmentForTest(String name, Ordering ordering)` is a package-private static factory added in this task (returns an empty `WEB_FRAGMENT` descriptor with that name and ordering).

- [ ] **Step 2: Run** `./mvnw -ntp -pl foy-core test -Dtest=FragmentOrdererTest` → compilation failure.
- [ ] **Step 3: Implement** as specified.
- [ ] **Step 4: Run** same → 9 PASS.
- [ ] **Step 5: Commit** — `feat(webxml): order web fragments per §8.2.2`.

---

### Task 3.4: `FragmentMerger` — web.xml + ordered fragments → one effective descriptor (§8.2.3)

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/webxml/FragmentMerger.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/boot/DescriptorMerger.java` (new overload; annotation exclusion per jar)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/webxml/FragmentMergerTest.java`
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/boot/DescriptorMergerTest.java` (Phase 1 follow-ups: error-page and context-param copy, `FilterMappingDecl` invariants, unmapped dynamic vs static filter)

**Interfaces:**
- Consumes: `Fragment`, `FragmentOrderer.order` (3.3), the descriptor fields of 3.1/3.2.
- Produces:

```java
package io.vidocq.foy.internal.webxml;

public final class FragmentMerger {
    /**
     * Folds the ordered fragments into web.xml. web.xml always wins; between fragments, a conflicting
     * value for the same key fails unless web.xml declares that key.
     * @throws jakarta.servlet.ServletException on a conflict, naming the element, the key and both fragment ids
     */
    public static WebAppDescriptor merge(WebAppDescriptor webXml, List<Fragment> ordered)
            throws jakarta.servlet.ServletException;
}
```

```java
// DescriptorMerger — new entry point used by every boot path
public static void merge(WebAppDescriptor webXml, List<Fragment> fragments,
                         AnnotatedComponents annotated, ComponentFactory factory,
                         WebAppModel.Builder target) throws ServletException;
// the existing 4-arg merge(...) delegates with List.of() fragments.
// AnnotatedComponents gains:
public AnnotatedComponents excludingSources(java.util.Set<java.net.URL> jars); // drops components whose
//   class's CodeSource location (normalised) is in jars
```

Merge rules (Servlet 6.1 §8.2.3, implement each with a test):
- `metadata-complete="true"` on **web.xml** → fragments are not merged and all annotations are ignored; ordering still decides which jars' SCIs run (Task 3.6). Verify the wording of §8.2.1 against the spec text and cite it in the Javadoc.
- `metadata-complete="true"` on a **fragment** → that fragment's descriptor is merged, the annotations of classes from its jar are ignored (`excludingSources`).
- Excluded fragments (absolute ordering) contribute nothing — neither descriptor nor annotations (`excludingSources` again).
- Servlets/filters by name: web.xml declaration wins; a fragment-only name is added; the same name in two fragments with a different class → conflict error; same class → merged. `init-param`: web.xml wins per key; fragments add missing keys; two fragments with different values for a key web.xml does not set → conflict. `async-supported`, `load-on-startup`, `multipart-config`, `run-as`: first non-null in web.xml then fragment order.
- `servlet-mapping`: if web.xml maps a servlet, fragment mappings for that servlet are ignored; otherwise the union (§8.2.3 rule 1.d); duplicate pattern on two servlets → conflict.
- `filter-mapping`: web.xml mappings first, then fragments in order (this is what `FragmentTests.filterOrderingTest` checks); same filter-mapping rule as servlet-mapping for a filter web.xml maps.
- `listener`: web.xml first, then fragments in order, duplicates removed (keep first).
- `context-param`, `mime-mapping`, `error-page` (by code / exception type), `locale-encoding-mapping`: web.xml wins per key; additive across fragments; conflicting fragment values → conflict.
- `welcome-file-list`: web.xml's list if present, else the concatenation of fragment lists in order (dedup).
- `session-config` (timeout, cookie-config, tracking-mode), `request/response-character-encoding`, `default-context-path`, `deny-uncovered-http-methods`: web.xml wins; else the first fragment that declares it; a second fragment with a different value → conflict.
- `security-constraint`, `security-role`: additive; `login-config`: web.xml wins, conflicting fragments → conflict.
- Result `kind()` is `WEB_APP`; `absoluteOrdering()` is kept from web.xml.

- [ ] **Step 1: Write the failing tests** — `FragmentMergerTest` with one test per rule above using inline descriptors (`WebXmlParser.parse/parseFragment` on text blocks), including `conflictBetweenFragmentsFails` (servlet `s` → `a.A` in fragment F1, `a.B` in F2: message contains `s`, `F1`, `F2`), `webXmlResolvesConflict`, `filterMappingsWebXmlFirstThenFragmentOrder`, `fragmentMetadataCompleteKeepsDescriptor`, `webXmlMetadataCompleteIgnoresFragments`. `DescriptorMergerTest`: `fragmentMetadataCompleteExcludesOnlyItsJarAnnotations` (two annotated servlet classes; one fragment URL marked metadata-complete equals the code-source of one class — build the test classes into two directories with the `javax.tools` compile approach used in foy-core tests, or use two `URLClassLoader`s over copies), plus the Phase 1 follow-up tests: error pages and context params copied from web.xml; a `FilterMappingDecl` with neither pattern nor servlet name is rejected; an annotated filter without mapping vs a dynamic filter without mapping behave the same (both registered, neither applied).
- [ ] **Step 2: Run** `./mvnw -ntp -pl foy-core test -Dtest='FragmentMergerTest,DescriptorMergerTest'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -ntp -pl foy-core test` → PASS.
- [ ] **Step 5: Commit** — `feat(core): merge web fragments into web.xml per §8.2.3`.

---

### Task 3.5: `WebAppModel` carries and the runtime applies the new elements

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppModel.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/boot/DescriptorMerger.java` (copy into the builder)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppDeployer.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/container/VidocqServletContext.java` (`getMimeType`, request/response encoding, session cookie config, tracking modes, welcome files accessor)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/boot/WebAppModelElementsEndToEndTest.java`

**Interfaces:**
- Produces on `WebAppModel` (record components, with builder setters of the same name): `List<String> welcomeFiles`, `Map<String,String> mimeMappings`, `String requestCharacterEncoding`, `String responseCharacterEncoding`, `String defaultContextPath`, `boolean denyUncoveredHttpMethods`, `CookieConfigDef cookieConfig`, `Set<SessionTrackingMode> trackingModes`, `List<SecurityConstraintDef> securityConstraints`, `LoginConfigDef loginConfig`, `List<String> securityRoles`; `ServletDecl` gains `MultipartConfigElement multipartConfig` (nullable; from web.xml `multipart-config`, else the descriptor's `@MultipartConfig`) and `boolean enabled` (`<enabled>false</enabled>`: the servlet is declared — `getServletRegistration` returns it — but it is neither instantiated nor mapped, so its URLs answer 404).
- Applied at deploy: `getMimeType` consults `mimeMappings` (case-insensitive extension) before the built-in table; `getRequestCharacterEncoding`/`getResponseCharacterEncoding` return the model values, and a request whose client sent no charset uses the request default, a response whose application set none uses the response default (`ServletContext#setRequestCharacterEncoding` / `#setResponseCharacterEncoding` Javadoc); `getSessionCookieConfig()` is pre-populated from `cookieConfig` and becomes read-only after `markInitialized`; `getDefaultSessionTrackingModes`/`getEffectiveSessionTrackingModes` reflect `trackingModes`; `ServletRegistration.getMultipartConfig`-equivalent data is reachable for Phase 7; `welcomeFiles` exposed for Phase 4 dispatch; `defaultContextPath` used by `FoyChappeBoot` when no context path is configured (Task 3.6); security fields stored for Phase 6 (documented as not enforced yet).

- [ ] **Step 1: Write the failing test** — an end-to-end deployment (`TestServerLauncher` pattern used by existing foy-core end-to-end tests) of a model built through `DescriptorMerger` from a literal web.xml declaring mime-mapping `foo → application/x-foo`, request-character-encoding `UTF-8`, response-character-encoding `ISO-8859-1`, cookie-config name `MYSESSION` http-only, tracking-mode `COOKIE`, a servlet with `multipart-config` and a disabled servlet; a probe servlet answers `ctx.getMimeType("a.FOO")`, `req.getCharacterEncoding()` (no client charset), `resp.getCharacterEncoding()`, `ctx.getSessionCookieConfig().getName()`, `ctx.getEffectiveSessionTrackingModes()`, and the disabled servlet's URL returns 404.
- [ ] **Step 2: Run** `./mvnw -ntp -pl foy-core test -Dtest=WebAppModelElementsEndToEndTest` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -ntp -pl foy-core,foy-chappe install` → PASS.
- [ ] **Step 5: Commit** — `feat(core): apply mime mappings, encodings, cookie config, tracking modes and multipart config from descriptors`.

---

### Task 3.6: Native discovery in `FoyChappeBoot` — fragments and SCIs

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/boot/ApplicationSources.java`
- Modify: `foy-chappe/src/main/java/io/vidocq/foy/chappe/FoyChappeBoot.java`
- Modify: `foy-core/src/main/java/module-info.java` (`uses jakarta.servlet.ServletContainerInitializer;`)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/boot/ApplicationSourcesTest.java`
- Test: `foy-chappe/src/test/java/io/vidocq/foy/chappe/FoyChappeBootFragmentsTest.java`

**Interfaces:**
- Produces:

```java
package io.vidocq.foy.internal.boot;

/** What a class/module-path application is made of, discovered through its class loader. */
public final class ApplicationSources {
    /** Every META-INF/web-fragment.xml visible to the loader, parsed; dedup by normalised jar URL; sorted by jar URL. */
    public static List<io.vidocq.foy.internal.webxml.Fragment> fragments(ClassLoader loader)
            throws jakarta.servlet.ServletException;   // malformed → ServletException naming the URL
    /** SCIs from ServiceLoader, each with the jar it came from (CodeSource), in jar URL order. */
    public static List<Initializer> initializers(ClassLoader loader);
    public record Initializer(jakarta.servlet.ServletContainerInitializer sci, java.net.URL jar) {}
    /** §8.2.4: drops SCIs from jars excluded by the absolute ordering; keeps SCIs of the application itself. */
    public static List<Initializer> ordering(List<Initializer> all, List<io.vidocq.foy.internal.webxml.Fragment> ordered,
                                                  java.util.Set<java.net.URL> allFragmentJars);
}
```

Jar identity: the URL of the resource minus `!/META-INF/web-fragment.xml` (`jar:file:...!/`) or the directory for exploded roots; normalise (`URI.normalize`, strip trailing `/`). A jar without a fragment is still a source (unnamed, no descriptor) for `<others/>` purposes only if it carries an SCI — follow §8.2.4: "the SCIs of jars excluded by `<absolute-ordering>` are not run".

`FoyChappeBoot.build()` becomes: web.xml (as today) → `ApplicationSources.fragments` → `FragmentOrderer.order(webXml.absoluteOrdering(), fragments)` → `DescriptorMerger.merge(webXml, ordered, annotated.excludingSources(...), factory, model)` → SCIs from `ApplicationSources.initializers` filtered by `ApplicationSources.ordering` and added with `WebAppModel.Builder.initializer(..)` → `contextPath` defaults to the merged `defaultContextPath()` when the builder has none. A `Builder.discoverPluggability(boolean)` (default `true`) lets embedders opt out.

- [ ] **Step 1: Failing tests** — `ApplicationSourcesTest`: build two jars in a `@TempDir` (with `java.util.jar.JarOutputStream`): `a.jar` with `META-INF/web-fragment.xml` (`<name>A</name>`) and `META-INF/services/jakarta.servlet.ServletContainerInitializer` naming a test SCI compiled into the jar via `javax.tools` (or copied from test classes), `b.jar` with a malformed fragment; a `URLClassLoader` over `a.jar`, `a.jar` again (duplicate) → one fragment `A` (`duplicateJarMergedOnce`); over `b.jar` → `ServletException` containing `b.jar` (`malformedFragmentNamesTheJar`); `ApplicationSources.ordering` drops the SCI of a jar excluded by an absolute ordering. `FoyChappeBootFragmentsTest`: a jar whose fragment declares a servlet + mapping and whose SCI adds a second servlet; boot with that loader, `GET` both → 200; with an absolute ordering in web.xml that excludes the fragment → both 404.
- [ ] **Step 2: Run** `./mvnw -ntp -pl foy-core,foy-chappe test -Dtest='ApplicationSourcesTest,FoyChappeBootFragmentsTest' -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.
- [ ] **Step 3: Implement.** SCI instances come from `ServiceLoader` (no reflection); their `@HandlesTypes` resolution keeps using `DeployOptions.handlesTypes` (Task 3.8 adds the jar-scan fallback).
- [ ] **Step 4: Run** `./mvnw -ntp -pl foy-core,foy-chappe install` → PASS.
- [ ] **Step 5: Commit** — `feat(chappe): discover web fragments and container initializers on the class and module path`.

---

### Task 3.7: `META-INF/resources` static resources (§4.6, §10.5)

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/container/ClassPathResourceProvider.java`
- Modify: `foy-chappe/src/main/java/io/vidocq/foy/chappe/FoyChappeBoot.java` (install it unless the embedder set one)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/container/ClassPathResourceProviderTest.java`

**Interfaces:**
- Consumes: `VidocqServletContext.ResourceProvider` (existing interface — read it first; implement its exact methods).
- Produces: `new ClassPathResourceProvider(ClassLoader loader, List<java.net.URL> orderedJars)` resolving `getResource("/x/y.css")` to the first `META-INF/resources/x/y.css` found in jar order (application classes first, then ordered fragments, then others — §4.6: order between jars unspecified, Foy uses the fragment order); `getResourcePaths("/x/")` returns the union of direct children (directories end with `/`); paths with `..` or a backslash return null; `WEB-INF/` and `META-INF/` under the resource root are not served by the default servlet (only reachable through `getResource`).

- [ ] **Step 1: Failing test** — two jars in `@TempDir`: `a.jar` with `META-INF/resources/index.html` and `META-INF/resources/css/a.css`, `b.jar` with `META-INF/resources/index.html` (different content) and `META-INF/resources/css/b.css`; assert first-wins on `index.html` in the given jar order, `getResourcePaths("/css/")` = `{"/css/a.css","/css/b.css"}`, `getResource("/../x")` null, and an end-to-end `GET /css/b.css` through `FoyChappeBoot` returns the content with `text/css`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `./mvnw -ntp -pl foy-core,foy-chappe install` → PASS.
- [ ] **Step 5: Commit** — `feat(core): serve META-INF/resources from the application's jars`.

---

### Task 3.8: `@HandlesTypes` — Class-File jar-scan fallback

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/gen/ClassFileHandlesTypesScanner.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/gen/IndexedHandlesTypesResolver.java` (fall back per jar)
- Modify: `foy-tck/src/test/java/io/vidocq/foy/tck/arquillian/VidocqDeployableContainer.java` (replace its private war-class-list resolver, ruling D of Phase 2, by the scanner over the WAR's classes and lib jars)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/gen/ClassFileHandlesTypesScannerTest.java`

**Interfaces:**
- Produces: `ClassFileHandlesTypesScanner.scan(Iterable<java.nio.file.Path> roots)` → the same entries the class index holds (binary name, transitive supertypes, type-level annotations read from `RuntimeVisibleAnnotations` only, since an annotation type named in `@HandlesTypes` must be runtime-retained to be matched) decoded with `java.lang.classfile` from every `.class` under a directory or inside a jar; transitive supertypes are resolved across all scanned roots plus, for types outside them, by reading their bytes through the loader (`getResourceAsStream`), never by `Class.forName` with initialisation. `IndexedHandlesTypesResolver` uses the index for jars that ship `META-INF/foy/class-index.list` and the scanner for the others (roots from the loader: the code-source locations of the jars/directories that hold the application's `META-INF/web-fragment.xml` or `META-INF/services` entries, plus explicit roots from `DeployOptions`). Same §8.2.4 semantics as Phase 2: handled types excluded; `null` when nothing matches.

- [ ] **Step 1: Failing test** — compile into a `@TempDir` jar (via `javax.tools`) `p.Base` (abstract, implements `Runnable`), `p.Impl extends Base`, `@p.Marker class p.M` with `@Retention(RUNTIME) @interface p.Marker`; no class index; an SCI with `@HandlesTypes({Runnable.class, p.Marker.class})` resolves to `{p.Base, p.Impl, p.M}` (`Runnable` itself excluded); an SCI handling an unrelated type gets `null`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `./mvnw -ntp -pl foy-core install` and `./mvnw -ntp -Ptck -pl foy-tck test-compile` → PASS.
- [ ] **Step 5: Commit** — `feat(core): resolve @HandlesTypes from class bytes when a jar has no class index`.

---

### Task 3.9: async-supported recomputed on dispatch (§2.3.3.3)

**Files:**
- Modify: `foy-chappe/src/main/java/io/vidocq/foy/chappe/ChappeServletBridge.java` (and/or the dispatcher in `foy-core/.../internal/dispatcher/`: find where `setAsyncSupported` is called and where `RequestDispatcher.forward/include` and `AsyncContext.dispatch` build the target chain)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/dispatcher/AsyncSupportedDispatchTest.java` (end-to-end)

**Interfaces:**
- Produces: on every dispatch (FORWARD, INCLUDE, ASYNC, ERROR) the request's async-supported flag = the incoming flag AND the target servlet's flag AND every filter's flag in the chain for that dispatcher type; restored after an include/forward returns (§9.4, §2.3.3.3: "an async request dispatched to a servlet that does not support async is not async-capable").

- [ ] **Step 1: Failing test** — async servlet `S1` (`asyncSupported=true`) forwards to `S2` (absent → false); `S2` reports `req.isAsyncSupported()` = false and `startAsync()` throws `IllegalStateException`; after the forward returns into `S1` (include variant), `S1` sees `true` again; `AsyncContext.dispatch("/s2")` from `S1` → `S2` sees false. Mirrors `spec.async.AsyncTests` `Servlet2_Async=NOT_STARTED`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `./mvnw -ntp -pl foy-core,foy-chappe install` → PASS.
- [ ] **Step 5: Commit** — `fix(core): recompute async support on forward, include and async dispatch`.

---

### Task 3.10: TCK harness through the product merge, with `WEB-INF/lib` fragments

**Files:**
- Modify: `foy-tck/src/test/java/io/vidocq/foy/tck/arquillian/VidocqDeployableContainer.java`
- Modify: `foy-tck/src/main/java/io/vidocq/foy/tck/ServletTestHarness.java` (accept a merged `WebAppModel.Builder`, drop `toModel`'s private component building)
- Test: `foy-tck/src/test/java/io/vidocq/foy/tck/ServletTestHarnessTest.java` (existing harness unit tests, extended)

**Interfaces:**
- Consumes: `WebXmlParser.parse/parseFragment`, `FragmentOrderer`, `DescriptorMerger.merge(webXml, fragments, annotated, factory, builder)`, `AnnotatedComponents.excludingSources`, `ClassFileHandlesTypesScanner`, `ClassPathResourceProvider`-like WAR resource view.
- Produces: per deployment, the container
  1. lists `WEB-INF/classes/**` and every `WEB-INF/lib/*.jar` (open each `ArchiveAsset`'s archive, or re-read the bytes as a zip): class names (all added to the visible-names set), `META-INF/web-fragment.xml` (a `Fragment` whose `jar` URL is a stable synthetic `URL` per lib jar, e.g. `file:/<archive>/WEB-INF/lib/<jar>`), `META-INF/services/jakarta.servlet.ServletContainerInitializer`, `META-INF/resources/**` (added to the WAR resource view after the WAR root);
  2. builds `AnnotatedComponents` from the registry descriptors of the annotated classes (WEB-INF/classes and lib jars), and filters them with the overload `AnnotatedComponents.excludingSources(Set<URL> jars, Function<Class<?>, URL> sourceOf)` (added in this task; the 1-arg form of Task 3.4 delegates with `sourceOf` = the class's normalised CodeSource location), passing a `sourceOf` that maps each WAR class to its synthetic lib-jar URL or to the WAR's `WEB-INF/classes` URL;
  3. calls `FragmentOrderer.order` and `DescriptorMerger.merge`, then `WebAppDeployer` — `registerFromWebXml`, `registerIfAnnotated` and `ServletTestHarness.toModel`'s component building are deleted;
  4. SCIs from `WEB-INF/classes` and lib jars, filtered by §8.2.4 ordering exclusion; `@HandlesTypes` via `ClassFileHandlesTypesScanner` over the WAR's classes + lib jars;
  5. logs `servlets=[...]` as today (the per-deployment line is how regressions are diagnosed).

- [ ] **Step 1: Failing harness tests** — `ServletTestHarnessTest`: a ShrinkWrap `WebArchive` with **no** web.xml and a lib jar whose fragment declares servlet `/a` → deploy → `GET /a` 200; a WAR whose web.xml sets `metadata-complete="true"` and an annotated servlet → not registered; a filter declared in web.xml without `async-supported` before an async servlet → `isAsyncSupported()` false in the servlet. (Use the TCK's own `ShrinkWrap` dependency already on foy-tck's test classpath.)
- [ ] **Step 2: Run** `./mvnw -ntp -Ptck -pl foy-tck test -Dtest=ServletTestHarnessTest` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -ntp -Ptck -pl foy-tck test -Dtest=ServletTestHarnessTest` → PASS, then targeted TCK: `./run-official-tck-servlet6.1.sh --family pluggability` and `--family spec` → pluggability ≥ 600/646, spec ≥ 102 with `AsyncTests` and `WebFilterTests` improved, no per-class regression vs `foy-tck/tck-baseline.txt`, `reflection=0`.
- [ ] **Step 5: Commit** — `refactor(tck): deploy WARs through the product descriptor and fragment merge`.

---

### Task 3.11: Phase exit — full TCK, baseline, docs

**Files:**
- Modify: `foy-tck/tck-baseline.txt` (only with zero regression), `TCK.md` (§0 entry)
- Modify: `docs/en/modules/ROOT/pages/usage.adoc` (native layout table: WAR concept → Foy, fragments as `META-INF/web-fragment.xml`, `META-INF/resources`, SCIs via `META-INF/services` / `provides`, ordering), `reference.adoc` (web.xml / web-fragment.xml element coverage table: parsed+applied / parsed-for-Phase-6 / ignored), `internals.adoc` (discovery → ordering → fragment merge → annotation merge → deploy pipeline), `concepts.adoc` if it describes deployment
- Modify: `docs/superpowers/plans/2026-10-07-servlet-completion-roadmap.md` ("Phase 3 exit / follow-ups")

- [ ] **Step 1:** `./run-official-tck-servlet6.1.sh --all > /tmp/foy-tck-phase3-exit.log 2>&1`; regression diff (TCK.md §0 procedure) → zero regression; `grep -o 'tiers=Stats\[[^]]*\]' /tmp/foy-tck-phase3-exit.log | grep -v 'reflection=0'` → no output; pluggability ≥ 600/646.
- [ ] **Step 2:** refresh the baseline (`LC_ALL=C` sort, same format) and add the TCK.md §0 entry with per-family figures.
- [ ] **Step 3:** docs as listed; roadmap note with the residual pluggability failures (expected Phase 4/6 twins) and anything deferred.
- [ ] **Step 4: Commit** — `docs: document descriptors, fragments and the native layout (Phase 3 exit)` and `build(tck): refresh the baseline to the Phase 3 exit tally`.
