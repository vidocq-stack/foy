# CDI Session Context for `@SessionScoped` Beans Implementation Plan (foy#21)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `@SessionScoped` beans work in Foy under Weld (any CDI Full container) and under Vauban: one instance per HTTP session, a new one after `invalidate()`, destruction on expiry, on undeploy and at the end of an invalidating request, with the `@Initialized` / `@BeforeDestroyed` / `@Destroyed(SessionScoped.class)` events.

**Architecture:** option (b) of foy#21. Foy provides its own stateless context, `io.vidocq.foy.internal.cdi.FoySessionContext implements AlterableContext`, which reads a per-thread `SessionContextBinding` (a static `ThreadLocal`) and stores instances in a Foy-internal slot of `HttpSessionImpl` (a `SessionBeanStore`, never an attribute). CDI Full containers get the context from a portable extension (`FoySessionScopeExtension`, `AfterBeanDiscovery.addContext`, CDI 4.1 §17.2); Vauban gets it from a `@Discovery` method of `foy-cdi-vauban`'s existing `FoyWebExtension` (`MetaAnnotations.addContext`, 1-argument overload). A request listener `CdiSessionScopeListener` binds the context per request (inside the request context, which stays outermost) and is also the `SessionLifecycleHook` that `SessionManager`/`HttpSessionImpl` call on creation and around every invalidation path (in-request `invalidate()` deferred to request end; lazy expiry, reaper and `close()` destroyed at once, after the application's `sessionDestroyed` listeners, with a request context activated).

**Tech Stack:** Java 25 (virtual threads), Jakarta Servlet 6.1, Jakarta CDI 4.1 API (`jakarta.enterprise.cdi-api` 4.1.0, `requires static jakarta.cdi` in foy-core), Vauban 0.4.0-SNAPSHOT, Weld SE 6.0.4.Final (test only, `foy-it-weld`), Chappe, JUnit 5.

**Spec:** research report `/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq-foy/714bb865-5c9f-4aac-a489-970c2a1d362e/scratchpad/issue21-research.md` (section "Recommended design", treated as the spec; the scratchpad is temporary, so the digest below carries what the tasks need), issue foy#21 "feat(cdi): activate the session context for @SessionScoped beans (follow-up of #18)", CDI 4.1 §6.5.1, §6.7, §17.2, §17.5.4-5, and the `SessionScoped` Javadoc.

**Branch:** `pr/ybl/cdi-session-context`, created from an up-to-date `main` in Task 1 Step 0.

## Design digest (from the spec)

- Registration: portable `Extension` for CDI Full (a BCE must not define a context for a built-in scope of CDI Full, §6.7); a BCE `@Discovery` in `foy-cdi-vauban` only (Vauban has no portable extensions; `@SessionScoped` is not a CDI Lite built-in). Never the boolean `addContext` overload: on Weld it re-declares `@SessionScoped` non-passivating. No BCE in foy-core.
- The context is stateless: containers instantiate it (Vauban, Weld's lite translator) or wrap it (Weld's `PassivatingContextWrapper`), so Foy never holds the instance; state lives in the `ThreadLocal` binding and the session.
- Inactive context (no binding on the thread): `get`/`destroy` throw `ContextNotActiveException`, `isActive()` is false. Mandatory on Vauban, whose client proxies call the first registered context without checking `isActive()`.
- Lazy session: `get(c)` uses `getSession(false)` and returns `null` without a session; `get(c, cc)` uses `getSession(true)`.
- Store: keyed by `Contextual` `equals` (Weld passes serializable wrappers), one `ReentrantLock` per store, no `computeIfAbsent` around `contextual.create` (a session bean whose creation uses another session bean would update the map recursively).
- Step aside: if another session context is already active on the request thread when the request starts, Foy does not bind (same rule as the request listener of foy#18).
- Destruction: in-request `invalidate()` keeps serving the old instances until `requestDestroyed`, then `@BeforeDestroyed`, `contextual.destroy` per instance (log and continue), `@Destroyed`. Expiry (lazy, reaper) and `close()`: bind the dying session, activate a request context through `RequestContextController`, run the application's `sessionDestroyed` listeners (context active), then the same destruction sequence.
- Payload of the three events: the `HttpSession`.
- Weld: `@SessionScoped` beans must be `Serializable` (passivating scope, §17.5.5).

## Global Constraints

- English everywhere: code, Javadoc, comments, test names, commit messages, Markdown, AsciiDoc.
- Say "Java Modules" (or "Java module"), never the abbreviation of the Java Platform Module System.
- Zero runtime dependency beyond the Jakarta spec APIs (and chappe/vauban where they already are); `jakarta.cdi` stays `requires static` in foy-core.
- Static codegen over reflection: no new `getAnnotation(` / `getDeclaredConstructor(` / `Class.forName(` in foy-core main (`NoProductReflectionTest` must stay green). Weld's and Vauban's own reflective instantiation of the context class is the container's business.
- TDD: every step that adds behaviour writes the failing test first and runs it red.
- Virtual threads for every thread Foy starts; `ReentrantLock`, never `synchronized` around application callbacks added by this plan.
- Every reproducible bug found on the way goes to `BUG.md` (format of the existing entries); every performance figure to `BENCH.md` (none expected here).
- Commits: `git commit -S -s`, Conventional Commits, English, ticket reference `Refs: foy#21`, trailers `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi`.
- Build from the repository root with `./mvnw` (Java 25, Maven 3.9.16: `sdk env`). Gate of every task: `./mvnw -ntp -pl <touched modules> -am install` green.
- Final gate (Task 7): full reactor `./mvnw -ntp install`, harness `./mvnw -ntp -Ptck -pl foy-tck test`, then `./run-official-tck-servlet6.1.sh --all` compared per class with `foy-tck/tck-baseline.txt` (recipe in the `foy-tck/tck-tally.sh` header): zero regression, since the listener order changes for every request.
- Out of scope: any change to the vauban repository (caveats are documented in Task 7 and drafted as upstream issues in Appendix A).

## Review Focus

1. **Two requests of one session using a session bean for the first time at the same moment** — exactly one instance is created and both requests get it (Task 2 test `concurrentFirstUseOnOneSessionCreatesOneInstance`).
2. **`invalidate()` then the same session bean used again in the same request** — the old instance is still served; it is destroyed once, at the end of the request, and the next request gets a new one (Task 4 `invalidateDuringTheRequestDefersDestructionToRequestEnd`, Task 5 `invalidateGivesANewInstance`).
3. **A throwing `@PreDestroy`, a throwing application `sessionDestroyed` listener or a throwing event observer** — the other beans are still destroyed, `@Destroyed` still fires, the binding is cleared (Task 2 `destroyAllLogsAFailureAndGoesOn`, Task 4 `aThrowingSessionListenerStillDestroysTheBeans` and `aThrowingObserverStillDestroysTheBeans`).
4. **Undeploy with live sessions** — every session bean is destroyed by `SessionManager.close()`, before the CDI container shuts down (Task 4 `undeployDestroysTheBeansOfEveryLiveSession`; Task 5 closes the deployment before Weld).
5. **A session bean used on an `AsyncContext.start()` thread** — a clean `ContextNotActiveException`, never another session's bean nor an NPE; documented gap BUG-20261010-07 (Task 5 `asyncThreadHasNoSessionContext`).

---

## File map

| File | Responsibility | Task |
|---|---|---|
| `foy-core/src/main/java/io/vidocq/foy/internal/cdi/FoySessionContext.java` | The `AlterableContext` for `@SessionScoped`; stateless, delegates to `SessionContextBinding` | 1, 2 |
| `foy-core/src/main/java/io/vidocq/foy/internal/cdi/FoySessionScopeExtension.java` | Portable extension registering the context (CDI Full) | 1 |
| `foy-core/src/main/resources/META-INF/services/jakarta.enterprise.inject.spi.Extension` | Class-path registration of the extension | 1 |
| `foy-core/src/main/java/module-info.java` | `exports io.vidocq.foy.internal.cdi`, `provides ...Extension` | 1 |
| `foy-core/src/main/java/io/vidocq/foy/internal/cdi/SessionBeanStore.java` | Instances of one session | 2 |
| `foy-core/src/main/java/io/vidocq/foy/internal/cdi/SessionContextBinding.java` | Per-thread binding and the context operations | 2 |
| `foy-core/src/main/java/io/vidocq/foy/internal/session/HttpSessionImpl.java` | Internal `scopeState` slot; destruction through the hook | 2, 3 |
| `foy-core/src/main/java/io/vidocq/foy/internal/session/SessionLifecycleHook.java` | Internal hook on session creation and destruction | 3 |
| `foy-core/src/main/java/io/vidocq/foy/internal/session/SessionManager.java` | Calls the hook; `restartReaper` test seam | 3 |
| `foy-core/src/main/java/io/vidocq/foy/internal/cdi/CdiSessionScopeListener.java` | Request listener + lifecycle hook: binding, events, destruction | 4 |
| `foy-chappe/src/main/java/io/vidocq/foy/chappe/FoyChappeBoot.java` | Wiring when a `BeanManager` is given | 5 |
| `foy-it-other-containers/foy-it-weld/...` | Weld scenarios | 5 |
| `foy-cdi-vauban/...FoyWebExtension.java`, `module-info.java`, `pom.xml` | Vauban registration | 6 |
| `foy-cdi-vauban/src/test/.../FoySessionScopeVaubanTest.java` | Vauban scenarios + negative test | 6 |
| `docs/en/modules/ROOT/pages/{reference,usage,concepts,whats-new}.adoc`, `BUG.md` | Documentation, bugs | 7 |

---

### Task 1: Module placement — Foy's portable extension in foy-core, CDI still optional

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/cdi/FoySessionContext.java`
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/cdi/FoySessionScopeExtension.java`
- Create: `foy-core/src/main/resources/META-INF/services/jakarta.enterprise.inject.spi.Extension`
- Modify: `foy-core/src/main/java/module-info.java` (exports list, after `exports io.vidocq.foy.internal.bridge;`; add `provides`)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/cdi/OptionalCdiModuleTest.java`
- Test: `foy-core/src/test/java/io/vidocq/foy/nocdi/NoCdiSmoke.java`
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/cdi/FoySessionContextTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `public final class io.vidocq.foy.internal.cdi.FoySessionContext implements jakarta.enterprise.context.spi.AlterableContext` with `public FoySessionContext()`, `getScope()` returning `SessionScoped.class`. In this task it is always inactive; Task 2 makes it delegate to `SessionContextBinding`.
  - `public class io.vidocq.foy.internal.cdi.FoySessionScopeExtension implements jakarta.enterprise.inject.spi.Extension` with `public void registerSessionContext(@Observes AfterBeanDiscovery event)` calling `event.addContext(new FoySessionContext())`. Not `final` (Weld may subclass extension beans).
  - foy-core `module-info`: `exports io.vidocq.foy.internal.cdi;` and `provides jakarta.enterprise.inject.spi.Extension with io.vidocq.foy.internal.cdi.FoySessionScopeExtension;`.
  - Placement decision recorded in the commit message: **foy-core** (primary) or **foy-cdi** (fallback, Step 9).

Context for the implementer: JDK 25's `java.lang.module.Resolver.checkExportSuppliers` skips the "provides a service type the module does not read" failure when the service's module is a `requires static` dependency that is absent (`requiresStaticMissingModule`). The expected outcome is therefore that foy-core still resolves without `jakarta.cdi`; Step 6 proves it in a child JVM whose module path holds no CDI jar.

- [ ] **Step 0: Create the branch**

```bash
cd /Users/yblazart/projects/perso/vidocq/foy
git switch main && git pull --ff-only
git switch -c pr/ybl/cdi-session-context
```

- [ ] **Step 1: Write the failing tests**

`foy-core/src/test/java/io/vidocq/foy/internal/cdi/OptionalCdiModuleTest.java`:

```java
/*
 * (license header: copy the 19-line header of CdiRequestScopeListener.java verbatim)
 */
package io.vidocq.foy.internal.cdi;

import io.vidocq.foy.internal.session.SessionManager;
import io.vidocq.foy.nocdi.NoCdiSmoke;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * foy-core declares Foy's portable extension (foy#21) while CDI stays optional
 * ({@code requires static jakarta.cdi}): a module path without any CDI module still resolves
 * foy-core and runs its session code.
 */
class OptionalCdiModuleTest {

    private static final String EXTENSION = "jakarta.enterprise.inject.spi.Extension";

    @Test
    void moduleDescriptorProvidesThePortableExtension() {
        ModuleDescriptor descriptor = SessionManager.class.getModule().getDescriptor();
        assertNotNull(descriptor, "foy-core tests run on the module path");
        var providers = descriptor.provides().stream()
                .filter(p -> p.service().equals(EXTENSION))
                .flatMap(p -> p.providers().stream())
                .toList();
        assertEquals(List.of(FoySessionScopeExtension.class.getName()), providers);
    }

    @Test
    void classPathServiceFileNamesThePortableExtension() throws Exception {
        try (var in = SessionManager.class.getModule().getResourceAsStream("META-INF/services/" + EXTENSION)) {
            assertNotNull(in, "META-INF/services/" + EXTENSION);
            var names = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(l -> l.replaceFirst("#.*", "").strip())
                    .filter(l -> !l.isEmpty())
                    .toList();
            assertEquals(List.of(FoySessionScopeExtension.class.getName()), names);
        }
    }

    @Test
    void foyCoreResolvesAndRunsWithoutCdiOnTheModulePath() throws Exception {
        List<String> modulePath = new ArrayList<>();
        for (String module : List.of("io.vidocq.foy.core", "io.vidocq.foy.api", "jakarta.servlet",
                "io.vidocq.chappe.api")) {
            modulePath.add(location(module));
        }
        assertTrue(modulePath.stream().noneMatch(p -> p.contains("cdi-api")), modulePath::toString);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // NoCdiSmoke's package exists only in the test classes, so the child JVM loads it from the
        // class path (unnamed module) while foy-core comes from the module path.
        String testClasses = Path.of("target", "test-classes").toAbsolutePath().toString();
        Process process = new ProcessBuilder(java,
                "--module-path", String.join(File.pathSeparator, modulePath),
                "--add-modules", "io.vidocq.foy.core",
                "-cp", testClasses,
                NoCdiSmoke.class.getName())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the child JVM did not end");
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains(NoCdiSmoke.OK), output);
    }

    /** Where this test run's boot layer found {@code module}. */
    private static String location(String module) {
        return ModuleLayer.boot().configuration().findModule(module)
                .flatMap(m -> m.reference().location())
                .map(uri -> Path.of(uri).toString())
                .orElseThrow(() -> new AssertionError(module + " is not on this test run's module path"));
    }
}
```

`foy-core/src/test/java/io/vidocq/foy/nocdi/NoCdiSmoke.java`:

```java
/*
 * (license header)
 */
package io.vidocq.foy.nocdi;

import io.vidocq.foy.internal.session.HttpSessionImpl;
import io.vidocq.foy.internal.session.InMemorySessionStore;
import io.vidocq.foy.internal.session.SessionManager;

/**
 * Run in a child JVM by {@code OptionalCdiModuleTest}: foy-core on a module path that holds no CDI
 * module. Creates and invalidates a session, the code path every later task of foy#21 touches.
 */
public final class NoCdiSmoke {

    public static final String OK = "foy-core ok without jakarta.cdi";

    private NoCdiSmoke() {}

    public static void main(String[] args) {
        if (ModuleLayer.boot().findModule("jakarta.cdi").isPresent()) {
            throw new AssertionError("jakarta.cdi must not be resolved in this JVM");
        }
        Module core = ModuleLayer.boot().findModule("io.vidocq.foy.core")
                .orElseThrow(() -> new AssertionError("io.vidocq.foy.core is not resolved"));
        var manager = new SessionManager(new InMemorySessionStore(), null, 60);
        HttpSessionImpl session = manager.createNew();
        session.setAttribute("a", "1");
        session.invalidate();
        manager.close();
        System.out.println(OK + " " + core.getDescriptor().provides());
    }
}
```

`foy-core/src/test/java/io/vidocq/foy/internal/cdi/FoySessionContextTest.java`:

```java
/*
 * (license header)
 */
package io.vidocq.foy.internal.cdi;

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FoySessionContextTest {

    private final FoySessionContext context = new FoySessionContext();

    private static final Contextual<Object> BEAN = new Contextual<>() {
        @Override public Object create(CreationalContext<Object> creationalContext) { return new Object(); }
        @Override public void destroy(Object instance, CreationalContext<Object> creationalContext) {}
    };

    @Test
    void theScopeIsSessionScoped() {
        assertEquals(SessionScoped.class, context.getScope());
    }

    @Test
    void withoutABindingTheContextIsInactiveAndRefusesEveryCall() {
        assertFalse(context.isActive());
        assertThrows(ContextNotActiveException.class, () -> context.get(BEAN));
        assertThrows(ContextNotActiveException.class, () -> context.get(BEAN, null));
        assertThrows(ContextNotActiveException.class, () -> context.destroy(BEAN));
    }

    @Test
    void theExtensionRegistersAFoySessionContextAfterBeanDiscovery() {
        var added = new ArrayList<Object>();
        var event = (AfterBeanDiscovery) Proxy.newProxyInstance(AfterBeanDiscovery.class.getClassLoader(),
                new Class<?>[] {AfterBeanDiscovery.class}, (p, m, a) -> {
                    if (m.getName().equals("addContext")) {
                        added.add(a[0]);
                        return null;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        new FoySessionScopeExtension().registerSessionContext(event);
        assertEquals(1, added.size());
        assertInstanceOf(FoySessionContext.class, added.getFirst());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -ntp -pl foy-core -am test -Dtest='OptionalCdiModuleTest,FoySessionContextTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION FAILURE (`FoySessionContext`, `FoySessionScopeExtension` do not exist).

- [ ] **Step 3: Write the context and the extension**

`foy-core/src/main/java/io/vidocq/foy/internal/cdi/FoySessionContext.java`:

```java
/*
 * (license header)
 */
package io.vidocq.foy.internal.cdi;

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;

import java.lang.annotation.Annotation;

/**
 * Foy's context for {@code @SessionScoped} (foy#21), registered by {@link FoySessionScopeExtension}
 * on CDI Full containers and by foy-cdi-vauban's build compatible extension on Vauban.
 *
 * <p>Public, with a public no-argument constructor, and not a bean: containers instantiate it
 * themselves. It is stateless — several containers or deployments may create one each, and Weld
 * hands out a wrapper of it — so every call reads the state of the calling thread.</p>
 */
public final class FoySessionContext implements AlterableContext {

    /** Public no-argument constructor, required by {@code MetaAnnotations.addContext}. */
    public FoySessionContext() {}

    @Override
    public Class<? extends Annotation> getScope() {
        return SessionScoped.class;
    }

    @Override
    public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        throw notActive();
    }

    @Override
    public <T> T get(Contextual<T> contextual) {
        throw notActive();
    }

    @Override
    public boolean isActive() {
        return false;
    }

    @Override
    public void destroy(Contextual<?> contextual) {
        throw notActive();
    }

    private static ContextNotActiveException notActive() {
        return new ContextNotActiveException("the Foy session context is not active on " + Thread.currentThread());
    }
}
```

`foy-core/src/main/java/io/vidocq/foy/internal/cdi/FoySessionScopeExtension.java`:

```java
/*
 * (license header)
 */
package io.vidocq.foy.internal.cdi;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;

/**
 * Portable extension giving CDI Full containers (Weld, any Full implementation) Foy's context for
 * {@code @SessionScoped} (foy#21). CDI 4.1 §17.2 lets an extension register a context object for a
 * built-in scope through {@code AfterBeanDiscovery}; a build compatible extension may not (§6.7),
 * which is why Vauban gets the context from foy-cdi-vauban instead.
 *
 * <p>Found through {@code META-INF/services} on a class path and through the {@code provides} of
 * foy-core's module descriptor on a module path. Vauban ignores portable extensions.</p>
 */
public class FoySessionScopeExtension implements Extension {

    /** Public no-argument constructor, required by {@code ServiceLoader}. */
    public FoySessionScopeExtension() {}

    /**
     * Registers the context. The container may register its own session context too (Weld SE
     * registers an inactive bound one): CDI only forbids two <em>active</em> contexts for a scope.
     *
     * @param event the container's event
     */
    public void registerSessionContext(@Observes AfterBeanDiscovery event) {
        event.addContext(new FoySessionContext());
    }
}
```

`foy-core/src/main/resources/META-INF/services/jakarta.enterprise.inject.spi.Extension`:

```
io.vidocq.foy.internal.cdi.FoySessionScopeExtension
```

- [ ] **Step 4: Export the package and declare the provider**

In `foy-core/src/main/java/module-info.java`, add after `exports io.vidocq.foy.internal.bridge;`:

```java
    exports io.vidocq.foy.internal.cdi;
```

and after the `uses jakarta.servlet.ServletContainerInitializer;` line:

```java
    // foy#21: CDI Full containers find Foy's session context through this portable extension.
    // jakarta.cdi stays `requires static`: the JDK resolver accepts a `provides` whose service
    // module is an absent static dependency (OptionalCdiModuleTest proves it).
    provides jakarta.enterprise.inject.spi.Extension
            with io.vidocq.foy.internal.cdi.FoySessionScopeExtension;
```

- [ ] **Step 5: Run the descriptor and context tests**

Run: `./mvnw -ntp -pl foy-core -am test -Dtest='OptionalCdiModuleTest,FoySessionContextTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 6 tests (including `foyCoreResolvesAndRunsWithoutCdiOnTheModulePath`).

- [ ] **Step 6: Read the module-path verdict**

If `foyCoreResolvesAndRunsWithoutCdiOnTheModulePath` passed, the placement is **foy-core**: skip Steps 8-9 and go to Step 7.
If it failed with `java.lang.module.ResolutionException: Module io.vidocq.foy.core provides jakarta.enterprise.inject.spi.Extension but does not read a module that exports jakarta.enterprise.inject.spi`, apply the fallback (Step 9) before Step 7.

- [ ] **Step 7: Run the module gate and commit**

Run: `./mvnw -ntp -pl foy-core -am install`
Expected: BUILD SUCCESS (all foy-core tests, `NoProductReflectionTest` included).

```bash
git add foy-core/src/main/java/module-info.java \
        foy-core/src/main/java/io/vidocq/foy/internal/cdi \
        foy-core/src/main/resources/META-INF/services/jakarta.enterprise.inject.spi.Extension \
        foy-core/src/test/java/io/vidocq/foy/internal/cdi \
        foy-core/src/test/java/io/vidocq/foy/nocdi
git commit -S -s -F - <<'EOF'
feat(core): declare Foy's session context through a portable extension

FoySessionContext (inactive for now) and FoySessionScopeExtension live in
foy-core, exported package io.vidocq.foy.internal.cdi, declared both in
META-INF/services and with `provides` in the module descriptor. A child
JVM proves foy-core still resolves and runs with no CDI module on the
module path (requires static jakarta.cdi).

Refs: foy#21

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
EOF
```

- [ ] **Step 8: (only if Step 6 failed) record the finding**

Add `BUG-20261010-09` to `BUG.md` in the existing format: symptom = the `ResolutionException` text, repro = `OptionalCdiModuleTest.foyCoreResolvesAndRunsWithoutCdiOnTheModulePath` with the `provides` in foy-core, cause = the JDK resolver check of `provides` service packages, status FIXED by the foy-cdi module.

- [ ] **Step 9: (only if Step 6 failed) fallback: a `foy-cdi` module**

1. Remove the `provides` clause and the services file from foy-core. Keep `exports io.vidocq.foy.internal.cdi;`: Tasks 2-4 add `SessionBeanStore`, `SessionContextBinding` and `CdiSessionScopeListener` to that package in foy-core.
2. Create `foy-cdi/pom.xml` (parent `foy-parent`, artifactId `foy-cdi`, name `Foy :: CDI session context`), dependencies: `io.vidocq.foy:foy-core` (compile), `jakarta.enterprise:jakarta.enterprise.cdi-api` (`provided`), `org.junit.jupiter:junit-jupiter` (test). Add `<module>foy-cdi</module>` after `<module>foy-core</module>` in the root `pom.xml`, and a `foy-cdi` entry with `${project.version}` in its `dependencyManagement`.
3. Create `foy-cdi/src/main/java/module-info.java`:

```java
module io.vidocq.foy.cdi {
    requires transitive io.vidocq.foy.core;
    requires jakarta.cdi;

    exports io.vidocq.foy.cdi;

    provides jakarta.enterprise.inject.spi.Extension
            with io.vidocq.foy.cdi.FoySessionScopeExtension;
}
```

4. `git mv` `FoySessionContext.java` and `FoySessionScopeExtension.java` to `foy-cdi/src/main/java/io/vidocq/foy/cdi/` (package `io.vidocq.foy.cdi`), the services file to `foy-cdi/src/main/resources/META-INF/services/`, `FoySessionContextTest.java` to `foy-cdi/src/test/java/io/vidocq/foy/cdi/`. Split `OptionalCdiModuleTest`: `moduleDescriptorProvidesThePortableExtension` and `classPathServiceFileNamesThePortableExtension` move to foy-cdi (reading `FoySessionScopeExtension.class.getModule()`); `foyCoreResolvesAndRunsWithoutCdiOnTheModulePath` stays in foy-core.
5. Path changes in later tasks: Task 2 modifies `foy-cdi/src/main/java/io/vidocq/foy/cdi/FoySessionContext.java` (its delegation calls the public static methods of `io.vidocq.foy.internal.cdi.SessionContextBinding`, which stays in foy-core) and adds its delegation test to `foy-cdi/src/test/java/io/vidocq/foy/cdi/FoySessionContextTest.java`, with a verbatim copy of the `manager()`, `creationalContext()`, `FakeBean` and `FakeRequestSessions` members of Task 2's `CdiFakes` in `foy-cdi/src/test/java/io/vidocq/foy/cdi/TestFakes.java` (foy-cdi tests cannot see foy-core's test classes); Task 4's `CdiSessionScopeListenerTest.setUp` cannot see `FoySessionContext` from foy-core, so it registers `CdiFakes.proxy(Context.class, (m, a) -> switch (m) { case "isActive" -> SessionContextBinding.isActive(); case "getScope" -> SessionScoped.class; default -> throw new UnsupportedOperationException(m); })` instead of `new FoySessionContext()`; Task 5 adds a test dependency `io.vidocq.foy:foy-cdi` `${project.version}` to `foy-it-weld/pom.xml`; Task 6 makes foy-cdi-vauban depend on `foy-cdi` instead of `foy-core`, `requires io.vidocq.foy.cdi;`, and imports `io.vidocq.foy.cdi.FoySessionContext`; Task 7 documents the `foy-cdi` artifact as required next to foy-chappe for `@SessionScoped`.
6. Gate: `./mvnw -ntp -pl foy-core,foy-cdi -am install`; commit with the subject `feat(cdi): declare Foy's session context in a foy-cdi module` and the same trailers.

---

### Task 2: The context works when bound — per-session store and thread binding

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/cdi/SessionBeanStore.java`
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/cdi/SessionContextBinding.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/cdi/FoySessionContext.java` (delegation)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/session/HttpSessionImpl.java` (fields block `:50-65`, new methods after `isInvalidated()` `:221`)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/Await.java` (new shared test helper)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/cdi/CdiFakes.java` (new; extended in Task 4)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/cdi/SessionContextBindingTest.java`
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/cdi/SessionBeanStoreTest.java`
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/cdi/FoySessionContextTest.java` (add one test)

**Interfaces:**
- Consumes: `FoySessionContext` (Task 1); `SessionManager(SessionStore, ServletContext, int)`, `SessionManager.createNew()`, `SessionManager.changeSessionId(HttpSessionImpl)`, `HttpSessionImpl.invalidate()`, `HttpSessionImpl.isInvalidated()`, `HttpServletRequestImpl.getSession(boolean)`, `HttpServletRequestImpl.boundSession()` (existing).
- Produces:
  - `HttpSessionImpl`: `public Object scopeState()`, `public Object scopeState(java.util.function.Supplier<?> factory)` (get or atomically install), `public Object takeScopeState()` (detach and return, may be `null`).
  - `public final class SessionBeanStore`: `static SessionBeanStore of(HttpSessionImpl)` (create on first use), `static SessionBeanStore existing(HttpSessionImpl)` (may be `null`), `static SessionBeanStore take(HttpSessionImpl)` (detach, may be `null`), `<T> T get(Contextual<T>)`, `<T> T getOrCreate(Contextual<T>, CreationalContext<T>)`, `void destroy(Contextual<?>)`, `void destroyAll()` (reverse creation order, logs each failure at WARNING and goes on), `boolean isEmpty()`.
  - `public final class SessionContextBinding`:
    - nested `public interface SessionSource { HttpSessionImpl session(boolean create); HttpSessionImpl current(); static SessionSource of(HttpServletRequestImpl request); }`
    - `public static SessionContextBinding bind(SessionSource source)` (request binding), `public static SessionContextBinding bindTo(HttpSessionImpl session)` (destruction binding, serves that session even invalidated), `public static SessionContextBinding current()`, `public static boolean isActive()`, `public void unbind()` (restores the previous binding; idempotent).
    - `public boolean isRequestBinding()`, `public boolean owns(HttpSessionImpl session)`, `public void markInvalidatedHere(HttpSessionImpl session)`, `public List<HttpSessionImpl> invalidatedHere()`.
    - context operations: `public static <T> T get(Contextual<T>, CreationalContext<T>)`, `public static <T> T get(Contextual<T>)`, `public static void destroy(Contextual<?>)`; all throw `ContextNotActiveException` without a binding.
  - Test helpers: `io.vidocq.foy.internal.Await.until(BooleanSupplier, String)`; `CdiFakes.FakeBean`, `CdiFakes.creationalContext()`, `CdiFakes.FakeRequestSessions`, `CdiFakes.manager()`.

- [ ] **Step 1: Write the test helpers**

`foy-core/src/test/java/io/vidocq/foy/internal/Await.java`:

```java
/*
 * (license header)
 */
package io.vidocq.foy.internal;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/** Waits for an outcome of another thread by polling, with a deadline: never a fixed sleep. */
public final class Await {

    private Await() {}

    /** Polls {@code condition} every 10 ms; fails after 10 s naming {@code what}. */
    public static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("timed out after 10 s waiting for " + what);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }
}
```

`foy-core/src/test/java/io/vidocq/foy/internal/cdi/CdiFakes.java`:

```java
/*
 * (license header)
 */
package io.vidocq.foy.internal.cdi;

import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.session.HttpSessionImpl;
import io.vidocq.foy.internal.session.InMemorySessionStore;
import io.vidocq.foy.internal.session.SessionManager;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Test doubles for the session context tests: no CDI container involved. */
final class CdiFakes {

    private CdiFakes() {}

    /** A session manager with a 30-minute timeout and no reaper. */
    static SessionManager manager() {
        return new SessionManager(new InMemorySessionStore(), new VidocqServletContext("/"), 1800);
    }

    static <T> CreationalContext<T> creationalContext() {
        return new CreationalContext<>() {
            @Override public void push(T incompleteInstance) {}
            @Override public void release() {}
        };
    }

    /**
     * A bean named {@code name}: its instances are {@code "<name>#<n>"}, its destructions are
     * recorded in {@code destroyed}. Equal by name, as Weld's serializable wrappers of one bean are.
     */
    static final class FakeBean implements Contextual<Object> {
        final String name;
        final AtomicInteger created = new AtomicInteger();
        final List<Object> destroyed;
        volatile Runnable onCreate = () -> {};
        volatile RuntimeException destroyFailure;

        FakeBean(String name) {
            this(name, new CopyOnWriteArrayList<>());
        }

        FakeBean(String name, List<Object> destroyed) {
            this.name = name;
            this.destroyed = destroyed;
        }

        @Override
        public Object create(CreationalContext<Object> creationalContext) {
            onCreate.run();
            return name + "#" + created.incrementAndGet();
        }

        @Override
        public void destroy(Object instance, CreationalContext<Object> creationalContext) {
            destroyed.add(instance);
            if (destroyFailure != null) throw destroyFailure;
        }

        @Override public boolean equals(Object o) { return o instanceof FakeBean b && b.name.equals(name); }
        @Override public int hashCode() { return name.hashCode(); }
        @Override public String toString() { return "FakeBean[" + name + "]"; }
    }

    /**
     * The session lookup of one request, as {@code HttpServletRequestImpl} does it: the session it
     * holds while valid, else a new one when asked to create one.
     */
    static final class FakeRequestSessions implements SessionContextBinding.SessionSource {
        private final SessionManager manager;
        private volatile HttpSessionImpl held;

        FakeRequestSessions(SessionManager manager) {
            this(manager, null);
        }

        FakeRequestSessions(SessionManager manager, HttpSessionImpl held) {
            this.manager = manager;
            this.held = held;
        }

        @Override
        public HttpSessionImpl session(boolean create) {
            HttpSessionImpl s = held;
            if (s != null && !s.isInvalidated()) return s;
            if (!create) return null;
            held = manager.createNew();
            return held;
        }

        @Override
        public HttpSessionImpl current() {
            return held;
        }
    }
}
```

- [ ] **Step 2: Write the failing tests**

`foy-core/src/test/java/io/vidocq/foy/internal/cdi/SessionContextBindingTest.java`:

```java
/*
 * (license header)
 */
package io.vidocq.foy.internal.cdi;

import io.vidocq.foy.internal.Await;
import io.vidocq.foy.internal.cdi.CdiFakes.FakeBean;
import io.vidocq.foy.internal.cdi.CdiFakes.FakeRequestSessions;
import io.vidocq.foy.internal.session.HttpSessionImpl;
import io.vidocq.foy.internal.session.SessionManager;
import jakarta.enterprise.context.ContextNotActiveException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.vidocq.foy.internal.cdi.CdiFakes.creationalContext;
import static org.junit.jupiter.api.Assertions.*;

class SessionContextBindingTest {

    private final SessionManager manager = CdiFakes.manager();
    private final FakeBean cart = new FakeBean("cart");

    @AfterEach
    void tearDown() {
        while (SessionContextBinding.current() != null) SessionContextBinding.current().unbind();
        manager.close();
    }

    @Test
    void withoutABindingEveryOperationThrowsContextNotActive() {
        assertFalse(SessionContextBinding.isActive());
        assertThrows(ContextNotActiveException.class, () -> SessionContextBinding.get(cart, creationalContext()));
        assertThrows(ContextNotActiveException.class, () -> SessionContextBinding.get(cart));
        assertThrows(ContextNotActiveException.class, () -> SessionContextBinding.destroy(cart));
    }

    @Test
    void oneInstancePerBeanPerSession() {
        SessionContextBinding.bind(new FakeRequestSessions(manager));
        Object first = SessionContextBinding.get(cart, creationalContext());
        Object second = SessionContextBinding.get(cart, creationalContext());
        assertSame(first, second);
        assertEquals(1, cart.created.get());
    }

    @Test
    void equalContextualsShareTheInstance() {
        SessionContextBinding.bind(new FakeRequestSessions(manager));
        Object first = SessionContextBinding.get(cart, creationalContext());
        assertSame(first, SessionContextBinding.get(new FakeBean("cart"), creationalContext()));
    }

    @Test
    void anotherSessionGetsAnotherInstance() {
        var first = SessionContextBinding.bind(new FakeRequestSessions(manager));
        Object a = SessionContextBinding.get(cart, creationalContext());
        first.unbind();
        SessionContextBinding.bind(new FakeRequestSessions(manager));
        Object b = SessionContextBinding.get(cart, creationalContext());
        assertNotEquals(a, b);
    }

    @Test
    void getWithoutCreationalContextNeverCreatesASession() {
        var request = new FakeRequestSessions(manager);
        SessionContextBinding.bind(request);
        assertNull(SessionContextBinding.get(cart));
        assertNull(SessionContextBinding.get(cart, null));
        assertNull(request.current());
        assertEquals(0, manager.store().size());
    }

    @Test
    void getWithCreationalContextCreatesTheSessionLazily() {
        var request = new FakeRequestSessions(manager);
        SessionContextBinding.bind(request);
        assertEquals(0, manager.store().size());
        Object instance = SessionContextBinding.get(cart, creationalContext());
        assertNotNull(request.current());
        assertEquals(1, manager.store().size());
        assertSame(instance, SessionContextBinding.get(cart));
    }

    @Test
    void theInstancesAreNotSessionAttributes() {
        var request = new FakeRequestSessions(manager);
        SessionContextBinding.bind(request);
        SessionContextBinding.get(cart, creationalContext());
        assertFalse(request.current().getAttributeNames().hasMoreElements());
    }

    @Test
    void destroyCallsTheContextualAndTheNextGetCreatesAgain() {
        SessionContextBinding.bind(new FakeRequestSessions(manager));
        Object first = SessionContextBinding.get(cart, creationalContext());
        SessionContextBinding.destroy(cart);
        assertEquals(java.util.List.of(first), cart.destroyed);
        assertNotEquals(first, SessionContextBinding.get(cart, creationalContext()));
    }

    @Test
    void changeSessionIdKeepsTheInstances() {
        var request = new FakeRequestSessions(manager);
        SessionContextBinding.bind(request);
        Object before = SessionContextBinding.get(cart, creationalContext());
        manager.changeSessionId(request.current());
        assertSame(before, SessionContextBinding.get(cart, creationalContext()));
    }

    @Test
    void aNestedBindingRestoresThePreviousOne() {
        var outer = SessionContextBinding.bind(new FakeRequestSessions(manager));
        HttpSessionImpl other = manager.createNew();
        var inner = SessionContextBinding.bindTo(other);
        assertSame(inner, SessionContextBinding.current());
        assertFalse(inner.isRequestBinding());
        inner.unbind();
        assertSame(outer, SessionContextBinding.current());
        outer.unbind();
        assertNull(SessionContextBinding.current());
    }

    @Test
    void aDestructionBindingServesItsSessionEvenInvalidated() {
        var request = new FakeRequestSessions(manager);
        var binding = SessionContextBinding.bind(request);
        Object instance = SessionContextBinding.get(cart, creationalContext());
        HttpSessionImpl session = request.current();
        binding.unbind();
        session.invalidate();   // no lifecycle hook yet: the store stays attached to the session
        SessionContextBinding.bindTo(session);
        assertSame(instance, SessionContextBinding.get(cart));
    }

    @Test
    void concurrentFirstUseOnOneSessionCreatesOneInstance() throws Exception {
        HttpSessionImpl session = manager.createNew();
        var creating = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        cart.onCreate = () -> {
            creating.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        };
        var results = new ConcurrentLinkedQueue<Object>();
        Runnable firstUse = () -> {
            var binding = SessionContextBinding.bind(new FakeRequestSessions(manager, session));
            try {
                results.add(SessionContextBinding.get(cart, creationalContext()));
            } finally {
                binding.unbind();
            }
        };
        Thread first = Thread.ofVirtual().start(firstUse);
        assertTrue(creating.await(10, TimeUnit.SECONDS));
        Thread second = Thread.ofVirtual().start(firstUse);
        Await.until(() -> second.getState() == Thread.State.WAITING, "the second request to wait for the store lock");
        release.countDown();
        assertTrue(first.join(Duration.ofSeconds(10)));
        assertTrue(second.join(Duration.ofSeconds(10)));
        assertEquals(2, results.size());
        assertEquals(1, cart.created.get());
        assertEquals(1, new HashSet<>(results).size());
    }

    @Test
    void aSessionBeanCreatedWhileCreatingAnotherOneDoesNotDeadlock() throws Exception {
        var inner = new FakeBean("inner");
        cart.onCreate = () -> SessionContextBinding.get(inner, creationalContext());
        var failure = new AtomicReference<Throwable>();
        Thread t = Thread.ofVirtual().start(() -> {
            var binding = SessionContextBinding.bind(new FakeRequestSessions(manager));
            try {
                SessionContextBinding.get(cart, creationalContext());
                SessionContextBinding.get(inner, creationalContext());
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                binding.unbind();
            }
        });
        assertTrue(t.join(Duration.ofSeconds(10)), "deadlock: the creation did not end");
        assertNull(failure.get());
        assertEquals(1, cart.created.get());
        assertEquals(1, inner.created.get());
    }
}
```

`foy-core/src/test/java/io/vidocq/foy/internal/cdi/SessionBeanStoreTest.java`:

```java
/*
 * (license header)
 */
package io.vidocq.foy.internal.cdi;

import io.vidocq.foy.internal.LogCapture;
import io.vidocq.foy.internal.cdi.CdiFakes.FakeBean;
import io.vidocq.foy.internal.session.HttpSessionImpl;
import io.vidocq.foy.internal.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.vidocq.foy.internal.cdi.CdiFakes.creationalContext;
import static org.junit.jupiter.api.Assertions.*;

class SessionBeanStoreTest {

    private final SessionManager manager = CdiFakes.manager();

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void theStoreIsCreatedOnFirstUseAndCanBeDetached() {
        HttpSessionImpl session = manager.createNew();
        assertNull(SessionBeanStore.existing(session));
        SessionBeanStore store = SessionBeanStore.of(session);
        assertSame(store, SessionBeanStore.of(session));
        assertSame(store, SessionBeanStore.existing(session));
        assertSame(store, SessionBeanStore.take(session));
        assertNull(SessionBeanStore.existing(session));
        assertNull(SessionBeanStore.take(session));
    }

    @Test
    void destroyAllRunsInReverseCreationOrder() {
        var order = new CopyOnWriteArrayList<Object>();
        var first = new FakeBean("first", order);
        var second = new FakeBean("second", order);
        SessionBeanStore store = SessionBeanStore.of(manager.createNew());
        Object a = store.getOrCreate(first, creationalContext());
        Object b = store.getOrCreate(second, creationalContext());
        store.destroyAll();
        assertEquals(List.of(b, a), order);
        assertTrue(store.isEmpty());
    }

    @Test
    void destroyAllLogsAFailureAndGoesOn() {
        var order = new CopyOnWriteArrayList<Object>();
        var failing = new FakeBean("failing", order);
        failing.destroyFailure = new IllegalStateException("boom");
        var healthy = new FakeBean("healthy", order);
        SessionBeanStore store = SessionBeanStore.of(manager.createNew());
        store.getOrCreate(healthy, creationalContext());
        store.getOrCreate(failing, creationalContext());
        try (var log = LogCapture.of(SessionBeanStore.class.getName())) {
            store.destroyAll();
            assertEquals(1, log.warnings().size(), log.warnings()::toString);
            assertTrue(log.warnings().getFirst().contains("FakeBean[failing]"), log.warnings()::toString);
        }
        assertEquals(List.of("failing#1", "healthy#1"), order);
    }
}
```

Add to `FoySessionContextTest`:

```java
    @Test
    void whenBoundTheContextDelegatesToTheBinding() {
        var manager = CdiFakes.manager();
        var binding = SessionContextBinding.bind(new CdiFakes.FakeRequestSessions(manager));
        try {
            var bean = new CdiFakes.FakeBean("delegated");
            assertTrue(context.isActive());
            Object instance = context.get(bean, CdiFakes.creationalContext());
            assertSame(instance, context.get(bean));
            context.destroy(bean);
            assertEquals(java.util.List.of(instance), bean.destroyed);
        } finally {
            binding.unbind();
            manager.close();
        }
    }
```

(add `import static org.junit.jupiter.api.Assertions.assertSame;` and `assertTrue`).

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -ntp -pl foy-core -am test -Dtest='SessionContextBindingTest,SessionBeanStoreTest,FoySessionContextTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION FAILURE (`SessionContextBinding`, `SessionBeanStore`, `scopeState` do not exist).

- [ ] **Step 4: Add the internal slot to `HttpSessionImpl`**

Imports: `java.util.concurrent.atomic.AtomicReference`, `java.util.function.Supplier`. Field, after `attributes`:

```java
    /**
     * Foy-internal state of the CDI session context (foy#21): never an attribute, so the
     * application does not see it, no attribute listener fires, and an id change keeps it.
     */
    private final AtomicReference<Object> scopeState = new AtomicReference<>();
```

Methods, after `isInvalidated()`:

```java
    /** The CDI session context's state of this session, or {@code null}. */
    public Object scopeState() {
        return scopeState.get();
    }

    /** The CDI session context's state of this session, installed from {@code factory} on first use. */
    public Object scopeState(Supplier<?> factory) {
        Object current = scopeState.get();
        if (current != null) return current;
        Object created = factory.get();
        Object witness = scopeState.compareAndExchange(null, created);
        return witness == null ? created : witness;
    }

    /** Detaches the CDI session context's state, for its destruction; {@code null} when none. */
    public Object takeScopeState() {
        return scopeState.getAndSet(null);
    }
```

- [ ] **Step 5: Write `SessionBeanStore`**

```java
/*
 * (license header)
 */
package io.vidocq.foy.internal.cdi;

import io.vidocq.foy.internal.session.HttpSessionImpl;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The {@code @SessionScoped} instances of one HTTP session (foy#21), kept in a Foy-internal slot of
 * the session.
 *
 * <p>Keyed by the {@code Contextual}'s {@code equals}: Weld hands a passivating context serializable
 * wrappers, a new one per call. One lock per store, held while a bean is created, so two requests of
 * a session create a bean once; the lock is reentrant, so the creation of a session bean may use
 * another session bean of the same session. {@code ConcurrentHashMap.computeIfAbsent} is not used:
 * that nested creation would update the map recursively and throw.</p>
 */
public final class SessionBeanStore {

    private static final System.Logger LOG = System.getLogger(SessionBeanStore.class.getName());

    private record Entry<T>(Contextual<T> contextual, T instance, CreationalContext<T> creationalContext) {
        void destroy() {
            contextual.destroy(instance, creationalContext);
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    /** In creation order; destroyed in reverse. Guarded by {@link #lock}. */
    private final Map<Contextual<?>, Entry<?>> entries = new LinkedHashMap<>();

    /** The store of {@code session}, created on first use. */
    public static SessionBeanStore of(HttpSessionImpl session) {
        return (SessionBeanStore) session.scopeState(SessionBeanStore::new);
    }

    /** The store of {@code session}, or {@code null} when no session bean was created in it. */
    public static SessionBeanStore existing(HttpSessionImpl session) {
        return session.scopeState() instanceof SessionBeanStore store ? store : null;
    }

    /** Detaches the store of {@code session} for its destruction; {@code null} when none. */
    public static SessionBeanStore take(HttpSessionImpl session) {
        return session.takeScopeState() instanceof SessionBeanStore store ? store : null;
    }

    public <T> T get(Contextual<T> contextual) {
        lock.lock();
        try {
            Entry<?> entry = entries.get(contextual);
            return entry == null ? null : cast(entry.instance());
        } finally {
            lock.unlock();
        }
    }

    public <T> T getOrCreate(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        lock.lock();
        try {
            Entry<?> entry = entries.get(contextual);
            if (entry != null) return cast(entry.instance());
            T instance = contextual.create(creationalContext);
            entries.put(contextual, new Entry<>(contextual, instance, creationalContext));
            return instance;
        } finally {
            lock.unlock();
        }
    }

    /** {@code AlterableContext.destroy}: a failure of the bean's destruction reaches the caller. */
    public void destroy(Contextual<?> contextual) {
        Entry<?> entry;
        lock.lock();
        try {
            entry = entries.remove(contextual);
        } finally {
            lock.unlock();
        }
        if (entry != null) entry.destroy();
    }

    /** Destroys every instance, last created first; a failing destruction is logged, the others still run. */
    public void destroyAll() {
        List<Entry<?>> all;
        lock.lock();
        try {
            all = new ArrayList<>(entries.values());
            entries.clear();
        } finally {
            lock.unlock();
        }
        for (int i = all.size() - 1; i >= 0; i--) {
            Entry<?> entry = all.get(i);
            try {
                entry.destroy();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "destroying the session-scoped instance of " + entry.contextual() + " failed", e);
            }
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return entries.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object instance) {
        return (T) instance;
    }
}
```

- [ ] **Step 6: Write `SessionContextBinding`**

```java
/*
 * (license header)
 */
package io.vidocq.foy.internal.cdi;

import io.vidocq.foy.internal.bridge.HttpServletRequestImpl;
import io.vidocq.foy.internal.session.HttpSessionImpl;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The state of Foy's session context on the current thread (foy#21). {@link FoySessionContext} is
 * active exactly while a binding exists on the calling thread (CDI 4.1 §6.2: a context is active
 * with respect to a thread).
 *
 * <p>Two kinds of binding: a <em>request binding</em> ({@link #bind}), set for each request by
 * {@code CdiSessionScopeListener}, finds the session lazily through the request; a <em>destruction
 * binding</em> ({@link #bindTo}) serves one dying session while its listeners and its beans'
 * destruction run. Bindings nest: {@link #unbind()} restores the previous one.</p>
 *
 * <p>A request that invalidates its own session keeps being served the old instances until it ends
 * ({@link #markInvalidatedHere}); they are destroyed then.</p>
 */
public final class SessionContextBinding {

    private static final System.Logger LOG = System.getLogger(SessionContextBinding.class.getName());
    private static final ThreadLocal<SessionContextBinding> CURRENT = new ThreadLocal<>();

    /** How a request reaches its session. */
    public interface SessionSource {

        /** {@code request.getSession(create)}; {@code null} when there is none and {@code create} is false. */
        HttpSessionImpl session(boolean create);

        /** The session the request already holds, even invalidated, without lookup or creation. */
        HttpSessionImpl current();

        /** The source of a Foy request. */
        static SessionSource of(HttpServletRequestImpl request) {
            return new SessionSource() {
                @Override
                public HttpSessionImpl session(boolean create) {
                    return request.getSession(create) instanceof HttpSessionImpl s ? s : null;
                }

                @Override
                public HttpSessionImpl current() {
                    return request.boundSession();
                }
            };
        }
    }

    private final SessionContextBinding previous;
    /** {@code null} for a destruction binding. */
    private final SessionContextBinding.SessionSource source;
    private final List<HttpSessionImpl> invalidatedHere = new ArrayList<>(1);
    /** The session last served. */
    private HttpSessionImpl session;
    private boolean unbound;

    private SessionContextBinding(SessionContextBinding previous, SessionSource source, HttpSessionImpl session) {
        this.previous = previous;
        this.source = source;
        this.session = session;
    }

    /** Binds the context to a request on the current thread. */
    public static SessionContextBinding bind(SessionSource source) {
        var binding = new SessionContextBinding(CURRENT.get(), Objects.requireNonNull(source, "source"), null);
        CURRENT.set(binding);
        return binding;
    }

    /** Binds the context to {@code session}, served even once invalidated, on the current thread. */
    public static SessionContextBinding bindTo(HttpSessionImpl session) {
        var binding = new SessionContextBinding(CURRENT.get(), null, Objects.requireNonNull(session, "session"));
        binding.invalidatedHere.add(session);
        CURRENT.set(binding);
        return binding;
    }

    public static SessionContextBinding current() {
        return CURRENT.get();
    }

    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    /** Ends this binding and restores the previous live one. Idempotent; call it in a {@code finally}. */
    public void unbind() {
        if (unbound) return;
        unbound = true;
        if (CURRENT.get() != this) {
            LOG.log(System.Logger.Level.WARNING, "session context binding ended out of order on {0}",
                    Thread.currentThread());
            return;
        }
        SessionContextBinding restored = previous;
        while (restored != null && restored.unbound) restored = restored.previous;
        if (restored == null) CURRENT.remove();
        else CURRENT.set(restored);
    }

    public boolean isRequestBinding() {
        return source != null;
    }

    /** {@code true} when {@code candidate} is the session this binding serves or its request holds. */
    public boolean owns(HttpSessionImpl candidate) {
        return candidate == session || (source != null && candidate == source.current());
    }

    /** Keeps serving {@code dying}, invalidated by this request, until the request ends. */
    public void markInvalidatedHere(HttpSessionImpl dying) {
        if (!invalidatedHere.contains(dying)) invalidatedHere.add(dying);
        session = dying;
    }

    /** The sessions this request invalidated, whose beans are destroyed when it ends. */
    public List<HttpSessionImpl> invalidatedHere() {
        return List.copyOf(invalidatedHere);
    }

    HttpSessionImpl session(boolean create) {
        HttpSessionImpl s = session;
        if (s != null && (!s.isInvalidated() || invalidatedHere.contains(s))) return s;
        if (source == null) return null;
        s = source.session(create);
        if (s != null) session = s;
        return s;
    }

    // ---- the operations of FoySessionContext ----

    public static <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        SessionContextBinding binding = requireActive();
        if (creationalContext == null) return get(contextual);
        HttpSessionImpl s = binding.session(true);
        if (s == null) throw new IllegalStateException("no HTTP session can be created for this request");
        return SessionBeanStore.of(s).getOrCreate(contextual, creationalContext);
    }

    public static <T> T get(Contextual<T> contextual) {
        HttpSessionImpl s = requireActive().session(false);
        if (s == null) return null;
        SessionBeanStore store = SessionBeanStore.existing(s);
        return store == null ? null : store.get(contextual);
    }

    public static void destroy(Contextual<?> contextual) {
        HttpSessionImpl s = requireActive().session(false);
        if (s == null) return;
        SessionBeanStore store = SessionBeanStore.existing(s);
        if (store != null) store.destroy(contextual);
    }

    private static SessionContextBinding requireActive() {
        SessionContextBinding binding = CURRENT.get();
        if (binding == null) {
            throw new ContextNotActiveException("the Foy session context is not active on " + Thread.currentThread());
        }
        return binding;
    }
}
```

- [ ] **Step 7: Make `FoySessionContext` delegate**

Replace the bodies (and remove `notActive()`):

```java
    @Override
    public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        return SessionContextBinding.get(contextual, creationalContext);
    }

    @Override
    public <T> T get(Contextual<T> contextual) {
        return SessionContextBinding.get(contextual);
    }

    @Override
    public boolean isActive() {
        return SessionContextBinding.isActive();
    }

    @Override
    public void destroy(Contextual<?> contextual) {
        SessionContextBinding.destroy(contextual);
    }
```

Drop the `ContextNotActiveException` import; extend the class Javadoc with "Active while a {@link SessionContextBinding} exists on the calling thread."

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw -ntp -pl foy-core -am test -Dtest='SessionContextBindingTest,SessionBeanStoreTest,FoySessionContextTest,OptionalCdiModuleTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 9: Module gate and commit**

Run: `./mvnw -ntp -pl foy-core -am install`
Expected: BUILD SUCCESS.

```bash
git add foy-core/src/main/java/io/vidocq/foy/internal/cdi foy-core/src/main/java/io/vidocq/foy/internal/session/HttpSessionImpl.java \
        foy-core/src/test/java/io/vidocq/foy/internal/Await.java foy-core/src/test/java/io/vidocq/foy/internal/cdi
git commit -S -s -F - <<'EOF'
feat(core): store session-scoped instances per HTTP session

SessionContextBinding binds Foy's session context to the current thread
(request bindings resolve the session lazily, destruction bindings serve
a dying session); SessionBeanStore keeps the instances of one session in
an internal slot of HttpSessionImpl, keyed by Contextual equals, behind
one reentrant lock. FoySessionContext delegates to them.

Refs: foy#21

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
EOF
```

---

### Task 3: Session lifecycle hook in the session manager

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/session/SessionLifecycleHook.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/session/SessionManager.java` (fields `:59-73`, `createNew` `:129-141`, reaper section `:187-234`)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/session/HttpSessionImpl.java` (`completeInvalidation` `:134-160`)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/session/SessionLifecycleHookTest.java`

**Interfaces:**
- Consumes: `io.vidocq.foy.internal.Await.until(BooleanSupplier, String)` (Task 2), `io.vidocq.foy.internal.LogCapture` (existing).
- Produces:
  - `public interface SessionLifecycleHook { SessionLifecycleHook NONE; void sessionCreated(HttpSessionImpl session); void aroundDestruction(HttpSessionImpl session, Runnable destruction); }`. `destruction` is the Servlet half of an invalidation (application `sessionDestroyed` listeners, then attribute unbinding); the hook runs it once, on the calling thread; the session is still in the store while the hook runs.
  - `SessionManager.setLifecycleHook(SessionLifecycleHook hook)` (public; `null` means `NONE`), `SessionLifecycleHook lifecycleHook()` (package-private).
  - `SessionManager.restartReaper(Duration period)` (public test seam for foy-it-weld and foy-cdi-vauban; no-op after `close()`).
  - Order guarantees: `sessionCreated` runs after the application's `sessionCreated` listeners; every invalidation path (`invalidate()`, lazy expiry in `find`/`peek`, reaper, `close()`) goes through `aroundDestruction`; a failing hook is logged and the session is still invalidated once and removed.

- [ ] **Step 1: Write the failing tests**

`foy-core/src/test/java/io/vidocq/foy/internal/session/SessionLifecycleHookTest.java`:

```java
/*
 * (license header)
 */
package io.vidocq.foy.internal.session;

import io.vidocq.foy.internal.Await;
import io.vidocq.foy.internal.LogCapture;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.listener.ListenerRegistry;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** The internal hook the CDI session context uses (foy#21): when the manager calls it. */
class SessionLifecycleHookTest {

    private final List<String> log = new CopyOnWriteArrayList<>();
    private final InMemorySessionStore store = new InMemorySessionStore();
    private final ListenerRegistry registry = new ListenerRegistry();
    private volatile String destructionThread;
    private SessionManager manager;

    /** Records its calls, the thread of the destruction, and whether the session is still stored. */
    private final class RecordingHook implements SessionLifecycleHook {
        @Override
        public void sessionCreated(HttpSessionImpl session) {
            log.add("hook:created");
        }

        @Override
        public void aroundDestruction(HttpSessionImpl session, Runnable destruction) {
            destructionThread = Thread.currentThread().getName();
            log.add("hook:before");
            destruction.run();
            log.add("hook:after:stored=" + store.get(session.getId()).isPresent());
        }
    }

    @BeforeEach
    void setUp() {
        registry.register(new HttpSessionListener() {
            @Override public void sessionCreated(HttpSessionEvent se) { log.add("listener:created"); }
            @Override public void sessionDestroyed(HttpSessionEvent se) { log.add("listener:destroyed"); }
        });
        manager = new SessionManager(store, new VidocqServletContext("/"), 1800);
        manager.setListenerRegistry(registry);
        manager.setLifecycleHook(new RecordingHook());
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    private HttpSessionImpl sessionWithUnbindProbe() {
        HttpSessionImpl session = manager.createNew();
        session.setAttribute("probe", new HttpSessionBindingListener() {
            @Override public void valueUnbound(HttpSessionBindingEvent event) { log.add("unbound"); }
        });
        log.clear();
        return session;
    }

    @Test
    void creationCallsTheHookAfterTheListeners() {
        manager.createNew();
        assertEquals(List.of("listener:created", "hook:created"), log);
    }

    @Test
    void invalidateRunsTheServletDestructionInsideTheHook() {
        HttpSessionImpl session = sessionWithUnbindProbe();
        session.invalidate();
        assertEquals(List.of("hook:before", "listener:destroyed", "unbound", "hook:after:stored=true"), log);
        assertTrue(session.isInvalidated());
        assertEquals(0, store.size());
    }

    @Test
    void theReaperGoesThroughTheHookOnItsOwnThread() {
        HttpSessionImpl session = sessionWithUnbindProbe();
        session.setMaxInactiveInterval(1);
        manager.restartReaper(Duration.ofMillis(50));
        Await.until(() -> store.size() == 0, "the reaper to expire the session");
        assertEquals("foy-session-reaper", destructionThread);
        assertEquals(List.of("hook:before", "listener:destroyed", "unbound", "hook:after:stored=true"), log);
    }

    @Test
    void lazyExpiryGoesThroughTheHook() {
        HttpSessionImpl session = sessionWithUnbindProbe();
        session.setMaxInactiveInterval(1);
        Await.until(() -> manager.peek(session.getId()) == null, "the session to expire");
        assertEquals(List.of("hook:before", "listener:destroyed", "unbound", "hook:after:stored=true"), log);
    }

    @Test
    void closeGoesThroughTheHookForEveryLiveSession() {
        manager.createNew();
        manager.createNew();
        log.clear();
        manager.close();
        assertEquals(2, log.stream().filter("hook:after:stored=true"::equals).count(), log::toString);
    }

    @Test
    void aHookFailingBeforeTheDestructionStillInvalidatesOnce() {
        manager.setLifecycleHook(new SessionLifecycleHook() {
            @Override public void sessionCreated(HttpSessionImpl session) {}
            @Override public void aroundDestruction(HttpSessionImpl session, Runnable destruction) {
                throw new IllegalStateException("hook before");
            }
        });
        HttpSessionImpl session = sessionWithUnbindProbe();
        try (var warnings = LogCapture.of(HttpSessionImpl.class.getName())) {
            session.invalidate();
            assertEquals(1, warnings.warnings().size(), warnings.warnings()::toString);
        }
        assertEquals(List.of("listener:destroyed", "unbound"), log);
        assertTrue(session.isInvalidated());
        assertEquals(0, store.size());
    }

    @Test
    void aHookFailingAfterTheDestructionDoesNotRunItTwice() {
        manager.setLifecycleHook(new SessionLifecycleHook() {
            @Override public void sessionCreated(HttpSessionImpl session) {}
            @Override public void aroundDestruction(HttpSessionImpl session, Runnable destruction) {
                destruction.run();
                throw new IllegalStateException("hook after");
            }
        });
        HttpSessionImpl session = sessionWithUnbindProbe();
        session.invalidate();
        assertEquals(List.of("listener:destroyed", "unbound"), log);
        assertEquals(0, store.size());
    }

    @Test
    void aHookFailingOnCreationDoesNotFailTheCreation() {
        manager.setLifecycleHook(new SessionLifecycleHook() {
            @Override public void sessionCreated(HttpSessionImpl session) { throw new IllegalStateException("created"); }
            @Override public void aroundDestruction(HttpSessionImpl session, Runnable destruction) { destruction.run(); }
        });
        try (var warnings = LogCapture.of(SessionManager.class.getName())) {
            assertNotNull(manager.createNew());
            assertEquals(1, warnings.warnings().size(), warnings.warnings()::toString);
        }
    }

    @Test
    void restartReaperAfterCloseStartsNothing() {
        manager.close();
        manager.restartReaper(Duration.ofMillis(50));
        assertFalse(manager.isReaperRunning());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -ntp -pl foy-core -am test -Dtest=SessionLifecycleHookTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION FAILURE (`SessionLifecycleHook`, `setLifecycleHook`, `restartReaper` do not exist).

- [ ] **Step 3: Write `SessionLifecycleHook`**

```java
/*
 * (license header)
 */
package io.vidocq.foy.internal.session;

/**
 * Foy-internal hook on the life of the sessions of one web application, used by the CDI session
 * context (foy#21). It is not an {@code HttpSessionListener}: the destroyed listeners run in one
 * loop that a throwing application listener ends, and the CDI context must wrap that loop (its
 * beans stay usable from the listeners, then they are destroyed whatever the listeners did).
 */
public interface SessionLifecycleHook {

    /** No hook: the destruction runs as is. */
    SessionLifecycleHook NONE = new SessionLifecycleHook() {
        @Override
        public void sessionCreated(HttpSessionImpl session) {}

        @Override
        public void aroundDestruction(HttpSessionImpl session, Runnable destruction) {
            destruction.run();
        }
    };

    /** A session was stored and the application's {@code sessionCreated} listeners ran, on the creating thread. */
    void sessionCreated(HttpSessionImpl session);

    /**
     * Wraps the Servlet half of an invalidation — the application's {@code sessionDestroyed}
     * listeners, then the unbinding of the attributes — which the hook must run exactly once, on the
     * calling thread. Called for {@code invalidate()}, expiry (lazy or by the reaper) and undeploy,
     * while the session is still in its store.
     */
    void aroundDestruction(HttpSessionImpl session, Runnable destruction);
}
```

- [ ] **Step 4: Call the hook from `SessionManager`**

Field, after `listenerRegistry`:

```java
    /** CDI session context (foy#21); set by the bootstrap before the deployment serves requests. */
    private volatile SessionLifecycleHook lifecycleHook = SessionLifecycleHook.NONE;
```

Accessors, after `listenerRegistry()`:

```java
    /** Installs the internal session hook (the CDI session context); {@code null} removes it. */
    public void setLifecycleHook(SessionLifecycleHook hook) {
        this.lifecycleHook = hook == null ? SessionLifecycleHook.NONE : hook;
    }

    SessionLifecycleHook lifecycleHook() { return lifecycleHook; }
```

In `createNew()`, replace `listenerRegistry.fireSessionCreated(s);` with:

```java
            listenerRegistry.fireSessionCreated(s);
            try {
                lifecycleHook.sessionCreated(s);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "session lifecycle hook failed on creation of session " + id, e);
            }
```

After `startReaper(Duration)`:

```java
    /**
     * Test seam for the integration tests of other modules (foy-it-weld, foy-cdi-vauban), where the
     * minute granularity of the session timeout makes the default period too long: replaces the
     * running reaper with one of {@code period}. A no-op once the manager is closed.
     */
    public synchronized void restartReaper(Duration period) {
        if (closed) return;
        if (reaper != null) reaper.shutdown();
        reaper = null;
        startReaper(period);
    }
```

- [ ] **Step 5: Route `completeInvalidation` through the hook**

In `HttpSessionImpl`, add the field after `accessCount`:

```java
    /** The Servlet half of the invalidation ran; touched only by the invalidating thread. */
    private boolean servletDestructionRan;
```

Replace `completeInvalidation()` with:

```java
    /**
     * Second half of an invalidation claimed by {@link #claimExpired}, {@link #claimInvalidation}
     * or {@link #invalidate()}: the session lifecycle hook (the CDI session context, foy#21) wraps
     * {@code sessionDestroyed} and the unbinding of the attributes, then the session leaves its
     * store. A failing listener or hook is logged and does not stop the invalidation; the Servlet
     * half runs exactly once whatever the hook does.
     */
    void completeInvalidation() {
        try {
            manager.lifecycleHook().aroundDestruction(this, this::destroyServletState);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "session lifecycle hook failed for session " + id, e);
        } finally {
            if (!servletDestructionRan) destroyServletState();
            manager.onInvalidated(this);
        }
    }

    /** {@code sessionDestroyed}, then the unbinding of the attributes; runs once. */
    private void destroyServletState() {
        if (servletDestructionRan) return;
        servletDestructionRan = true;
        try {
            manager.listenerRegistry().fireSessionDestroyed(this);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "sessionDestroyed failed for session " + id, e);
        }
        invalidated = true;
        for (String name : new ArrayList<>(attributes.keySet())) {
            Object v = attributes.remove(name);
            if (v == null) continue;
            try {
                if (v instanceof HttpSessionBindingListener l) {
                    l.valueUnbound(new HttpSessionBindingEvent(this, name, v));
                }
                manager.listenerRegistry().fireSessionAttributeRemoved(this, name, v);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "unbinding " + name + " failed for session " + id, e);
            }
        }
    }
```

- [ ] **Step 6: Run the tests**

Run: `./mvnw -ntp -pl foy-core -am test -Dtest='SessionLifecycleHookTest,SessionCoreTest,SessionManagerTest,HttpSessionImplTest,ServletSessionEndToEndTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (the existing session tests are unchanged under the `NONE` hook).

- [ ] **Step 7: Module gate and commit**

Run: `./mvnw -ntp -pl foy-core -am install`
Expected: BUILD SUCCESS.

```bash
git add foy-core/src/main/java/io/vidocq/foy/internal/session foy-core/src/test/java/io/vidocq/foy/internal/session/SessionLifecycleHookTest.java
git commit -S -s -F - <<'EOF'
feat(core): add an internal session lifecycle hook

SessionManager calls a SessionLifecycleHook after sessionCreated and
around the Servlet half of every invalidation (invalidate, lazy expiry,
reaper, close), with the session still stored. A failing hook is logged;
the listeners and the unbinding still run exactly once. restartReaper is
a test seam for the integration tests of other modules.

Refs: foy#21

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
EOF
```

---

### Task 4: `CdiSessionScopeListener` — binding per request, events, destruction

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/cdi/CdiSessionScopeListener.java`
- Modify: `foy-core/src/test/java/io/vidocq/foy/internal/cdi/CdiFakes.java` (add `FakeBeanManager`, `activeForeignContext()`, `servletRequest()`, `proxy(...)`)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/cdi/CdiSessionScopeListenerTest.java`

**Interfaces:**
- Consumes: `SessionContextBinding` and `SessionBeanStore` (Task 2), `SessionLifecycleHook`, `SessionManager.setLifecycleHook`, `SessionManager.restartReaper`/`startReaper`, `SessionManager.peek` (Task 3 / existing), `HttpServletRequestImpl` (existing).
- Produces:
  - `public final class CdiSessionScopeListener implements ServletRequestListener, SessionLifecycleHook` with `public CdiSessionScopeListener(BeanManager beanManager)`.
  - Package-private `void bind(ServletRequest request, SessionContextBinding.SessionSource source)` (what `requestInitialized` does for an `HttpServletRequestImpl`; tests call it directly).
  - Behaviour: steps aside when another session context is active on the thread at `requestInitialized` (no Foy binding then, so Foy's own context reports inactive); fires `@Initialized(SessionScoped.class)` after creation, `@BeforeDestroyed` then `@Destroyed(SessionScoped.class)` around the beans' destruction, payload the `HttpSession`; a failing observer is logged.

- [ ] **Step 1: Extend the fakes**

Add to `CdiFakes` (imports: `jakarta.enterprise.context.SessionScoped`, `jakarta.enterprise.context.control.RequestContextController`, `jakarta.enterprise.context.spi.Context`, `jakarta.enterprise.event.Event`, `jakarta.enterprise.inject.Instance`, `jakarta.enterprise.inject.spi.BeanManager`, `jakarta.servlet.ServletRequest`, `jakarta.servlet.http.HttpSession`, `java.lang.annotation.Annotation`, `java.lang.reflect.Proxy`, `java.util.ArrayList`, `java.util.function.Consumer`, `java.util.stream.Collectors`):

```java
    @FunctionalInterface
    interface Handler {
        Object handle(String method, Object[] args) throws Throwable;
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<?> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> switch (m.getName()) {
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            case "toString" -> type.getSimpleName() + "@fake";
            default -> handler.handle(m.getName(), a == null ? new Object[0] : a);
        });
    }

    /** A request object used only as a map key by the listener. */
    static ServletRequest servletRequest() {
        return proxy(ServletRequest.class, (m, a) -> {
            throw new UnsupportedOperationException(m);
        });
    }

    /** Another container's session context, active on every thread. */
    static Context activeForeignContext() {
        return proxy(Context.class, (m, a) -> switch (m) {
            case "isActive" -> true;
            case "getScope" -> SessionScoped.class;
            default -> throw new UnsupportedOperationException(m);
        });
    }

    /** A BeanManager answering what CdiSessionScopeListener asks: session contexts, events, a RequestContextController. */
    static final class FakeBeanManager {
        final List<Context> sessionContexts = new CopyOnWriteArrayList<>();
        /** {@code "<qualifier simple names>:<session id>"} per fired event. */
        final List<String> events = new CopyOnWriteArrayList<>();
        /** {@code "activate@<thread>"} / {@code "deactivate@<thread>"}. */
        final List<String> requestContext = new CopyOnWriteArrayList<>();
        /** Runs after an event is recorded; may throw to emulate a failing observer. */
        volatile Consumer<String> onEvent = e -> {};

        BeanManager proxy() {
            return CdiFakes.proxy(BeanManager.class, (m, a) -> switch (m) {
                case "getContexts" -> a[0] == SessionScoped.class ? List.copyOf(sessionContexts) : List.of();
                case "getEvent" -> event(List.of());
                case "createInstance" -> instance();
                default -> throw new UnsupportedOperationException(m);
            });
        }

        private Event<Object> event(List<Annotation> qualifiers) {
            return CdiFakes.proxy(Event.class, (m, a) -> switch (m) {
                case "select" -> {
                    if (a.length == 1 && a[0] instanceof Annotation[] more) {
                        var all = new ArrayList<>(qualifiers);
                        all.addAll(List.of(more));
                        yield event(all);
                    }
                    throw new UnsupportedOperationException("select with " + a.length + " arguments");
                }
                case "fire" -> {
                    String entry = qualifiers.stream().map(q -> q.annotationType().getSimpleName())
                            .collect(Collectors.joining(",")) + ":" + ((HttpSession) a[0]).getId();
                    events.add(entry);
                    onEvent.accept(entry);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(m);
            });
        }

        private Instance<Object> instance() {
            RequestContextController controller = CdiFakes.proxy(RequestContextController.class, (m, a) -> switch (m) {
                case "activate" -> {
                    requestContext.add("activate@" + Thread.currentThread().getName());
                    yield true;
                }
                case "deactivate" -> {
                    requestContext.add("deactivate@" + Thread.currentThread().getName());
                    yield null;
                }
                default -> throw new UnsupportedOperationException(m);
            });
            Instance<Object> selected = CdiFakes.proxy(Instance.class, (m, a) -> switch (m) {
                case "isResolvable" -> true;
                case "get" -> controller;
                default -> throw new UnsupportedOperationException(m);
            });
            return CdiFakes.proxy(Instance.class, (m, a) -> switch (m) {
                case "select" -> selected;
                default -> throw new UnsupportedOperationException(m);
            });
        }
    }
```

- [ ] **Step 2: Write the failing tests**

`foy-core/src/test/java/io/vidocq/foy/internal/cdi/CdiSessionScopeListenerTest.java`:

```java
/*
 * (license header)
 */
package io.vidocq.foy.internal.cdi;

import io.vidocq.foy.internal.Await;
import io.vidocq.foy.internal.cdi.CdiFakes.FakeBean;
import io.vidocq.foy.internal.cdi.CdiFakes.FakeBeanManager;
import io.vidocq.foy.internal.cdi.CdiFakes.FakeRequestSessions;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.listener.ListenerRegistry;
import io.vidocq.foy.internal.session.HttpSessionImpl;
import io.vidocq.foy.internal.session.InMemorySessionStore;
import io.vidocq.foy.internal.session.SessionManager;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.vidocq.foy.internal.cdi.CdiFakes.creationalContext;
import static org.junit.jupiter.api.Assertions.*;

class CdiSessionScopeListenerTest {

    private final FakeBeanManager cdi = new FakeBeanManager();
    private final ListenerRegistry registry = new ListenerRegistry();
    private final VidocqServletContext servletContext = new VidocqServletContext("/");
    private final ServletRequest request = CdiFakes.servletRequest();
    private final FakeBean cart = new FakeBean("cart");
    private SessionManager manager;
    private CdiSessionScopeListener listener;

    @BeforeEach
    void setUp() {
        cdi.sessionContexts.add(new FoySessionContext());
        manager = new SessionManager(new InMemorySessionStore(), servletContext, 1800);
        manager.setListenerRegistry(registry);
        listener = new CdiSessionScopeListener(cdi.proxy());
        manager.setLifecycleHook(listener);
    }

    @AfterEach
    void tearDown() {
        manager.close();
        while (SessionContextBinding.current() != null) SessionContextBinding.current().unbind();
    }

    private FakeRequestSessions begin() {
        var sessions = new FakeRequestSessions(manager);
        listener.bind(request, sessions);
        return sessions;
    }

    private void end() {
        listener.requestDestroyed(new ServletRequestEvent(servletContext, request));
    }

    @Test
    void aRequestBindsTheContextUntilItEnds() {
        begin();
        assertTrue(SessionContextBinding.isActive());
        end();
        assertFalse(SessionContextBinding.isActive());
    }

    @Test
    void aRequestThatIsNotAFoyRequestIsNotBound() {
        listener.requestInitialized(new ServletRequestEvent(servletContext, request));
        assertFalse(SessionContextBinding.isActive());
        end();
    }

    @Test
    void creatingTheSessionFiresInitialized() {
        var sessions = begin();
        SessionContextBinding.get(cart, creationalContext());
        end();
        assertEquals(List.of("Initialized:" + sessions.current().getId()), cdi.events);
    }

    @Test
    void invalidateDuringTheRequestDefersDestructionToRequestEnd() {
        var sessions = begin();
        Object instance = SessionContextBinding.get(cart, creationalContext());
        HttpSessionImpl session = sessions.current();
        session.invalidate();
        assertEquals(List.of(), cart.destroyed, "destroyed only at the end of the request");
        assertSame(instance, SessionContextBinding.get(cart, creationalContext()), "the old instance is still served");
        end();
        assertEquals(List.of(instance), cart.destroyed);
        String id = session.getId();
        assertEquals(List.of("Initialized:" + id, "BeforeDestroyed:" + id, "Destroyed:" + id), cdi.events);
        assertNull(SessionContextBinding.current());
    }

    @Test
    void invalidateBeforeAnyBeanUseStillDefers() {
        var sessions = begin();
        HttpSessionImpl session = sessions.session(true);
        session.invalidate();
        Object instance = SessionContextBinding.get(cart, creationalContext());
        assertEquals(List.of(), cart.destroyed);
        end();
        assertEquals(List.of(instance), cart.destroyed);
    }

    @Test
    void theApplicationsSessionDestroyedListenerSeesTheBeansOnInvalidate() {
        var seen = new CopyOnWriteArrayList<Object>();
        registry.register(new HttpSessionListener() {
            @Override public void sessionDestroyed(HttpSessionEvent se) { seen.add(SessionContextBinding.get(cart)); }
        });
        var sessions = begin();
        Object instance = SessionContextBinding.get(cart, creationalContext());
        sessions.current().invalidate();
        end();
        assertEquals(List.of(instance), seen);
    }

    @Test
    void reaperExpiryDestroysTheBeansAfterTheApplicationsListeners() {
        var sessions = begin();
        Object instance = SessionContextBinding.get(cart, creationalContext());
        HttpSessionImpl session = sessions.current();
        end();
        var seen = new CopyOnWriteArrayList<String>();
        registry.register(new HttpSessionListener() {
            @Override public void sessionDestroyed(HttpSessionEvent se) {
                seen.add("active=" + SessionContextBinding.isActive() + " bean=" + SessionContextBinding.get(cart)
                        + " destroyedYet=" + !cart.destroyed.isEmpty());
            }
        });
        session.setMaxInactiveInterval(1);
        manager.restartReaper(Duration.ofMillis(50));
        String id = session.getId();
        Await.until(() -> cdi.requestContext.size() == 2, "the reaper to end the destruction");
        assertEquals(List.of("active=true bean=" + instance + " destroyedYet=false"), seen);
        assertEquals(List.of(instance), cart.destroyed);
        assertEquals(List.of("Initialized:" + id, "BeforeDestroyed:" + id, "Destroyed:" + id), cdi.events);
        assertEquals(List.of("activate@foy-session-reaper", "deactivate@foy-session-reaper"), cdi.requestContext);
    }

    @Test
    void undeployDestroysTheBeansOfEveryLiveSession() {
        begin();
        Object first = SessionContextBinding.get(cart, creationalContext());
        end();
        begin();
        Object second = SessionContextBinding.get(cart, creationalContext());
        end();
        manager.close();
        assertEquals(2, cart.destroyed.size());
        assertTrue(cart.destroyed.containsAll(List.of(first, second)));
        assertEquals(2, cdi.events.stream().filter(e -> e.startsWith("Destroyed:")).count(), cdi.events::toString);
    }

    @Test
    void aThrowingSessionListenerStillDestroysTheBeans() {
        registry.register(new HttpSessionListener() {
            @Override public void sessionDestroyed(HttpSessionEvent se) { throw new IllegalStateException("listener"); }
        });
        begin();
        Object instance = SessionContextBinding.get(cart, creationalContext());
        end();
        manager.close();
        assertEquals(List.of(instance), cart.destroyed);
    }

    @Test
    void aThrowingObserverStillDestroysTheBeans() {
        cdi.onEvent = e -> {
            if (e.startsWith("BeforeDestroyed")) throw new IllegalStateException("observer");
        };
        var sessions = begin();
        Object instance = SessionContextBinding.get(cart, creationalContext());
        sessions.current().invalidate();
        end();
        assertEquals(List.of(instance), cart.destroyed);
        assertTrue(cdi.events.contains("Destroyed:" + sessions.current().getId()), cdi.events::toString);
        assertNull(SessionContextBinding.current());
    }

    @Test
    void anotherActiveSessionContextMakesFoyStepAside() {
        cdi.sessionContexts.add(CdiFakes.activeForeignContext());
        var sessions = begin();
        assertFalse(SessionContextBinding.isActive());
        sessions.session(true);
        end();
        assertEquals(List.of(), cdi.events, "no Foy event while another context serves the thread");
    }

    @Test
    void theExpiryOfAnotherSessionDuringARequestKeepsTheRequestBinding() {
        var first = begin();
        Object old = SessionContextBinding.get(cart, creationalContext());
        HttpSessionImpl expiring = first.current();
        end();
        expiring.setMaxInactiveInterval(1);
        begin();
        SessionContextBinding requestBinding = SessionContextBinding.current();
        Await.until(() -> manager.peek(expiring.getId()) == null, "the lazy expiry of the other session");
        assertEquals(List.of(old), cart.destroyed);
        assertSame(requestBinding, SessionContextBinding.current());
        end();
    }
}
```

Note for the implementer: `peek` (not `find`) drives the lazy expiry, because `find` begins an access and an accessed session never expires.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -ntp -pl foy-core -am test -Dtest=CdiSessionScopeListenerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION FAILURE (`CdiSessionScopeListener` does not exist).

- [ ] **Step 4: Write `CdiSessionScopeListener`**

```java
/*
 * (license header)
 */
package io.vidocq.foy.internal.cdi;

import io.vidocq.foy.internal.bridge.HttpServletRequestImpl;
import io.vidocq.foy.internal.session.HttpSessionImpl;
import io.vidocq.foy.internal.session.SessionLifecycleHook;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives Foy's session context (foy#21): binds it for each request and destroys the session beans
 * when their session ends.
 *
 * <p>As a request listener, registered by the deployment just inside {@link
 * io.vidocq.foy.internal.listener.CdiRequestScopeListener}, it binds the context when the request
 * starts and unbinds it when it ends, on the request thread. If another session context is already
 * active on that thread (an embedding runtime, a Weld bound context the application activated), it
 * steps aside: CDI forbids two active contexts for a scope (§6.5.1).</p>
 *
 * <p>As the {@link SessionLifecycleHook} of the session manager, it fires
 * {@code @Initialized(SessionScoped.class)} for each new session and destroys the beans of a dying
 * session: at the end of the request that invalidated it ("at the very end of any request in which
 * invalidate() was called"), or at once on expiry and undeploy, after the application's
 * {@code sessionDestroyed} listeners, which still see the context active. Destruction fires
 * {@code @BeforeDestroyed}, destroys every instance (a failure is logged, the others go on), then
 * fires {@code @Destroyed}; the payload is the {@code HttpSession}. Outside a request, a request
 * context is activated for the duration, so {@code @PreDestroy} may use request-scoped beans.</p>
 *
 * <p>Threads started for asynchronous work ({@code AsyncContext.start}) are not bound, as for the
 * request context (BUG-20261010-07).</p>
 */
public final class CdiSessionScopeListener implements ServletRequestListener, SessionLifecycleHook {

    private static final System.Logger LOG = System.getLogger(CdiSessionScopeListener.class.getName());

    private final BeanManager beanManager;
    private final Map<ServletRequest, SessionContextBinding> bound = new ConcurrentHashMap<>();
    /** The session contexts of the container; fixed once it is booted, read on first use. */
    private volatile List<Context> sessionContexts;

    public CdiSessionScopeListener(BeanManager beanManager) {
        this.beanManager = beanManager;
    }

    // ---- request listener ----

    @Override
    public void requestInitialized(ServletRequestEvent event) {
        if (event.getServletRequest() instanceof HttpServletRequestImpl request) {
            bind(request, SessionContextBinding.SessionSource.of(request));
        }
    }

    void bind(ServletRequest request, SessionContextBinding.SessionSource source) {
        if (SessionContextBinding.current() == null && otherSessionContextActive()) return;
        bound.put(request, SessionContextBinding.bind(source));
    }

    @Override
    public void requestDestroyed(ServletRequestEvent event) {
        SessionContextBinding binding = bound.remove(event.getServletRequest());
        if (binding == null) return;
        List<HttpSessionImpl> dying = binding.invalidatedHere();
        binding.unbind();
        for (HttpSessionImpl session : dying) destroyScope(session);
    }

    // ---- session lifecycle hook ----

    @Override
    public void sessionCreated(HttpSessionImpl session) {
        if (SessionContextBinding.current() == null && otherSessionContextActive()) return;
        fire(Initialized.Literal.of(SessionScoped.class), session);
    }

    @Override
    public void aroundDestruction(HttpSessionImpl session, Runnable destruction) {
        SessionContextBinding current = SessionContextBinding.current();
        if (current != null && current.isRequestBinding() && current.owns(session)) {
            // invalidate() from the request using this session: destroyed when the request ends.
            current.markInvalidatedHere(session);
            destruction.run();
            return;
        }
        if (current == null && otherSessionContextActive()) {
            // Another session context serves this thread: Foy's events would duplicate its own.
            destruction.run();
            SessionBeanStore store = SessionBeanStore.take(session);
            if (store != null) store.destroyAll();
            return;
        }
        SessionContextBinding binding = SessionContextBinding.bindTo(session);
        RequestContextController requestContext = activateRequestContext();
        try {
            destruction.run();
        } finally {
            binding.unbind();
            try {
                destroyScope(session);
            } finally {
                if (requestContext != null) requestContext.deactivate();
            }
        }
    }

    // ---- internals ----

    /** {@code @BeforeDestroyed}, the beans' destruction (context bound to the dying session), {@code @Destroyed}. */
    private void destroyScope(HttpSessionImpl session) {
        SessionContextBinding binding = SessionContextBinding.bindTo(session);
        try {
            fire(BeforeDestroyed.Literal.of(SessionScoped.class), session);
            SessionBeanStore store = SessionBeanStore.take(session);
            if (store != null) store.destroyAll();
        } finally {
            binding.unbind();
        }
        fire(Destroyed.Literal.of(SessionScoped.class), session);
    }

    private void fire(Annotation qualifier, HttpSessionImpl session) {
        try {
            beanManager.getEvent().select(qualifier).fire(session);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "an observer of " + qualifier + " failed for session " + session.getId(), e);
        }
    }

    /**
     * {@code true} when a session context other than Foy's is active on this thread. Called only
     * while no Foy binding exists on the thread, so Foy's own context reports itself inactive.
     */
    private boolean otherSessionContextActive() {
        List<Context> contexts = sessionContexts;
        if (contexts == null) {
            try {
                contexts = List.copyOf(beanManager.getContexts(SessionScoped.class));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "no session contexts from the BeanManager: {0}", e.toString());
                contexts = List.of();
            }
            sessionContexts = contexts;
        }
        for (Context context : contexts) {
            try {
                if (context.isActive()) return true;
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "isActive failed on {0}: {1}", context, e.toString());
            }
        }
        return false;
    }

    /** Activates a request context for a destruction outside a request; {@code null} when none was activated. */
    private RequestContextController activateRequestContext() {
        try {
            var controllers = beanManager.createInstance().select(RequestContextController.class);
            if (!controllers.isResolvable()) return null;
            RequestContextController controller = controllers.get();
            return controller.activate() ? controller : null;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "No RequestContextController: {0}", e.toString());
            return null;
        }
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./mvnw -ntp -pl foy-core -am test -Dtest='CdiSessionScopeListenerTest,SessionContextBindingTest,SessionLifecycleHookTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 6: Module gate and commit**

Run: `./mvnw -ntp -pl foy-core -am install`
Expected: BUILD SUCCESS.

```bash
git add foy-core/src/main/java/io/vidocq/foy/internal/cdi/CdiSessionScopeListener.java foy-core/src/test/java/io/vidocq/foy/internal/cdi
git commit -S -s -F - <<'EOF'
feat(core): bind the session context per request and destroy session beans

CdiSessionScopeListener binds Foy's session context for each request
(stepping aside when another session context is active), fires
@Initialized/@BeforeDestroyed/@Destroyed(SessionScoped.class) with the
HttpSession, defers the destruction of an invalidated session to the end
of the request, and destroys expired and undeployed sessions' beans after
the application's sessionDestroyed listeners, inside a request context.

Refs: foy#21

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
EOF
```

- [ ] **Step 7: A throwing `requestDestroyed` must not skip the other listeners (controller ruling)**

Today `ListenerRegistry.fireRequestDestroyed` stops at the first listener that throws. An application listener added after Foy's CDI listeners runs *before* them on destroy (reverse order), so a throw there would skip the session-context unbinding, the deferred destruction of an invalidated session, and the request-context deactivation of foy#18. Every listener now runs; the first `RuntimeException` is rethrown at the end with the later ones suppressed (an `Error` still propagates at once).

Add to `foy-core/src/test/java/io/vidocq/foy/internal/listener/ListenerRegistryTest.java`:

```java
    @Test
    void aThrowingRequestDestroyedListenerDoesNotSkipTheOthers() {
        List<String> trace = new ArrayList<>();
        ServletRequestListener outer = new ServletRequestListener() {
            @Override public void requestDestroyed(ServletRequestEvent e) { trace.add("outer-destroyed"); }
        };
        ServletRequestListener first = new ServletRequestListener() {
            @Override public void requestDestroyed(ServletRequestEvent e) { throw new IllegalStateException("first"); }
        };
        ServletRequestListener second = new ServletRequestListener() {
            @Override public void requestDestroyed(ServletRequestEvent e) { throw new IllegalArgumentException("second"); }
        };
        var reg = new ListenerRegistry();
        reg.register(outer);
        reg.register(second);
        reg.register(first);
        var ctx = new VidocqServletContext("/");
        var thrown = assertThrows(IllegalStateException.class, () -> reg.fireRequestDestroyed(ctx, null));
        assertEquals("first", thrown.getMessage());
        assertEquals(1, thrown.getSuppressed().length);
        assertEquals("second", thrown.getSuppressed()[0].getMessage());
        assertEquals(List.of("outer-destroyed"), trace);
    }
```

Run: `./mvnw -ntp -pl foy-core -am test -Dtest=ListenerRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL (`outer-destroyed` missing, no suppressed exception).

Replace `fireRequestDestroyed` in `foy-core/src/main/java/io/vidocq/foy/internal/listener/ListenerRegistry.java` with:

```java
    public void fireRequestDestroyed(ServletContext ctx, ServletRequest req) {
        if (requestListeners.isEmpty()) return;
        var evt = new ServletRequestEvent(ctx, req);
        RuntimeException failure = null;
        // Every listener runs: container listeners (CDI request and session scopes) sit outermost
        // and must still unbind when an application listener throws.
        for (int i = requestListeners.size() - 1; i >= 0; i--) {
            try {
                requestListeners.get(i).requestDestroyed(evt);
            } catch (RuntimeException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }
```

Run the same test: PASS. Then `./mvnw -ntp -pl foy-core -am install`: BUILD SUCCESS.

```bash
git add foy-core/src/main/java/io/vidocq/foy/internal/listener/ListenerRegistry.java foy-core/src/test/java/io/vidocq/foy/internal/listener/ListenerRegistryTest.java
git commit -S -s -F - <<'EOF'
fix(core): run every requestDestroyed listener when one throws

A throwing application listener skipped the listeners registered before
it, among them Foy's CDI request and session scope listeners, which then
never unbound their contexts. Every listener now runs and the first
failure is rethrown with the later ones suppressed.

Refs: foy#21

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
EOF
```

---

### Task 5: Wiring in `FoyChappeBoot`, proven on Weld SE

**Files:**
- Modify: `foy-chappe/src/main/java/io/vidocq/foy/chappe/FoyChappeBoot.java:290-293` (+ import)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/listener/ListenerRegistry.java:79-84` (Javadoc of `addFirst`)
- Create: `foy-it-other-containers/foy-it-weld/src/test/java/io/vidocq/foy/it/weld/SessionCart.java`
- Create: `.../weld/SessionEvents.java`, `.../weld/SessionServlet.java`, `.../weld/SessionFilter.java`, `.../weld/SessionRequestListener.java`
- Modify: `foy-it-other-containers/foy-it-weld/src/test/java/io/vidocq/foy/it/weld/WeldPortabilityTest.java`
- Modify: `foy-it-other-containers/foy-it-weld/pom.xml` (description)

**Interfaces:**
- Consumes: `CdiSessionScopeListener(BeanManager)` (Task 4), `SessionManager.setLifecycleHook`, `SessionManager.restartReaper(Duration)` (Task 3), `Deployment.sessionManager()`, `FoyChappeBoot.Mounted.deployment()` (existing), `RequestToken` (existing `@RequestScoped` bean of foy-it-weld).
- Produces: with a `BeanManager`, the request listeners run in the order request context, session context, application listeners (destroyed in reverse); the deployment's session manager calls `CdiSessionScopeListener`.

- [ ] **Step 1: Write the Weld beans**

`SessionCart.java`:

```java
package io.vidocq.foy.it.weld;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Inject;
import java.io.Serializable;
import java.util.UUID;

/**
 * One instance per HTTP session. Serializable: {@code @SessionScoped} is a passivating scope, and
 * Weld refuses to deploy a non-passivation-capable bean in it (CDI 4.1 §17.5.5).
 */
@SessionScoped
public class SessionCart implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id = UUID.randomUUID().toString();

    @Inject
    SessionEvents events;

    @Inject
    RequestToken token;

    public String id() {
        return id;
    }

    /** Uses a request-scoped bean: Foy keeps a request context active while session beans are destroyed. */
    @PreDestroy
    void destroyed() {
        token.value();
        events.beanDestroyed(id);
    }
}
```

`SessionEvents.java`:

```java
package io.vidocq.foy.it.weld;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.event.Observes;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records the session context events (by session id) and the destroyed session beans (by bean id). */
@ApplicationScoped
public class SessionEvents {

    private final List<String> initialized = new CopyOnWriteArrayList<>();
    private final List<String> beforeDestroyed = new CopyOnWriteArrayList<>();
    private final List<String> destroyed = new CopyOnWriteArrayList<>();
    private final List<String> destroyedBeans = new CopyOnWriteArrayList<>();

    void onInitialized(@Observes @Initialized(SessionScoped.class) HttpSession session) {
        initialized.add(session.getId());
    }

    void onBeforeDestroyed(@Observes @BeforeDestroyed(SessionScoped.class) HttpSession session) {
        beforeDestroyed.add(session.getId());
    }

    void onDestroyed(@Observes @Destroyed(SessionScoped.class) HttpSession session) {
        destroyed.add(session.getId());
    }

    public void beanDestroyed(String beanId) {
        destroyedBeans.add(beanId);
    }

    public List<String> initialized() { return List.copyOf(initialized); }
    public List<String> beforeDestroyed() { return List.copyOf(beforeDestroyed); }
    public List<String> destroyed() { return List.copyOf(destroyed); }
    public List<String> destroyedBeans() { return List.copyOf(destroyedBeans); }
}
```

`SessionServlet.java`:

```java
package io.vidocq.foy.it.weld;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Uses a session-scoped bean: {@code /session/<op>}. */
@Dependent
@WebServlet(urlPatterns = "/session/*", asyncSupported = true)
public class SessionServlet extends HttpServlet {

    @Inject
    SessionCart cart;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("text/plain");
        String op = request.getPathInfo() == null ? "" : request.getPathInfo().substring(1);
        switch (op) {
            case "id" -> response.getWriter().write(cart.id() + "|" + request.getSession(false).getId());
            case "invalidate" -> {
                String id = cart.id();
                request.getSession(false).invalidate();
                response.getWriter().write(id + "|" + cart.id());
            }
            case "expire" -> {
                String id = cart.id();
                request.getSession(false).setMaxInactiveInterval(1);
                response.getWriter().write(id + "|" + request.getSession(false).getId());
            }
            case "same" -> response.getWriter().write(request.getAttribute(SessionRequestListener.ATTRIBUTE)
                    + "|" + request.getAttribute(SessionFilter.ATTRIBUTE) + "|" + cart.id());
            case "async" -> {
                AsyncContext async = request.startAsync();
                async.start(() -> {
                    String state;
                    try {
                        cart.id();
                        state = "active";
                    } catch (RuntimeException e) {
                        state = e.getClass().getSimpleName();
                    }
                    try {
                        async.getResponse().getWriter().write(state);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    } finally {
                        async.complete();
                    }
                });
            }
            default -> response.sendError(404);
        }
    }
}
```

`SessionFilter.java`:

```java
package io.vidocq.foy.it.weld;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Reads the session bean before the servlet. */
@Dependent
@WebFilter("/session/same")
public class SessionFilter extends HttpFilter {

    static final String ATTRIBUTE = "io.vidocq.foy.it.weld.session.filter";

    @Inject
    SessionCart cart;

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        request.setAttribute(ATTRIBUTE, cart.id());
        chain.doFilter(request, response);
    }
}
```

`SessionRequestListener.java`:

```java
package io.vidocq.foy.it.weld;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.http.HttpServletRequest;

/** Reads the session bean when {@code /session/same} starts: the session context is active in request listeners. */
@Dependent
@WebListener
public class SessionRequestListener implements ServletRequestListener {

    static final String ATTRIBUTE = "io.vidocq.foy.it.weld.session.listener";

    @Inject
    SessionCart cart;

    @Override
    public void requestInitialized(ServletRequestEvent event) {
        if (event.getServletRequest() instanceof HttpServletRequest request
                && request.getRequestURI().endsWith("/session/same")) {
            request.setAttribute(ATTRIBUTE, cart.id());
        }
    }
}
```

- [ ] **Step 2: Write the failing scenarios in `WeldPortabilityTest`**

Changes: keep the existing tests; add a static `mounted` field; close the deployment before Weld; add the helpers and tests below. New imports: `io.vidocq.foy.chappe.FoyChappeBoot.Mounted`, `jakarta.enterprise.context.ContextNotActiveException`, `jakarta.enterprise.context.SessionScoped`, `jakarta.enterprise.context.spi.Context`, `java.net.CookieManager`, `java.net.CookiePolicy`, `java.time.Duration`, `java.util.concurrent.TimeUnit`, `java.util.concurrent.locks.LockSupport`, `java.util.function.BooleanSupplier`, `static org.junit.jupiter.api.Assertions.assertFalse`.

```java
    private static Mounted mounted;

    @BeforeAll
    static void start() throws Exception {
        container = new Weld().initialize();
        mounted = FoyChappeBoot.builder()
                .beanManager(container.getBeanManager())
                .classLoader(WeldPortabilityTest.class.getClassLoader())
                .build()
                .orElseThrow();
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        server = Server.builder().host("127.0.0.1").port(port).handler(mounted.handler()).build();
        server.start();
        base = "http://127.0.0.1:" + port;
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop();
        }
        // Undeploy before Weld shuts down: the session beans of the live sessions are destroyed now.
        if (mounted != null) {
            mounted.close();
        }
        if (container != null) {
            container.close();
        }
    }

    // ---- session scope (foy#21) ----

    @Test
    void twoClientsGetTwoInstancesEachStableAcrossItsRequests() throws Exception {
        var alice = cookieClient();
        var bob = cookieClient();
        String alice1 = body(alice, "/session/id").split("\\|")[0];
        String alice2 = body(alice, "/session/id").split("\\|")[0];
        String bob1 = body(bob, "/session/id").split("\\|")[0];
        assertEquals(alice1, alice2, "one instance per session");
        assertNotEquals(alice1, bob1, "one instance per session, not per application");
    }

    @Test
    void invalidateGivesANewInstance() throws Exception {
        SessionEvents events = container.select(SessionEvents.class).get();
        var client = cookieClient();
        String before = body(client, "/session/id").split("\\|")[0];
        String[] invalidated = body(client, "/session/invalidate").split("\\|");
        assertEquals(before, invalidated[0]);
        assertEquals(before, invalidated[1], "the instance survives invalidate() until the end of the request");
        String after = body(client, "/session/id").split("\\|")[0];
        assertNotEquals(before, after);
        awaitTrue(() -> events.destroyedBeans().contains(before), "@PreDestroy of the invalidated session's bean");
    }

    @Test
    void sessionContextEventsFire() throws Exception {
        SessionEvents events = container.select(SessionEvents.class).get();
        var client = cookieClient();
        String sessionId = body(client, "/session/id").split("\\|")[1];
        assertTrue(events.initialized().contains(sessionId), "@Initialized(SessionScoped.class) with the HttpSession");
        body(client, "/session/invalidate");
        awaitTrue(() -> events.destroyed().contains(sessionId), "@Destroyed(SessionScoped.class)");
        assertTrue(events.beforeDestroyed().contains(sessionId), "@BeforeDestroyed(SessionScoped.class)");
    }

    @Test
    void expiryDestroysTheSessionBeansWithoutAnotherRequest() throws Exception {
        SessionEvents events = container.select(SessionEvents.class).get();
        mounted.deployment().sessionManager().restartReaper(Duration.ofMillis(100));
        String[] expiring = body(cookieClient(), "/session/expire").split("\\|");
        awaitTrue(() -> events.destroyed().contains(expiring[1]), "@Destroyed after expiry");
        assertTrue(events.destroyedBeans().contains(expiring[0]), "@PreDestroy ran, with a request context");
    }

    @Test
    void weldsOwnSessionContextStaysInactiveNextToFoys() throws Exception {
        var bm = container.getBeanManager();
        var contexts = bm.getContexts(SessionScoped.class);
        assertTrue(contexts.size() >= 2, "Weld's bound session context and Foy's: " + contexts);
        assertTrue(contexts.stream().noneMatch(Context::isActive), "no session context outside a request");
        assertThrows(ContextNotActiveException.class, () -> bm.getContext(SessionScoped.class));
        body(cookieClient(), "/session/id");   // no WELD-001304: a single active context during the request
    }

    @Test
    void filterListenerAndServletShareTheInstanceOfARequest() throws Exception {
        String[] ids = body(cookieClient(), "/session/same").split("\\|");
        assertEquals(ids[2], ids[0], "request listener");
        assertEquals(ids[2], ids[1], "filter");
    }

    @Test
    void asyncThreadHasNoSessionContext() throws Exception {
        // Documented gap BUG-20261010-07: AsyncContext.start threads are not bound, as for the request context.
        assertTrue(body(cookieClient(), "/session/async").contains("ContextNotActive"));
    }

    private static HttpClient cookieClient() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private static String body(HttpClient client, String path) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + ": " + response.body());
        return response.body();
    }

    /** Polls with a deadline: the end of a request (and its events) may follow the response. */
    private static void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) throw new AssertionError("timed out after 10 s waiting for " + what);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
    }
```

Update the class Javadoc: "... and the CDI request and session contexts are active for each request (foy#18, foy#21)."

- [ ] **Step 3: Run the Weld tests to verify the session scenarios fail**

Run: `./mvnw -ntp -pl foy-it-other-containers/foy-it-weld -am install -Dtest=WeldPortabilityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — the session scenarios answer 500 (`WELD-001303: No active contexts for scope type jakarta.enterprise.context.SessionScoped`: Foy's context is registered but never bound, since nothing wires the listener yet). `asyncThreadHasNoSessionContext` already passes; the foy#18 tests still pass.

- [ ] **Step 4: Wire the listener in `FoyChappeBoot`**

Import `io.vidocq.foy.internal.cdi.CdiSessionScopeListener;` and replace lines 290-293:

```java
            if (beanManager != null) {
                // The CDI session context (foy#21): bound for each request inside the request
                // context, and told by the session manager when sessions are created and destroyed.
                CdiSessionScopeListener sessionScope = new CdiSessionScopeListener(beanManager);
                deployment.listeners().addFirst(sessionScope);
                deployment.sessionManager().setLifecycleHook(sessionScope);
                // The CDI request context, active for each request under any container (foy#18).
                // Added last with addFirst, so it is the outermost listener: still active while
                // session beans are destroyed at the end of a request.
                deployment.listeners().addFirst(new CdiRequestScopeListener(beanManager));
            }
```

Javadoc of `ListenerRegistry.addFirst`, replace "Used for the CDI request context ({@link CdiRequestScopeListener})." with "Used for the CDI request and session contexts ({@link CdiRequestScopeListener}, {@code CdiSessionScopeListener}); the listener added last is the outermost."

In `foy-it-weld/pom.xml`, extend the `<description>`: "... and @RequestScoped beans live per request, @SessionScoped beans per HTTP session, over HTTP (vidocq-workspace#15, foy#21)."

- [ ] **Step 5: Run the Weld tests**

Run: `./mvnw -ntp -pl foy-it-other-containers/foy-it-weld -am install -Dtest=WeldPortabilityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 12 tests.

- [ ] **Step 6: Module gate and commit**

Run: `./mvnw -ntp -pl foy-core,foy-chappe,foy-it-other-containers/foy-it-weld -am install`
Expected: BUILD SUCCESS.

```bash
git add foy-chappe/src/main/java/io/vidocq/foy/chappe/FoyChappeBoot.java \
        foy-core/src/main/java/io/vidocq/foy/internal/listener/ListenerRegistry.java \
        foy-it-other-containers/foy-it-weld
git commit -S -s -F - <<'EOF'
feat(chappe): activate the CDI session context, proven on Weld SE

FoyChappeBoot registers CdiSessionScopeListener inside the request
context listener and as the session manager's lifecycle hook whenever a
BeanManager is given. foy-it-weld: one instance per session, a new one
after invalidate, the session context events, destruction on expiry with
a request context, Weld's own context inactive next to Foy's, one
instance across listener, filter and servlet, and the async-thread gap.

Refs: foy#21

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
EOF
```

---

### Task 6: Vauban — register the context from `FoyWebExtension`

**Files:**
- Modify: `foy-cdi-vauban/pom.xml` (dependency on foy-core)
- Modify: `foy-cdi-vauban/src/main/java/module-info.java` (`requires io.vidocq.foy.core;`)
- Modify: `foy-cdi-vauban/src/main/java/io/vidocq/foy/cdi/vauban/FoyWebExtension.java` (new `@Discovery` method, imports, class Javadoc bullet)
- Test: `foy-cdi-vauban/src/test/java/io/vidocq/foy/cdi/vauban/FoySessionScopeVaubanTest.java`
- Modify: `foy-cdi-vauban/src/test/java/io/vidocq/foy/cdi/vauban/FoyWebExtensionTest.java` (processed-build test)

**Interfaces:**
- Consumes: `io.vidocq.foy.internal.cdi.FoySessionContext` (Task 1), `SessionManager.restartReaper(Duration)` (Task 3), the wiring of Task 5, `VaubanContainer.builder().classLoader(..).addBeanClass(..).build()` (Vauban).
- Produces: `public void sessionContext(MetaAnnotations meta)` annotated `@Discovery` in `FoyWebExtension`, calling `meta.addContext(SessionScoped.class, FoySessionContext.class)` (1-argument overload only).

Context for the implementer: Vauban installs `MetaAnnotations.addContext` contexts only from the BCE discovery it runs at boot (`VaubanContainerBuilder.build`, `if (discoveryResult != null)`), and skips that discovery when every bean source carries the `META-INF/vauban-bce-processed` marker; `VaubanProcessor.applyDiscoveryResult` does not persist custom contexts at build time. So the runtime scenarios compile the test application **without** annotation processing and give Vauban the classes with `addBeanClass`: discovery then runs at boot. The processed-build case is pinned by Step 6.

- [ ] **Step 1: Write the failing tests**

`foy-cdi-vauban/src/test/java/io/vidocq/foy/cdi/vauban/FoySessionScopeVaubanTest.java`:

```java
/*
 * (license header)
 */
package io.vidocq.foy.cdi.vauban;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.chappe.FoyChappeBoot;
import io.vidocq.foy.internal.cdi.FoySessionContext;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.Proxy;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Foy's session context on Vauban (foy#21): {@link FoyWebExtension} registers it in
 * {@code @Discovery}; Vauban installs it when it runs that discovery at boot, which these tests
 * ensure by compiling the application without annotation processing.
 */
@DisplayName("FoySessionContext on Vauban: @SessionScoped beans per HTTP session")
class FoySessionScopeVaubanTest {

    @TempDir
    Path tempDir;

    private static final List<String> CLASSES = List.of("vsess.app.Cart", "vsess.app.Token", "vsess.app.Events",
            "vsess.app.SessionServlet", "vsess.app.SessionFilter", "vsess.app.SessionRequestListener");

    private static final String CART = """
            package vsess.app;

            import jakarta.annotation.PreDestroy;
            import jakarta.enterprise.context.SessionScoped;
            import jakarta.inject.Inject;

            @SessionScoped
            public class Cart implements java.io.Serializable {
                private final String id = java.util.UUID.randomUUID().toString();
                @Inject
                Events events;
                @Inject
                Token token;

                public String id() {
                    return id;
                }

                @PreDestroy
                public void destroyed() {
                    token.value();
                    events.record("bean " + id);
                }
            }
            """;

    private static final String TOKEN = """
            package vsess.app;

            @jakarta.enterprise.context.RequestScoped
            public class Token {
                private final String value = java.util.UUID.randomUUID().toString();

                public String value() {
                    return value;
                }
            }
            """;

    private static final String EVENTS = """
            package vsess.app;

            import jakarta.enterprise.context.BeforeDestroyed;
            import jakarta.enterprise.context.Destroyed;
            import jakarta.enterprise.context.Initialized;
            import jakarta.enterprise.context.SessionScoped;
            import jakarta.enterprise.event.Observes;
            import jakarta.servlet.http.HttpSession;

            @jakarta.enterprise.context.ApplicationScoped
            public class Events {
                private final java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();

                public void record(String line) {
                    lines.add(line);
                }

                public String dump() {
                    return String.join("\\n", lines);
                }

                public void initialized(@Observes @Initialized(SessionScoped.class) HttpSession s) {
                    lines.add("initialized " + s.getId());
                }

                public void beforeDestroyed(@Observes @BeforeDestroyed(SessionScoped.class) HttpSession s) {
                    lines.add("beforeDestroyed " + s.getId());
                }

                public void destroyed(@Observes @Destroyed(SessionScoped.class) HttpSession s) {
                    lines.add("destroyed " + s.getId());
                }
            }
            """;

    private static final String SERVLET = """
            package vsess.app;

            import jakarta.inject.Inject;
            import jakarta.servlet.http.HttpServletRequest;
            import jakarta.servlet.http.HttpServletResponse;

            @jakarta.enterprise.context.Dependent
            @jakarta.servlet.annotation.WebServlet("/session/*")
            public class SessionServlet extends jakarta.servlet.http.HttpServlet {
                @Inject
                Cart cart;
                @Inject
                Events events;

                @Override
                protected void doGet(HttpServletRequest q, HttpServletResponse r) throws java.io.IOException {
                    String op = q.getPathInfo() == null ? "" : q.getPathInfo().substring(1);
                    var out = r.getWriter();
                    switch (op) {
                        case "id" -> out.write(cart.id() + "|" + q.getSession(false).getId());
                        case "invalidate" -> {
                            String id = cart.id();
                            q.getSession(false).invalidate();
                            out.write(id + "|" + cart.id());
                        }
                        case "expire" -> {
                            String id = cart.id();
                            q.getSession(false).setMaxInactiveInterval(1);
                            out.write(id + "|" + q.getSession(false).getId());
                        }
                        case "same" -> out.write(q.getAttribute("listener") + "|" + q.getAttribute("filter") + "|" + cart.id());
                        case "probe" -> {
                            try {
                                out.write("active " + cart.id());
                            } catch (RuntimeException e) {
                                out.write(e.getClass().getName());
                            }
                        }
                        case "events" -> out.write(events.dump());
                        default -> r.sendError(404);
                    }
                }
            }
            """;

    private static final String FILTER = """
            package vsess.app;

            @jakarta.enterprise.context.Dependent
            @jakarta.servlet.annotation.WebFilter("/session/same")
            public class SessionFilter extends jakarta.servlet.http.HttpFilter {
                @jakarta.inject.Inject
                Cart cart;

                @Override
                protected void doFilter(jakarta.servlet.http.HttpServletRequest q, jakarta.servlet.http.HttpServletResponse r,
                        jakarta.servlet.FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
                    q.setAttribute("filter", cart.id());
                    chain.doFilter(q, r);
                }
            }
            """;

    private static final String LISTENER = """
            package vsess.app;

            @jakarta.enterprise.context.Dependent
            @jakarta.servlet.annotation.WebListener
            public class SessionRequestListener implements jakarta.servlet.ServletRequestListener {
                @jakarta.inject.Inject
                Cart cart;

                @Override
                public void requestInitialized(jakarta.servlet.ServletRequestEvent event) {
                    if (event.getServletRequest() instanceof jakarta.servlet.http.HttpServletRequest q
                            && q.getRequestURI().endsWith("/session/same")) {
                        q.setAttribute("listener", cart.id());
                    }
                }
            }
            """;

    // ---- the extension itself ----

    @Test
    @DisplayName("@Discovery registers FoySessionContext through the one-argument addContext overload")
    void discoveryRegistersTheContextWithTheOneArgumentOverload() {
        var calls = new ArrayList<List<Object>>();
        var meta = (MetaAnnotations) Proxy.newProxyInstance(MetaAnnotations.class.getClassLoader(),
                new Class<?>[] {MetaAnnotations.class}, (p, m, a) -> {
                    if (m.getName().equals("addContext")) {
                        calls.add(List.of(a));
                        return null;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        new FoyWebExtension().sessionContext(meta);
        assertEquals(List.of(List.<Object>of(SessionScoped.class, FoySessionContext.class)), calls);
    }

    // ---- end to end ----

    @Test
    @DisplayName("two clients get two instances, each stable across its requests")
    void twoClientsTwoInstances() throws Exception {
        try (var app = start(true)) {
            var alice = cookieClient();
            var bob = cookieClient();
            String alice1 = app.body(alice, "/session/id").split("\\|")[0];
            String alice2 = app.body(alice, "/session/id").split("\\|")[0];
            String bob1 = app.body(bob, "/session/id").split("\\|")[0];
            assertEquals(alice1, alice2);
            assertNotEquals(alice1, bob1);
        }
    }

    @Test
    @DisplayName("invalidate keeps the instance until the end of the request, then a new one comes")
    void invalidateGivesANewInstance() throws Exception {
        try (var app = start(true)) {
            var client = cookieClient();
            String before = app.body(client, "/session/id").split("\\|")[0];
            String[] invalidated = app.body(client, "/session/invalidate").split("\\|");
            assertEquals(before, invalidated[0]);
            assertEquals(before, invalidated[1]);
            assertNotEquals(before, app.body(client, "/session/id").split("\\|")[0]);
            app.awaitEvent("bean " + before);
        }
    }

    @Test
    @DisplayName("@Initialized, @BeforeDestroyed and @Destroyed(SessionScoped.class) fire with the HttpSession")
    void sessionEventsFire() throws Exception {
        try (var app = start(true)) {
            var client = cookieClient();
            String sessionId = app.body(client, "/session/id").split("\\|")[1];
            app.awaitEvent("initialized " + sessionId);
            app.body(client, "/session/invalidate");
            app.awaitEvent("destroyed " + sessionId);
            assertTrue(app.events().contains("beforeDestroyed " + sessionId));
        }
    }

    @Test
    @DisplayName("an expired session's beans are destroyed by the reaper, without another request")
    void expiryDestroysTheBeans() throws Exception {
        try (var app = start(true)) {
            app.mounted().deployment().sessionManager().restartReaper(Duration.ofMillis(100));
            String[] expiring = app.body(cookieClient(), "/session/expire").split("\\|");
            app.awaitEvent("destroyed " + expiring[1]);
            assertTrue(app.events().contains("bean " + expiring[0]));
        }
    }

    @Test
    @DisplayName("a request listener, a filter and the servlet share the instance of a request")
    void listenerFilterAndServletShareTheInstance() throws Exception {
        try (var app = start(true)) {
            String[] ids = app.body(cookieClient(), "/session/same").split("\\|");
            assertEquals(ids[2], ids[0]);
            assertEquals(ids[2], ids[1]);
        }
    }

    @Test
    @DisplayName("without FoyWebExtension, a session bean still fails with ContextNotActiveException, and nothing else breaks")
    void withoutTheContextASessionBeanFailsCleanly() throws Exception {
        try (var app = start(false)) {
            assertEquals("jakarta.enterprise.context.ContextNotActiveException",
                    app.body(cookieClient(), "/session/probe"));
        }
    }

    // ---- harness ----

    private record App(URLClassLoader loader, VaubanContainer container, Server server,
                       FoyChappeBoot.Mounted mounted, String base) implements AutoCloseable {

        String body(HttpClient client, String path) throws Exception {
            var response = client.send(HttpRequest.newBuilder(URI.create(base + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), path + ": " + response.body());
            return response.body();
        }

        List<String> events() throws Exception {
            return body(HttpClient.newHttpClient(), "/session/events").lines().toList();
        }

        void awaitEvent(String line) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!events().contains(line)) {
                if (System.nanoTime() - deadline > 0) throw new AssertionError("timed out waiting for '" + line + "': " + events());
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            }
        }

        @Override
        public void close() throws Exception {
            server.stop();
            mounted.close();   // before the container: the live sessions' beans are destroyed now
            container.close();
            loader.close();
        }
    }

    private App start(boolean withFoyExtension) throws Exception {
        URLClassLoader loader = compile(CART, TOKEN, EVENTS, SERVLET, FILTER, LISTENER);
        VaubanContainer container = withTccl(loader, () -> {
            var builder = VaubanContainer.builder().classLoader(loader);
            if (withFoyExtension) builder.addBeanClass(FoyWebExtension.class);
            for (String name : CLASSES) builder.addBeanClass(loader.loadClass(name));
            return builder.build();
        });
        var mounted = withTccl(loader, () -> FoyChappeBoot.builder()
                .beanManager(container.getBeanManager()).classLoader(loader).contextPath("/").build().orElseThrow());
        int port;
        try (var s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Server server = Server.builder().host("127.0.0.1").port(port).handler(mounted.handler()).build();
        server.start();
        return new App(loader, container, server, mounted, "http://127.0.0.1:" + port);
    }

    private static HttpClient cookieClient() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private static <T> T withTccl(ClassLoader loader, Callable<T> action) throws Exception {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return action.call();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** Compiles without annotation processing: no vauban-bce-processed marker, so Vauban runs the BCEs at boot. */
    private URLClassLoader compile(String... sources) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var root = Files.createTempDirectory(tempDir, "app");
        var classes = Files.createDirectories(root.resolve("classes"));
        var files = new ArrayList<File>();
        for (var source : sources) {
            var pkg = match(source, "package\\s+([\\w.]+)\\s*;");
            var name = match(source, "public\\s+(?:class|interface|enum|record)\\s+(\\w+)");
            var file = root.resolve("src").resolve(pkg.replace('.', '/')).resolve(name + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
            files.add(file.toFile());
        }
        try (var fm = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
            fm.setLocation(StandardLocation.CLASS_PATH, testPath());
            boolean ok = compiler.getTask(null, fm, diagnostics, List.of("--release", "25", "-proc:none"), null,
                    fm.getJavaFileObjectsFromFiles(files)).call();
            assertTrue(ok, () -> diagnostics.getDiagnostics().toString());
        }
        return new URLClassLoader(new URL[] {classes.toUri().toURL()}, FoySessionScopeVaubanTest.class.getClassLoader());
    }

    private static String match(String source, String regex) {
        var m = Pattern.compile(regex).matcher(source);
        if (!m.find()) throw new IllegalArgumentException("no match for " + regex);
        return m.group(1);
    }

    /** This test run's class path and module path, which hold the Jakarta APIs the application compiles against. */
    private static List<File> testPath() {
        Set<File> path = new LinkedHashSet<>();
        for (var property : List.of("jdk.module.path", "java.class.path")) {
            for (var e : System.getProperty(property, "").split(File.pathSeparator)) {
                if (!e.isBlank()) path.add(new File(e));
            }
        }
        ModuleLayer.boot().configuration().modules().forEach(rm -> rm.reference().location().ifPresent(uri -> {
            if ("file".equals(uri.getScheme())) path.add(new File(uri));
        }));
        return List.copyOf(path);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -ntp -pl foy-cdi-vauban -am test -Dtest=FoySessionScopeVaubanTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION FAILURE (`FoyWebExtension.sessionContext` does not exist; `io.vidocq.foy.internal.cdi` not readable by `io.vidocq.foy.cdi.vauban`).

- [ ] **Step 3: Depend on foy-core and register the context**

`foy-cdi-vauban/pom.xml`, after the `foy-api` dependency (version from `foy-parent`'s `dependencyManagement`):

```xml
        <!-- FoySessionContext (foy#21), named by FoyWebExtension's @Discovery. -->
        <dependency>
            <groupId>io.vidocq.foy</groupId>
            <artifactId>foy-core</artifactId>
        </dependency>
```

`foy-cdi-vauban/src/main/java/module-info.java`, after `requires transitive io.vidocq.foy.api;`:

```java
    requires io.vidocq.foy.core;
```

`FoyWebExtension.java`: imports `io.vidocq.foy.internal.cdi.FoySessionContext`, `jakarta.enterprise.inject.build.compatible.spi.Discovery`, `jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations`; add to the class Javadoc list:

```java
 *   <li>{@link #sessionContext} gives Vauban Foy's context for {@code @SessionScoped} (foy#21):
 *       Vauban has no session context of its own, and no portable extension support for foy-core's
 *       {@code FoySessionScopeExtension}.</li>
```

and the method, before `defaultScope`:

```java
    /**
     * Registers Foy's session context. The one-argument overload reads {@code @NormalScope} from
     * {@code SessionScoped}; the boolean overload would declare the scope non-passivating (on Weld,
     * through its lite extension translator). This method runs only on Vauban: on CDI Full
     * containers foy-core's portable extension registers the context, and a build compatible
     * extension may not define a context for a built-in scope of CDI Full (CDI 4.1 §6.7).
     *
     * @param meta the meta-annotations of the deployment
     */
    @Discovery
    public void sessionContext(MetaAnnotations meta) {
        meta.addContext(SessionScoped.class, FoySessionContext.class);
    }
```

- [ ] **Step 4: Run the Vauban tests**

Run: `./mvnw -ntp -pl foy-cdi-vauban -am test -Dtest='FoySessionScopeVaubanTest,FoyWebExtensionTest,FoyWebExtensionScopeTest,CdiWebComponentsCreatorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. `FoyWebExtensionTest`'s processed builds still compile: vauban-processor runs the new `@Discovery` at build time, which needs `FoySessionContext` on the processor path (foy-core is now a compile dependency, so it is on the test path both tests use, class path and `--processor-module-path`).

- [ ] **Step 5: Pin the processed-build behaviour**

Add to `FoyWebExtensionTest` (imports `org.junit.jupiter.api.Disabled`, `java.net.CookieManager`, `java.net.CookiePolicy`), after `twoArchivesAreServedTogether`:

```java
    @Test
    @Disabled("Vauban drops MetaAnnotations.addContext contexts when every bean source is pre-processed "
            + "(BUG-20261010-08; upstream issue drafted in docs/superpowers/plans/2026-10-10-cdi-session-context.md, Appendix A)")
    @DisplayName("end to end, processed build: a @SessionScoped bean is one instance per session")
    void sessionScopedBeanThroughAProcessedBuild() throws Exception {
        var result = compile(ProcessorPath.CLASS_PATH, """
                package psess.app;

                @jakarta.enterprise.context.SessionScoped
                public class Cart implements java.io.Serializable {
                    private final String id = java.util.UUID.randomUUID().toString();

                    public String id() {
                        return id;
                    }
                }
                """, """
                package psess.app;

                @jakarta.servlet.annotation.WebServlet("/cart")
                public class CartServlet extends jakarta.servlet.http.HttpServlet {
                    @jakarta.inject.Inject
                    Cart cart;

                    @Override
                    protected void doGet(jakarta.servlet.http.HttpServletRequest q,
                            jakarta.servlet.http.HttpServletResponse r) throws java.io.IOException {
                        r.getWriter().write(cart.id());
                    }
                }
                """);
        assertTrue(result.success(), result::messages);

        try (var loader = result.loader();
             var container = withTccl(loader, () -> boot(loader))) {
            var mounted = withTccl(loader, () -> FoyChappeBoot.builder()
                    .beanManager(container.getBeanManager()).classLoader(loader).contextPath("/").build().orElseThrow());
            int port;
            try (var s = new ServerSocket(0)) {
                port = s.getLocalPort();
            }
            Server server = Server.builder().host("127.0.0.1").port(port).handler(mounted.handler()).build();
            server.start();
            try {
                var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
                var uri = URI.create("http://127.0.0.1:" + port + "/cart");
                var first = client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
                var second = client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, first.statusCode(), first::body);
                assertEquals(first.body(), second.body());
            } finally {
                server.stop();
                mounted.close();
            }
        }
    }
```

Before adding `@Disabled`, run it once enabled:

Run: `./mvnw -ntp -pl foy-cdi-vauban -am test -Dtest='FoyWebExtensionTest#sessionScopedBeanThroughAProcessedBuild' -Dsurefire.failIfNoSpecifiedTests=false`
Expected today: FAIL, status 500 whose body or log names `ContextNotActiveException` ("No context registered for scope jakarta.enterprise.context.SessionScoped"). Then add the `@Disabled` shown above. If it PASSES instead, keep it enabled, do not create BUG-20261010-08 in Task 7, and drop the "processed build" limitation from the Task 7 docs text.

- [ ] **Step 6: Module gate and commit**

Run: `./mvnw -ntp -pl foy-cdi-vauban -am install`
Expected: BUILD SUCCESS (one skipped test if Step 5 confirmed the limitation).

```bash
git add foy-cdi-vauban
git commit -S -s -F - <<'EOF'
feat(cdi-vauban): register Foy's session context on Vauban

FoyWebExtension registers FoySessionContext for @SessionScoped in
@Discovery through the one-argument MetaAnnotations.addContext overload;
foy-cdi-vauban now depends on foy-core. Vauban end-to-end tests cover one
instance per session, invalidate, the session events, expiry, sharing
across listener/filter/servlet, and a clean ContextNotActiveException
without the extension. A fully processed build is pinned as a known
Vauban limitation (disabled test).

Refs: foy#21

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
EOF
```

---

### Task 7: Documentation, bug records, final verification

**Files:**
- Modify: `docs/en/modules/ROOT/pages/reference.adoc:403-413` (`#other-containers`)
- Modify: `docs/en/modules/ROOT/pages/usage.adoc:401`
- Modify: `docs/en/modules/ROOT/pages/concepts.adoc:89`
- Modify: `docs/en/modules/ROOT/pages/whats-new.adoc:2,9-10`
- Modify: `BUG.md` (BUG-20261010-01 remark; new BUG-20261010-07; BUG-20261010-08 only if Task 6 Step 5 confirmed it)

**Interfaces:**
- Consumes: behaviour of Tasks 1-6; Task 6 Step 5 outcome.
- Produces: user documentation and bug records; final verification evidence.

- [ ] **Step 1: `reference.adoc#other-containers`**

Replace the bullet starting `* **No session context yet**` with:

```asciidoc
* [.tag-new]#NEW# **The session context is active for each request** — Foy registers its own context for `@SessionScoped` (foy#21): on CDI Full containers such as Weld through a portable extension of foy-core (`FoySessionScopeExtension`, found through `META-INF/services` or the module's `provides`), on Vauban through `foy-cdi-vauban`'s build compatible extension. The context is active in servlets, filters, request listeners and `HttpSessionListener`s, one instance per HTTP session. A session bean creates the session on first use; a mere lookup never does. After `invalidate()`, the request keeps the old instances until it ends, then they are destroyed; the beans of an expired session are destroyed after the application's `sessionDestroyed` listeners, by the reaper or at undeploy, with a request context active. Foy fires `@Initialized`, `@BeforeDestroyed` and `@Destroyed(SessionScoped.class)` with the `HttpSession` as payload. If another session context is already active when a request starts (an embedding runtime), Foy leaves it alone.
* **Session context limits** —
** Threads started with `AsyncContext.start()` have no session context, as they have no request context (BUG-20261010-07).
** On Weld, `@SessionScoped` beans must be `Serializable`: the scope is passivating, and Weld refuses to deploy a bean that is not passivation capable.
** On Vauban, the context is installed only when Vauban runs the build compatible extensions at boot. In an application whose every bean archive was processed by vauban-processor at build time, Vauban drops it and `@SessionScoped` beans fail with `ContextNotActiveException` (BUG-20261010-08, a Vauban issue).
** Vauban's client proxies call the first context registered for a scope; Foy's is the only session context on Vauban, so this is harmless today.
** A CDI Lite container other than Vauban, without `foy-cdi-vauban`, has no session context: `@SessionScoped` beans fail with `ContextNotActiveException`, as before.
** Foy sessions live in memory and are never serialized: session beans are not passivated.
```

Replace the paragraph starting `` `foy-it-weld`, part of every build`` with:

```asciidoc
`foy-it-weld`, part of every build and never published, runs Foy on Chappe with Weld SE 6.0 (CDI 4.1) on a class path, without `foy-cdi-vauban`: a servlet, a filter and a listener get an injected bean, a `@RequestScoped` bean is one instance per request, a `@SessionScoped` bean one instance per HTTP session (a new one after `invalidate()`, destroyed on expiry), and the request and session context events fire. No Open Liberty module: on a server, use the server's own Servlet container.
```

(If Task 6 Step 5 passed, delete the "On Vauban, the context is installed only when..." sub-bullet.)

- [ ] **Step 2: `usage.adoc:401`, `concepts.adoc:89`, `whats-new.adoc`**

`usage.adoc` line 401 becomes:

```asciidoc
[.tag-new]#NEW# Your own `@RequestScoped` and `@SessionScoped` beans do work: Foy activates the CDI request context for each request and its own session context, one instance per HTTP session, under Vauban or any other CDI container. On Weld, make session beans `Serializable`; threads started with `AsyncContext.start()` have neither context (xref:reference.adoc#other-containers[Other CDI containers]).
```

`concepts.adoc` line 89 becomes:

```asciidoc
* [.tag-new]#NEW# The CDI request context is active for each request, and Foy's session context gives `@SessionScoped` beans one instance per HTTP session, under any container (xref:reference.adoc#other-containers[Other CDI containers]).
```

`whats-new.adoc`: `:description:` becomes `Everything that changed in Foy since the {release-version} release — the CDI request and session contexts active for each request, and Foy proven on Weld SE.`; in the first bullet, replace `The session context is not activated.` with `The session context followed (next entry).`; add after it:

```asciidoc
* **The session context for `@SessionScoped` beans** — Foy now provides its own context for `@SessionScoped` (foy#21): one instance per HTTP session, the old instances kept until the end of a request that calls `invalidate()`, destruction on expiry and undeploy, and the `@Initialized` / `@BeforeDestroyed` / `@Destroyed(SessionScoped.class)` events. Registered by a portable extension on Weld and other CDI Full containers, and by `foy-cdi-vauban` on Vauban. {release-version} had no session context at all: a `@SessionScoped` bean failed with `ContextNotActiveException`. xref:reference.adoc#other-containers[Other CDI containers].
```

and extend the "Proven on Weld SE" bullet: "... request-scoped beans live per request, session-scoped beans per HTTP session (foy#18, foy#21)."

- [ ] **Step 3: `BUG.md`**

In BUG-20261010-01, replace the last sentence of **Correction** (`The session context is still not activated (no portable API).`) with:

```markdown
  The session context, out of scope here, was added by foy#21 (`FoySessionContext`, `CdiSessionScopeListener`): resolved.
```

Append after BUG-20261010-06:

```markdown
## BUG-20261010-07 — the CDI session context is not active on AsyncContext.start threads

- **Date** : 2026-10-10
- **Statut** : OPEN (documented gap, parity with the request context of BUG-20261010-01)
- **Module touché** : `foy-core` (`CdiSessionScopeListener`, `AsyncContextImpl`)
- **Symptôme** : a `@SessionScoped` bean used from the `Runnable` given to `AsyncContext.start()` fails
  with `ContextNotActiveException`. The `SessionScoped` Javadoc requires the session context to be active
  when the container calls an `AsyncListener`, and the request context has the same gap.
- **Reproduction minimale** : `foy-it-weld`, `WeldPortabilityTest.asyncThreadHasNoSessionContext` (pins the
  gap: the async thread answers `ContextNotActiveException`).
- **Hypothèse de cause** : the binding is a `ThreadLocal` set by `CdiSessionScopeListener` on the request
  thread; Foy starts asynchronous work on new virtual threads and propagates neither the session nor the
  request context to them.
- **Piste** : bind both contexts around the `Runnable` in `AsyncContextImpl.start` and around the
  `AsyncListener` callbacks fired off the request thread, with the request's session source.
```

Only if Task 6 Step 5 failed as expected, append:

```markdown
## BUG-20261010-08 — @SessionScoped fails on Vauban when every bean archive was processed at build time

- **Date** : 2026-10-10
- **Statut** : OPEN (upstream Vauban; issue text in `docs/superpowers/plans/2026-10-10-cdi-session-context.md`, Appendix A)
- **Module touché** : `foy-cdi-vauban` (symptom); cause in `vauban-core` / `vauban-processor`
- **Symptôme** : with `foy-cdi-vauban` on the annotation processor path and every bean archive processed, a
  `@SessionScoped` bean fails with `ContextNotActiveException` ("No context registered for scope
  jakarta.enterprise.context.SessionScoped"); the same application compiled without processing works.
- **Reproduction minimale** : `FoyWebExtensionTest.sessionScopedBeanThroughAProcessedBuild` (disabled).
- **Hypothèse de cause** : `VaubanContainerBuilder.build` skips the BCE discovery when every source carries
  `META-INF/vauban-bce-processed` (`allSourcesProcessed`, `VaubanContainerBuilder.java:546-556`) and installs
  `MetaAnnotations.addContext` contexts only from that discovery (`:916`); `VaubanProcessor.applyDiscoveryResult`
  does not persist `getCustomContexts()` at build time. Mansart's `@TransactionScoped` is exposed the same way.
```

- [ ] **Step 4: Full reactor and harness**

Run: `./mvnw -ntp install`
Expected: BUILD SUCCESS, every module.

Run: `./mvnw -ntp -Ptck -pl foy-tck test`
Expected: BUILD SUCCESS (harness tests).

- [ ] **Step 5: Official Servlet TCK, per-class comparison with the baseline**

The listener order changes for every request, so the full suite runs:

```bash
./run-official-tck-servlet6.1.sh --all 2>&1 | tee "$TMPDIR/foy-tck-all.log"
foy-tck/tck-tally.sh "$TMPDIR/foy-tck-all.log" > "$TMPDIR/tck-new.txt"
LC_ALL=C join foy-tck/tck-baseline.txt "$TMPDIR/tck-new.txt" \
  | awk '$5 != $2 || $6 > $3 || $7 > $4 || $5-$6-$7 < $2-$3-$4 {print "REGRESSION", $0}'
LC_ALL=C join -v1 foy-tck/tck-baseline.txt "$TMPDIR/tck-new.txt"
LC_ALL=C join -v2 foy-tck/tck-baseline.txt "$TMPDIR/tck-new.txt"
awk '{r+=$2; b+=$3; s+=$4} END {print r - b - s " passing / " r " run, " s " skipped"}' "$TMPDIR/tck-new.txt"
```

Expected: no `REGRESSION` line, both `join -v` empty, the passing total equal to the baseline's. Any regression is a stop: record it in `BUG.md`, fix it, rerun.

- [ ] **Step 6: Commit**

```bash
git add docs/en/modules/ROOT/pages BUG.md
git commit -S -s -F - <<'EOF'
docs: document the CDI session context and its limits

Other CDI containers: the session context, its lifecycle and events, and
its limits (async threads, Serializable on Weld, Vauban processed
builds). BUG-20261010-01's session remark is resolved; BUG-20261010-07
records the async-thread gap.

Refs: foy#21

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
EOF
```

---

## Appendix A: upstream Vauban issues (drafts, not filed by this plan)

### A.1 Custom contexts registered with `MetaAnnotations.addContext` are dropped when every bean source is pre-processed

**Summary.** A build compatible extension that registers a context in `@Discovery` (`MetaAnnotations.addContext`) works when Vauban runs the BCE lifecycle at boot, and silently loses the context when every bean archive was processed by vauban-processor at build time. Beans of that scope then fail with `ContextNotActiveException("No context registered for scope ...")`.

**Where.**
- `vauban-core/.../container/VaubanContainerBuilder.java:546-556`: `allSourcesProcessed = unprocessedArchiveClasses.isEmpty() && !bceProcessedSources.isEmpty()` empties `bceClasses`, so `discoveryResult` stays `null`.
- `VaubanContainerBuilder.java:916-940`: custom contexts are installed only `if (discoveryResult != null)`.
- `vauban-processor/.../VaubanProcessor.java` `applyDiscoveryResult` (`:1227`): applies custom qualifiers, interceptor bindings, stereotypes and non-binding members, but not `metaAnnotations().getCustomContexts()`; nothing writes them to the build metadata.

**Reproduction.** Foy's `FoyWebExtensionTest.sessionScopedBeanThroughAProcessedBuild` (foy#21): an application with a `@SessionScoped` bean, compiled with vauban-processor and foy-cdi-vauban on the processor path, booted with `VaubanContainer.builder().classLoader(loader).scanClasspath().build()`. The same classes given to `addBeanClass` without processing work. Mansart's `@TransactionScoped` (`MansartTransactionsExtension`) is exposed the same way.

**Expected.** The contexts an extension registers in `@Discovery` are installed whatever path the container takes at boot.

**Proposal.** Record the custom contexts at build time (for example `META-INF/vauban-custom-contexts.list`, one `scope-annotation-FQN=context-class-FQN[,normal]` per line) and install them at boot when discovery is skipped, through the existing instantiation order (component provider, public no-arg constructor, private lookup); or always run the `@Discovery` phase of the BCEs found on the class path, which is cheap.

### A.2 Client proxies use the first registered context of a scope, ignoring `isActive()`

**Summary.** CDI 4.1 §6.5.1 resolves a scope to its single *active* context and requires `IllegalStateException` when more than one is active. Vauban's normal-scope client proxies call `VaubanContainer.getFirstContext(scope)`, the first *registered* context whether active or not (`InterceptorBeanWrapper.java:248-262, 324-338`; `VaubanContainer.java:539-542`), and `VaubanBeanManager.getContext` (`:361-372`) returns the first active context without detecting a second active one.

**Impact today.** Harmless while a scope has one context (Foy's session context is the only `@SessionScoped` context on Vauban; it throws `ContextNotActiveException` itself when inactive). It breaks as soon as two contexts are registered for one scope and the first one is inactive.

**Proposal.** Resolve through the same active-context lookup as `getContext`, and throw `IllegalStateException` when two contexts of the scope are active.
