# Spike: vauban build-time build compatible extension capabilities (Task 2.9)

Temporary findings file, deleted at the end of Task 2.10.

- Date: 2026-10-08
- Test: `src/test/java/io/vidocq/foy/cdi/vauban/BceCapabilitySpikeTest.java`
- Command: `./mvnw -ntp -pl foy-cdi-vauban clean test -Dtest=BceCapabilitySpikeTest` (3/3 green)
- vauban: `0.4.0-SNAPSHOT` from the local repository (installed 2026-10-08 13:53, vauban checkout on `feat/dependency-providers` at `7384ac10`).

## Harness

Modelled on `vauban/vauban-processor/src/test/java/io/vidocq/vauban/processor/apt/BceCompileTimeTest.java`:
each test compiles a tiny app (`spike.app.SpikeServlet`: `@WebServlet("/spike")`, extends
`HttpServlet`, **no** scope) in-process with `javax.tools`, `-proc:full`, and a
`VaubanProcessor` whose public `overrideBceClasses` field injects the test extension (no
ServiceLoader). A container is then booted from the compiled output with
`VaubanContainer.builder().classLoader(loader).scanClasspath().build()` (the loader is a
`URLClassLoader` over the output directory; `scanClasspath` reads `vauban-beans.list`, the BCE
marker and the synthetic metadata). Booting from compiled output works: every runtime assertion
below comes from a live `BeanManager`.

Build plumbing: the test module needs `java.compiler`, which the main module does not read, so
`default-testCompile` gets `--add-modules java.compiler --add-reads io.vidocq.foy.cdi.vauban=java.compiler`
and surefire runs with `useModulePath=false` (same precedent as `foy-chappe`). New test-scope
dependency: `io.vidocq.vauban:vauban-processor`.

## Q1 — `@Enhancement(withAnnotations = WebServlet.class)` on an unscoped class: **YES**

Test: `enhancementPromotesUnscopedWebServlet`.

Observed: with an extension `@Enhancement(types = Object.class, withAnnotations = WebServlet.class)`
calling `clazz.addAnnotation(Dependent.class)`, `SpikeServlet` (no bean-defining annotation) lands
in `META-INF/vauban-beans.list`, gets a generated `SpikeServlet_Factory.class`, and the booted
container's `bm.getBeans(SpikeServlet.class)` returns exactly one `@Dependent` bean.

Why it works: `VaubanProcessor.init` reads every extension's `@Enhancement.withAnnotations()`
(`VaubanProcessor.extractBceAnnotationTypes`, ~line 195) and adds them to
`getSupportedAnnotationTypes()`, so the round environment hands the `@WebServlet` classes to the
index even without a CDI annotation.

Caveat: the trigger is read with `getDeclaredMethods()` — the `@Enhancement` method must be declared
on the extension class itself, not inherited. And the extension must be visible to the processor at
build time (on the processor path, via ServiceLoader) — the spike injects it directly.

**Decision:** the BCE can discover unscoped `@WebServlet` / `@WebFilter` / `@WebListener` classes
itself and make them `@Dependent` beans. Task 2.10 may rely on it; foy-processor (Task 2.2) remains
the source of the `WebComponent` metadata, not the only discovery path for CDI.

## Q2 — `@Synthesis` + `withParam("classes", Class<?>[])` at build time: **PARTIAL — synthesis yes, `Class<?>[]` param no**

Test: `synthesisWithClassArrayParam`.

Observed:
- The synthetic `CdiWebComponents` bean (`@Singleton`, `createWith(ComponentsCreator.class)`) is
  serialised to `META-INF/vauban-synthetic-metadata.properties` (`bean.count=1`, creator FQCN) and
  is resolvable at runtime: `bm.getBeans(CdiWebComponents.class).size() == 1`, the creator runs.
- The `Class<?>[]` param is **silently dropped**: no `bean.0.param.classes` line in the metadata, and
  at runtime `params.get("classes", Class[].class)` returns `null`.
- A `String` param survives: `withParam("classNames", "java.lang.String,java.lang.Integer")` is read
  back intact by the creator.

Cause (vauban sources):
- `vauban-core/.../extensions/SyntheticMetadataSerializer.encodeParam` (~line 149-158) encodes only
  `String`, `Boolean`, `Integer`, `Long`, `Double`, `Class`, `Enum`; every array (and `ClassInfo`,
  `AnnotationInfo`, `Annotation`, `InvokerInfo`) returns `null` → "unsupported param type — skipped".
- `vauban-core/.../container/SyntheticComponentRegistrar.applyParam` (~line 155-165) re-applies only
  `String`/`Boolean`/`Integer`/`Long`/`Double`; `decodeParam` turns `C:` (single `Class`) and `E:`
  into plain strings, so even a single `Class<?>` param reaches the creator as a `String`
  (not exercised by the test — read from source).
- `VaubanSyntheticBeanBuilder.withParam(String, Class<?>[])` itself accepts the value (it is just put
  in the map); the loss happens only across the build-time → runtime serialisation, so the same BCE
  run at runtime (no processor) would see the array.

Additional build-time limit: in the processor, the classes of the compilation being processed are
not loadable as `Class<?>` (the BCE sees `ClassInfo`), so an array of application classes could not
be built from enhancement results anyway; `ClassInfo[]` is not serialised either.

**Decision:** keep the `CdiWebComponents` synthetic bean, but carry the component classes as a
**`String` param holding the comma-joined binary class names**; the creator resolves them at
runtime (`Class.forName(name, false, <loader>)`, loader to be chosen in Task 2.10 — the creator's or
the TCCL). This is a deviation from the brief's "Q2 no → drop the synthetic bean" branch, because
the synthetic bean itself works; only the param type is unsupported. If the maintainer prefers the
brief's fallback, `WebAppDiscovery` filters the ServiceLoader `WebComponent` set by
`bm.getBeans(type)` non-empty instead.

vauban issue to open (not opened by the spike — draft):

> **Build-time synthetic bean params: arrays, `Class`, `ClassInfo`, `Enum`, annotations are dropped.**
> `SyntheticMetadataSerializer.encodeParam` returns `null` for any array and for
> `ClassInfo`/`AnnotationInfo`/`Annotation`/`InvokerInfo`; `SyntheticComponentRegistrar.applyParam`
> ignores `Class`/`Enum` (decoded as strings). A BCE that calls
> `SyntheticBeanBuilder.withParam(String, Class<?>[])` at build time sees `Parameters.get(key,
> Class[].class) == null` in its creator, with no warning. CDI 4.1 Lite requires all `withParam`
> overloads to round-trip. Repro: foy `BceCapabilitySpikeTest.synthesisWithClassArrayParam`.
> Expected: encode arrays and class-like values (by binary name) and resolve them at runtime; at
> minimum, report an unsupported param as a compilation error rather than skip it.

## Q3 — `@Validation` + `Messages.error(..)` fails the compilation: **YES (when the processor indexed at least one class)**

Test: `validationErrorFailsCompilation`.

Observed: an extension whose `@Validation` method calls `messages.error("spike: web component
rejected")` makes `javac` fail with `ERROR: [Vauban BCE] spike: web component rejected`
(`VaubanProcessor.process`, ~line 305-316: definition and deployment errors are printed as
`Diagnostic.Kind.ERROR`).

Pitfall (second compilation in the same test): with an extension that has **only** a `@Validation`
method (no `@Enhancement` trigger) and an app carrying no CDI annotation, the compilation
**succeeds** and no error is printed — `VaubanProcessor.process` returns before running any
extension phase when its index is empty (`if (index.size() == 0) return false;`, ~line 248). The
same applies to `@Synthesis` (observed while writing Q2: no metadata file until the extension
declared the `@WebServlet` trigger).

**Decision:** Foy's BCE may report validation errors through `Messages.error` at build time, as long
as it declares the `@WebServlet`/`@WebFilter`/`@WebListener` enhancement triggers (it will, for Q1).
Validation that must also run for apps with no web component annotation stays in `foy-processor`.
