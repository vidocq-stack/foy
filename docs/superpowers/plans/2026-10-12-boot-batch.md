# FoyChappeBoot Batch (Application Layer, JSP-Only Applications, Component Source) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Three `FoyChappeBoot` changes for the Vidocq runtime:
1. **Application layer.** The application's `ModuleLayer` can be passed to the deployer, so a JSP engine on that layer is found.
2. **JSP-only applications.** An application made only of precompiled JSPs is deployed when its engine says it has pages.
3. **Component source (vidocq#228).** A harness that loads every jar's classes from one class loader can still attribute each class to its fragment jar. The absolute-ordering and `metadata-complete` exclusions then apply.

**Architecture:**
- `FoyChappeBoot.Builder` gains `applicationLayer(ModuleLayer)` and `componentSource(Function<Class<?>, URL>)`. Both default to today's behaviour: the boot layer, and each class's code source.
- `JspEngine` (foy-api SPI) gains a default method `hasPages(ModuleLayer)` that returns `true`.
- `build()` looks the engine up once, before the "nothing to deploy" check, and hands it to a now-public 4-argument `WebAppDeployer.deploy`.
- The component source flows into the code paths that today attribute classes by code source:
  - annotated-component exclusion;
  - `metadata-complete` merging;
  - application roots;
  - initializer attribution.

**Tech Stack:** Java 25, Jakarta Servlet 6.1, JUnit 5, in-process `javac` for test jars and modules.

**Spec:** the Vidocq requests (relayed by the Vidocq session), the maintainer decisions below, and the research in the controller scratchpad (`foy-boot-batch-research.md`).

## Global Constraints

- **Language.** English everywhere: code, Javadoc, comments, tests, commits and docs. Say "Java Modules", never the abbreviation.
- **Dependencies.** Zero runtime dependencies. Foy must not depend on Ibarra: `PageIndex` is Ibarra's type and never appears in Foy.
- **TDD.** Write the failing test first. Record RED and GREEN with the number of tests that ran.
- **Maintainer decision R2.** "Has pages" is a default method on the SPI: `default boolean hasPages(ModuleLayer applicationLayer) { return true; }` on `io.vidocq.foy.spi.jsp.JspEngine`. `JspEngine` stays a `@FunctionalInterface`, and Ibarra overrides it with its `PageIndex`.
- **Maintainer decision R3.**
  - The fix is a hook only: `FoyChappeBoot.Builder.componentSource(Function<Class<?>, URL> sourceOf)`. Its default is the code source, which is today's behaviour.
  - There is no automatic fallback on class-name sets. The Vidocq harness (`MaterializedDeployment`) will supply the attribution itself.
  - A `null` source means "unknown": the class is kept, as today for a class without a code source.
- **`applicationLayer(null)`** throws `NullPointerException`, and so does `componentSource(null)`. An unset `classLoader` does not follow the layer: the two settings stay independent, and the Javadoc says so.
- **Commits.**
  - `git commit -S -s`, Conventional Commits, explicit paths only.
  - The last paragraph is exactly `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` then `Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi`. A subagent running on another model names that model instead.
- **Build.**
  - Run `./mvnw -ntp …` from the foy root with `-Dmaven.repo.local=<scratchpad>/m2 -Dmaven.repo.local.tail=$HOME/.m2/repository`.
  - Select tests with `-Dtest='A,B'` (commas) and `-Dsurefire.failIfNoSpecifiedTests=false` with `-am`.
  - foy-core tests run on the module path. foy-chappe tests run on the class path (`useModulePath=false`).
  - Known unrelated failure: `FoyWebExtensionTest.requestScopedThroughFoyChappeBoot` (BUG-20261011-02).
- **BUG.md.** Record vidocq#228 as a new entry, the first free id on 2026-10-12 (`BUG-20261012-01` unless one exists), with status FIXED by this branch.

## Review Focus

1. **A layer with no JSP engine.** It must keep today's result: an application with nothing else gives `Optional.empty()` (Task 2, test `aLayerWithoutAnEngineStaysEmpty`).
2. **An engine whose `hasPages` answers false.** With no other component, nothing is deployed. With other components, the application deploys and the engine still serves `*.jsp` (Task 2, tests `anEngineWithoutPagesDeploysNothingAlone` and `anEngineWithoutPagesStillServesAnOtherwiseNonEmptyApp`).
3. **The engine is looked up once.** It must not be instantiated twice: a `ServiceLoader` provider constructed twice would open two engines (Task 1, test `theEngineIsLookedUpOnce`, and Task 2, which uses the seam).
4. **A `componentSource` that returns `null` or throws.** `null` keeps the class. An exception fails `build()` with a `ServletException` naming the class, never a half-built deployment (Task 3, tests `aNullSourceKeepsTheComponent` and `aThrowingSourceFailsTheBuild`).
5. **The exclusion with a custom source.** It must cover `@WebServlet`, `@WebFilter` and `@WebListener`, plus a `ServletContainerInitializer` of the excluded jar loaded by the shared loader (Task 3, test `aSharedLoaderHarnessExcludesTheFragmentThroughComponentSource`).

---

### Task 1: The JSP-engine lookup and `hasPages` (foy-api, foy-core)

**Files:**
- Modify: `foy-api/src/main/java/io/vidocq/foy/spi/jsp/JspEngine.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppDeployer.java`
- Create: `foy-core/src/test/java/io/vidocq/foy/internal/boot/ApplicationLayerTest.java`

**Interfaces:**
- Produces:
  - `JspEngine#hasPages(ModuleLayer applicationLayer)`, a default method returning `true`.
  - `public static Optional<JspEngine> WebAppDeployer.jspEngine(ModuleLayer applicationLayer)`. It throws `IllegalStateException("Multiple JSP engines are visible in the application layer")` when two engines are visible.
  - `public static Deployment WebAppDeployer.deploy(WebAppModel model, DeployOptions options, ModuleLayer applicationLayer, JspEngine engine)`. Only its visibility changes: it is package-private today. `engine` may be `null`.
  - The 3-argument `deploy` becomes `deploy(model, options, layer, jspEngine(layer).orElse(null))`.

- [ ] **Step 1: Write the failing tests** in `ApplicationLayerTest` (package `io.vidocq.foy.internal.boot`; foy-core tests run on the module path, patched into `io.vidocq.foy.core`).
  - Copy the child-layer pattern from `foy-core/src/test/java/io/vidocq/foy/internal/gen/WebComponentRegistryTest.java` (around lines 215-243): write module sources to a `@TempDir`, compile them with `ToolProvider.findFirst("javac")`, then use `ModuleLayer.boot().configuration().resolve(ModuleFinder.of(out), ModuleFinder.of(), Set.of(name))` and `ModuleLayer.defineModulesWithOneLoader(cfg, List.of(ModuleLayer.boot()), getClass().getClassLoader())`.
  - The provider module is `test.jsp`: `requires io.vidocq.foy.api; requires jakarta.servlet; provides io.vidocq.foy.spi.jsp.JspEngine with test.jsp.TestEngine;`. Compile it with a `--module-path` that holds foy-api's classes and the servlet API jar. Locate them through `JspEngine.class.getProtectionDomain().getCodeSource()` and `jakarta.servlet.Servlet.class...`, as `WebComponentRegistryTest` does for the servlet jar.
  - `TestEngine` counts its instances through a system property, so the test needs no access to the module's classes: its constructor calls `System.setProperty("test.jsp.created", String.valueOf(Integer.getInteger("test.jsp.created", 0) + 1))`. Clear the property in `@BeforeEach`.
  - Its `deploy` returns an `Application` whose `servlet()` answers `"engine"` on GET. It records the layer it received in `System.getProperties().put("test.jsp.layer", layer)`.
  - Tests:

```java
    @Test
    void theBootLayerHasNoEngine() {
        assertTrue(WebAppDeployer.jspEngine(ModuleLayer.boot()).isEmpty());
    }

    @Test
    void anEngineOfAChildLayerIsFoundAndAnswersHasPages() throws Exception {
        ModuleLayer child = layerWith("test.jsp", ENGINE_SOURCES);
        JspEngine engine = WebAppDeployer.jspEngine(child).orElseThrow();
        assertTrue(engine.hasPages(child), "the default answers true");
    }

    @Test
    void twoEnginesInTheLayerAreRefused() throws Exception {
        ModuleLayer child = layerWith(TWO_ENGINE_MODULES);   // test.jsp and test.jsp2, each providing an engine
        var e = assertThrows(IllegalStateException.class, () -> WebAppDeployer.jspEngine(child));
        assertTrue(e.getMessage().contains("Multiple JSP engines"));
    }

    @Test
    void theThreeArgumentDeployOpensTheEngineWithTheLayer() throws Exception {
        ModuleLayer child = layerWith("test.jsp", ENGINE_SOURCES);
        var d = WebAppDeployer.deploy(WebAppModel.builder("/").build(),
                DeployOptions.defaults(getClass().getClassLoader()), child);
        try {
            assertNotNull(d.servletContext().getServletRegistration("jsp"), "*.jsp is mapped to the engine");
            assertSame(child, System.getProperties().get("test.jsp.layer"));
        } finally { d.close(); }
    }

    @Test
    void theEngineIsLookedUpOnce() throws Exception {
        ModuleLayer child = layerWith("test.jsp", ENGINE_SOURCES);
        JspEngine engine = WebAppDeployer.jspEngine(child).orElseThrow();
        var d = WebAppDeployer.deploy(WebAppModel.builder("/").build(),
                DeployOptions.defaults(getClass().getClassLoader()), child, engine);
        try { assertEquals("1", System.getProperty("test.jsp.created")); } finally { d.close(); }
    }
```

  - Adapt `Deployment` accessors (`servletContext()`, `close()`) and `DeployOptions.defaults(...)` to their real names; `JspEngineLifecycleTest` shows how foy-core tests deploy.
  - `test.jsp` must be a fresh layer per test: the `System` properties are reset in `@BeforeEach`.

- [ ] **Step 2: Run and check RED.** Run `./mvnw -ntp -pl foy-api,foy-core -am test -Dtest='ApplicationLayerTest' -Dsurefire.failIfNoSpecifiedTests=false <repo flags>`. Expected: a compile failure (`jspEngine`, `hasPages`, the public 4-argument `deploy`).

- [ ] **Step 3: Implement.**

`JspEngine`:

```java
    /**
     * Whether the application in {@code applicationLayer} has pages for this engine to serve. Foy asks
     * before it opens the application: an application made only of pages (no servlet, filter, listener,
     * descriptor or initializer) is deployed when the engine answers {@code true}, and is not deployed
     * otherwise. An application with other components is deployed whatever the answer, and the engine
     * is opened for it. The default answers {@code true}: an engine that cannot tell says yes.
     *
     * @param applicationLayer the layer the application's pages are looked up in
     */
    default boolean hasPages(ModuleLayer applicationLayer) { return true; }
```

`WebAppDeployer`:

```java
    /**
     * The JSP engine visible in {@code applicationLayer}, its named modules and their ancestors
     * ({@link ServiceLoader#load(ModuleLayer, Class)}: a provider on the class path is never found);
     * empty when there is none. The provider is instantiated once per call.
     *
     * @throws IllegalStateException when more than one engine is visible
     */
    public static Optional<JspEngine> jspEngine(ModuleLayer applicationLayer) {
        var it = ServiceLoader.load(applicationLayer, JspEngine.class).iterator();
        if (!it.hasNext()) return Optional.empty();
        JspEngine engine = it.next();
        if (it.hasNext()) throw new IllegalStateException("Multiple JSP engines are visible in the application layer");
        return Optional.of(engine);
    }
```

  - The 3-argument `deploy` calls `deploy(model, options, applicationLayer, jspEngine(applicationLayer).orElse(null))`. Keep its existing Javadoc, and add that it looks the engine up with `jspEngine`.
  - Make the 4-argument overload `public`, with a Javadoc: "Deploys with `engine`, already looked up in `applicationLayer` (`null` for none); the layer is handed to `JspEngine.deploy`."

- [ ] **Step 4: Run and check GREEN.** Run the class, then `JspEngineLifecycleTest,TldListenerLifecycleTest`, then the whole foy-core. Report the counts.

- [ ] **Step 5: Commit.** `feat(core): look up the JSP engine of an application layer and ask whether it has pages`.

---

### Task 2: `applicationLayer` and JSP-only applications in `FoyChappeBoot`

**Files:**
- Modify: `foy-chappe/src/main/java/io/vidocq/foy/chappe/FoyChappeBoot.java`
- Modify: `foy-chappe/src/test/java/io/vidocq/foy/chappe/FoyChappeBootTest.java`

**Interfaces:**
- Consumes: Task 1's `WebAppDeployer.jspEngine(ModuleLayer)`, the public 4-argument `deploy`, and `JspEngine#hasPages`.
- Produces:
  - `public Builder applicationLayer(ModuleLayer layer)`. Its default is `ModuleLayer.boot()`, and `null` throws NPE.
  - The package-private test seam `Builder jspEngineLookup(Function<ModuleLayer, Optional<JspEngine>> lookup)`. Its default is `WebAppDeployer::jspEngine`. foy-chappe tests run on the class path, where no child layer can provide a `JspEngine` that Foy's own `JspEngine` class accepts. The seam is not public, and its Javadoc says it exists for tests.

- [ ] **Step 1: Write the failing tests** in `FoyChappeBootTest`.
  - A JSP-only application is `FoyChappeBoot.builder().classLoader(new ClassLoader(null) {})`, the same loader as `emptyApplicationYieldsEmpty`: no web.xml, no fragment, no initializer.
  - The engine is a lambda-backed test engine whose `Application.servlet()` answers `"page"`. Give it an explicit `hasPages` through an anonymous class when needed.
  - Tests:
    - `aJspOnlyApplicationIsDeployedWhenTheEngineHasPages`: lookup → an engine with the default `hasPages`. `build()` is present, and a GET of `/x.jsp` through `getThroughRouter` (or the file's existing request helper) answers `page`.
    - `anEngineWithoutPagesDeploysNothingAlone`: `hasPages` returns false, so `build()` is empty.
    - `anEngineWithoutPagesStillServesAnOtherwiseNonEmptyApp`: `hasPages` returns false, but the web.xml has a servlet (use the file's existing `plainApp(...)` helper or an inline web.xml). The app is deployed and `/x.jsp` answers `page`.
    - `aLayerWithoutAnEngineStaysEmpty`: lookup → `Optional.empty()`, so `build()` is empty.
    - `theLayerIsHandedToTheLookupAndToTheEngine`:
      - set `applicationLayer(ModuleLayer.empty())`;
      - the lookup records the layer it receives, and the engine records the layer passed to `hasPages` and to `deploy`;
      - all three are `ModuleLayer.empty()`;
      - with no `applicationLayer` call, the lookup receives `ModuleLayer.boot()`.
    - `theEngineIsLookedUpOnceAndOpenedOnce`: the lookup counts its calls, and `deploy` counts the openings; both are 1.
    - `applicationLayerRejectsNull`: `assertThrows(NullPointerException.class, () -> FoyChappeBoot.builder().applicationLayer(null))`.

- [ ] **Step 2: Run and check RED.** Run `-pl foy-api,foy-core,foy-chappe -am test -Dtest='FoyChappeBootTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: a compile failure.

- [ ] **Step 3: Implement** in `FoyChappeBoot`.
  - Fields: `private ModuleLayer applicationLayer = ModuleLayer.boot();` and `private Function<ModuleLayer, Optional<JspEngine>> jspEngineLookup = WebAppDeployer::jspEngine;`.
  - `applicationLayer(...)`, with this Javadoc:

```java
        /**
         * The layer of the application's modules: the JSP engine ({@link JspEngine}) is looked up
         * there (its named modules and their ancestors; a provider on the class path is never
         * found), asked whether the application has pages, and opened with it. The boot layer by
         * default. Fragments, initializers, descriptors, resources and CDI adapters still come from
         * {@link #classLoader}: a layer whose modules hold them also needs
         * {@code classLoader(layer.findLoader(module))} or a loader that delegates to it.
         */
```

  - In `build()`, right after `WebAppDescriptor effective = FragmentMerger.merge(descriptor, ordered);`:

```java
            JspEngine engine;
            try {
                engine = jspEngineLookup.apply(applicationLayer).orElse(null);
            } catch (RuntimeException e) {
                throw new ServletException("Foy deployment failed: " + e.getMessage(), e);
            }
            boolean pages = engine != null && engine.hasPages(applicationLayer);
```

  - Add `&& !pages` to the emptiness condition. Its INFO message becomes `"No @WebServlet / @WebFilter / @WebListener beans, web.xml, web fragment, ServletContainerInitializer or JSP page discovered — Foy inactive"`.
  - Replace `WebAppDeployer.deploy(model, DeployOptions…)` with the 4-argument call `WebAppDeployer.deploy(model, options, applicationLayer, engine)`.
  - Fix the class Javadoc sentence about the empty `Optional`, which is stale. It must list what counts: a servlet, filter or listener (descriptor or annotated), a descriptor, an initializer, or pages of the JSP engine. Update `build()`'s Javadoc the same way.

- [ ] **Step 4: Run and check GREEN.** Run `FoyChappeBootTest,FoyChappeBootFragmentsTest,FoyChappeBootResourcesTest`, then all foy-chappe. Report the counts.

- [ ] **Step 5: Commit.** `feat(chappe): deploy into an application layer and deploy JSP-only applications`.

---

### Task 3: `componentSource` (vidocq#228)

**Files:**
- Modify: `foy-chappe/src/main/java/io/vidocq/foy/chappe/FoyChappeBoot.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/boot/ApplicationSources.java`
- Modify: `foy-chappe/src/test/java/io/vidocq/foy/chappe/FoyChappeBootFragmentsTest.java`
- Modify: `BUG.md`

**Interfaces:**
- Consumes: the existing `AnnotatedComponents.excludingSources(Set<URL>, Function<Class<?>, URL>)` and `DescriptorMerger.mergeMerged(effective, fragments, annotated, sourceOf, factory, target)`, the 6-argument form, both public in foy-core.
- Produces:
  - `public Builder componentSource(Function<Class<?>, URL> sourceOf)`. Its default is the code source, and `null` throws NPE.
  - `ApplicationSources.codeSources(AnnotatedComponents, Function<Class<?>, URL> sourceOf)`.
  - `ApplicationSources.initializers(ClassLoader, Predicate<URL>, Function<Class<?>, URL> sourceOf)`.
  - The existing overloads delegate with the code-source function. Every source a hook returns is normalised as `root(...)` normalises a code source (`toUrl(Fragment.sourceKey(u), u)`); a `null` stays `null`.

- [ ] **Step 1: Write the failing tests** in `FoyChappeBootFragmentsTest`. Follow `excludedJarContributesNoResourceNorHandledClass` and the file's helpers (`compiledJar`, `sciJar`, `servletBeans`, `loader`, `webXml`, `get`).
  - **`aSharedLoaderHarnessExcludesTheFragmentThroughComponentSource`** reproduces vidocq#228.
    - Build `ex.jar`, holding:
      - `META-INF/web-fragment.xml` named `Ex`;
      - `@WebServlet("/ex") expkg.ExServlet`, `@WebFilter("/*") expkg.ExFilter` (it adds a response header `X-Ex: 1`) and `@WebListener expkg.ExListener` (a `ServletRequestListener` that sets a request attribute the app servlet echoes);
      - `META-INF/services/jakarta.servlet.ServletContainerInitializer` → `expkg.ExSci`, which adds a servlet on `/exsci`.
    - Build `shared.jar` with the same compiled classes and the same services file, but **without** the fragment descriptor.
    - The loader is `new URLClassLoader(new URL[] {exJar}, parentWith(sharedJar))`, parent-first. The classes then load from `shared.jar`, so their code source names `shared.jar`, while `getResources("META-INF/web-fragment.xml")` finds `ex.jar`.
    - The web.xml says `<absolute-ordering/>` with no names, which excludes every fragment.
    - The CDI path gets a `BeanManager` that returns the three classes, through `servletBeans`, extended to filters and listeners if it only handles servlets.
    - **Without `componentSource`:** assert that the bug is reproduced. `/ex` answers 200, which documents today's behaviour; keep it as a separate assertion with a comment.
    - **With `componentSource(type -> type.getName().startsWith("expkg.") ? exJarUrl : null)`:**
      - `/ex` and `/exsci` answer 404;
      - `/app` answers 200, with no `X-Ex` header and no attribute set by `ExListener`;
      - `ExSci`'s constructor never ran: its constructor sets a system property, which the test reads.
  - **`aNullSourceKeepsTheComponent`:** the same layout, with `componentSource(type -> null)`. `/ex` answers 200.
  - **`aThrowingSourceFailsTheBuild`:** `componentSource(type -> { throw new IllegalStateException("boom"); })`. `build()` throws `ServletException`, its message names the class, and no server is started.
  - **`componentSourceRejectsNull`.**
  - If the loader layout cannot make the code source differ from `ex.jar` (check with `assertEquals(sharedJarUrl, ExServlet.class.getProtectionDomain().getCodeSource().getLocation())`), report it and choose another layout that does. Do not drop the reproduction.

- [ ] **Step 2: Run and check RED.** Run `-Dtest='FoyChappeBootFragmentsTest'`. Expected: a compile failure on `componentSource`. Once it compiles, the "with componentSource" assertions fail.

- [ ] **Step 3: Implement.**
  - **`FoyChappeBoot.Builder`.** Add `private Function<Class<?>, URL> componentSource;` (null means the default), and this method:

```java
        /**
         * How a discovered class is attributed to the jar or directory it comes from, for the
         * exclusions of an absolute ordering and of {@code metadata-complete} fragments, and for
         * the roots of the application: the code source of the class by default. A harness that
         * loads the classes of several jars from one class loader (so their code source does not
         * name their jar) supplies the real jar here. A {@code null} result means "unknown": the
         * class is kept. An exception fails {@link #build()}.
         */
        public Builder componentSource(Function<Class<?>, URL> sourceOf) {
            this.componentSource = Objects.requireNonNull(sourceOf, "sourceOf");
            return this;
        }
```

  - **In `build()`.** Resolve `Function<Class<?>, URL> sourceOf = componentSource != null ? guarded(componentSource) : AnnotatedComponents::codeSource;`.
    - `guarded` wraps an exception as `IllegalStateException("componentSource failed for " + type.getName(), e)`.
    - `build()` turns that into `ServletException("Foy deployment failed: …", e)`. Put the calls inside the existing `try` blocks, or add one.
    - Then use `sourceOf` in four places:
      - `annotated.excludingSources(excluded, sourceOf)`;
      - `ApplicationSources.codeSources(annotated, sourceOf)`;
      - `ApplicationSources.initializers(loader, ordering(...), sourceOf)`;
      - `DescriptorMerger.mergeMerged(effective, ordered, annotated, sourceOf, factory, modelBuilder)`.
    - If `AnnotatedComponents.codeSource` is not accessible from foy-chappe, use `ApplicationSources`' code-source function, made public as `ApplicationSources.codeSource(Class<?>)`.
  - **`ApplicationSources`.**
    - Add the two overloads.
    - `initializers` applies `sourceOf` to `p.type()`. `ServiceLoader.Provider.type()` loads the class without instantiating it, so an excluded initializer still never runs.
    - Normalise every non-null result as `root(...)` does.
  - **`BUG.md`.** Add the new entry: id, date 2026-10-12, symptom, minimal repro (the test name), cause (attribution by code source only, under a shared parent-first loader), and status FIXED by `componentSource`. Mention vidocq#228 and `Aordering4Tests.absoluteOrderingTest`.

- [ ] **Step 4: Run and check GREEN.** Run the three foy-chappe test classes, then the whole build with `./mvnw -ntp install`. Report the totals.

- [ ] **Step 5: Commit.** `feat(chappe): let a harness attribute component classes to their jar (vidocq#228)`.

---

### Task 4: Documentation

**Files:**
- Modify: `docs/en/modules/ROOT/pages/usage.adoc`: the Builder discovery section (around the `discoverPluggability`/`applicationRoot`/`resourceProvider` list), and the ordering and exclusion paragraph.
- Modify: `docs/en/modules/ROOT/pages/reference.adoc`: the `FoyChappeBoot.Builder` table (`applicationLayer`, `componentSource`), the section "Optional JSP-engine service" (`hasPages`, application layer, JSP-only applications, module providers only), and the empty `Optional` rule.
- Modify: `docs/en/modules/ROOT/pages/whats-new.adoc`, `migration.adoc` (new builder methods, `JspEngine.hasPages`), and `internals.adoc` (discovery pipeline: attribution by `componentSource`).
- Modify: `docs/en/modules/ROOT/pages/getting-started.adoc`, only if it states when `build()` returns empty.

- [ ] **Step 1: Update the pages** listed above. Every statement must match the code of Tasks 1–3. Name the methods exactly. English only, no "JPMS".
- [ ] **Step 2: Check.** `grep -rn "no servlet, filter or listener" docs/en README.md` must not find a stale wording of the empty rule. Do not build the vidocq-docs site; say so in the report.
- [ ] **Step 3: Commit.** `docs: document the application layer, JSP-only applications and componentSource`.

---

## Self-review notes (plan author)

- **Coverage:**
  - R1: Tasks 1–2.
  - R2: Tasks 1–2, `hasPages` per the maintainer decision.
  - R3: Task 3, the hook only per the maintainer decision, with SCI attribution included because it shares the same cause.
  - Docs: Task 4.
- **Consistency:**
  - `jspEngine(ModuleLayer)` and the public 4-argument `deploy` are defined in Task 1 and used in Task 2.
  - `ApplicationSources` overloads are defined in Task 3 and used in Task 3.
- **Risks:**
  - foy-chappe tests use the class path, hence the package-private `jspEngineLookup` seam. The real `ServiceLoader` path is tested in foy-core (Task 1).
  - The Task 3 reproduction depends on a parent-first loader layout, which is checked by an explicit assertion.
