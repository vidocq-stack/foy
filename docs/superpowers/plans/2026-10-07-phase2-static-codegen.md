# Phase 2 — Static Code Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** web-component metadata and instantiation come from code generated
at build time (APT, plus a CDI BuildCompatibleExtension for CDI-managed
components), with the Class-File API as the runtime degraded mode and
reflection only as the last resort.

**Architecture:** a `WebComponent` SPI in foy-api describes one class
(descriptor literal + factory). `foy-processor` (APT) generates
`X$$FoyComponent` for every concrete Servlet/Filter/listener/SCI class it
compiles, plus a class index for `@HandlesTypes`. At runtime,
`WebComponentRegistry` resolves a class through four tiers: ServiceLoader →
`Class.forName(X$$FoyComponent)` → Class-File tier (annotations decoded from
the class bytes, factory = hidden class emitted with `java.lang.classfile`)
→ reflection (one WARNING). `FoyWebExtension` (BCE, run by
`vauban-processor` at build time) gives CDI-managed components a scope,
validates them at build time, and synthesises a `CdiWebComponents` bean so
discovery never walks the bean set. Mirrors cassini (`CassiniResourceProcessor`,
`AdapterRegistry`, `RuntimeAdapterGenerator`) and vauban (`VaubanProcessor` +
`BceProcessor`).

**Tech Stack:** Java 25 (`javax.annotation.processing`, `java.lang.classfile`,
`MethodHandles.Lookup.defineHiddenClass`), Jakarta Servlet 6.1, Jakarta CDI
4.1 Lite BCE API, vauban 0.4.0-SNAPSHOT (`vauban-processor`), JUnit 5,
`javax.tools` compile harness (no compile-testing dependency, as in cassini/vauban).

**Spec:** `docs/superpowers/plans/2026-10-07-servlet-completion-roadmap.md`
("Code-generation doctrine" + Phase 2). Depends on Phase 1
(`WebAppModel`, `ComponentFactory`, `HandlesTypesResolver`, `DeployOptions`,
`WebAppDiscovery.discover` returning `AnnotatedComponents`).

## Global Constraints

- Runtime modules (foy-api, foy-core, foy-chappe, foy-cdi-vauban) keep zero
  dependency beyond Jakarta APIs + chappe/vauban. `foy-processor` is a
  build-time module: it may depend only on `java.compiler` (no third party).
- Generated sources must compile with **no** extra `requires` in the user's
  module beyond `requires io.vidocq.foy.api` (no `@Generated`, which lives in
  `java.compiler`).
- Java Modules: `foy-api` exports `io.vidocq.foy.spi.gen` and
  `io.vidocq.foy.spi.cdi`; foy-core `uses io.vidocq.foy.spi.gen.WebComponent`.
  Say "Java Modules", never "JPMS".
- Tier logging: Class-File tier → one INFO line per class; reflective tier →
  one WARNING line per class naming the reason. Never per request.
- English everywhere; Conventional Commits, `git commit -S -s`, trailer
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Gate:** TCK ≥ the Phase 1 exit figure, no class regression
  (`foy-tck/tck-tally.sh` + `foy-tck/tck-baseline.txt`), and in the TCK run
  `WebComponentRegistry` stats show `reflective == 0` (TCK wars are not
  APT-processed, so they must be served by the Class-File tier).

## Review Focus

1. **Nested and non-public classes**: a `public static` nested servlet
   (`Outer.Inner`) gets `Outer$Inner$$FoyComponent`; a private constructor or
   a non-static inner class gets **no** generated class and a compiler NOTE,
   then resolves through the Class-File/reflection tiers (Task 2.2/2.3 tests).
2. **`value` vs `urlPatterns`**: `@WebServlet("/a")` and
   `@WebServlet(urlPatterns="/a")` are equivalent; both set is a compile
   ERROR (§8.1.1) — Task 2.3 test.
3. **Default names**: `@WebServlet` without `name` → the fully qualified
   class name (§8.1.1); same for `@WebFilter` (Task 2.2/2.3 tests).
4. **Class-File tier on a class whose module does not open its package to
   Foy**: `privateLookupIn` fails → reflective tier → if that fails too, a
   `ServletException` naming the class and the missing `opens`, not an NPE
   (Task 2.5 test).
5. **Repeated deployments with different class loaders** (TCK deploys many
   wars): the registry caches per `Class` object, never per class name
   (Task 2.6 test `sameNameDifferentLoaders`).

---

### Task 2.1: The `WebComponent` SPI

**Files:**
- Create: `foy-api/src/main/java/io/vidocq/foy/spi/gen/WebComponent.java`
- Create: `foy-api/src/main/java/io/vidocq/foy/spi/gen/WebComponentDescriptor.java`
- Create: `foy-api/src/main/java/io/vidocq/foy/spi/cdi/CdiWebComponents.java`
- Modify: `foy-api/src/main/java/module-info.java` (two `exports`)
- Test: `foy-api/src/test/java/io/vidocq/foy/spi/gen/WebComponentDescriptorTest.java`

**Interfaces:**
- Produces:

```java
package io.vidocq.foy.spi.gen;

/** Build-time generated (or runtime-derived) description of one web component class. */
public interface WebComponent {
    Class<?> type();
    WebComponentDescriptor descriptor();
    /** A new instance; generated code calls the no-arg constructor directly. */
    Object newInstance();
}

public record WebComponentDescriptor(Kind kind,
                                     String name,                       // null for LISTENER/INITIALIZER/PLAIN
                                     List<String> urlPatterns,
                                     Map<String, String> initParams,     // declaration order kept
                                     int loadOnStartup,                  // Integer.MIN_VALUE when absent
                                     boolean asyncSupported,
                                     Set<DispatcherType> dispatcherTypes,
                                     List<String> servletNames,          // @WebFilter(servletNames)
                                     MultipartConfigElement multipartConfig,   // nullable
                                     ServletSecurityElement servletSecurity,   // nullable
                                     List<String> declaredRoles,
                                     String runAs,                       // nullable
                                     List<String> handlesTypes) {        // FQCNs, INITIALIZER only
    public enum Kind { SERVLET, FILTER, LISTENER, INITIALIZER, PLAIN }

    /** Builder-style factory used by generated code to keep it short. */
    public static WebComponentDescriptor plain() { ... }          // Kind.PLAIN, everything empty
    public WebComponentDescriptor withKind(Kind k) { ... }
    public WebComponentDescriptor withName(String n) { ... }
    public WebComponentDescriptor withUrlPatterns(String... p) { ... }
    public WebComponentDescriptor withInitParams(String... keyValuePairs) { ... } // even length, ordered
    public WebComponentDescriptor withLoadOnStartup(int v) { ... }
    public WebComponentDescriptor withAsyncSupported(boolean v) { ... }
    public WebComponentDescriptor withDispatcherTypes(DispatcherType... t) { ... }
    public WebComponentDescriptor withServletNames(String... n) { ... }
    public WebComponentDescriptor withMultipartConfig(MultipartConfigElement m) { ... }
    public WebComponentDescriptor withServletSecurity(ServletSecurityElement s) { ... }
    public WebComponentDescriptor withDeclaredRoles(String... r) { ... }
    public WebComponentDescriptor withRunAs(String r) { ... }
    public WebComponentDescriptor withHandlesTypes(String... fqcns) { ... }
}
```

```java
package io.vidocq.foy.spi.cdi;

/** Synthetic bean registered at build time by foy-cdi-vauban's BCE: the CDI-managed web components. */
public interface CdiWebComponents {
    List<Class<?>> componentClasses();
}
```

The compact constructor copies collections (`List.copyOf`, ordered
unmodifiable map, `Set.copyOf` → `EnumSet` semantics not required) and
replaces null collections with empty ones.

- [ ] **Step 1: Write the failing test**

```java
package io.vidocq.foy.spi.gen;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WebComponentDescriptorTest {

    @Test
    void plainHasEmptyDefaults() {
        var d = WebComponentDescriptor.plain();
        assertEquals(WebComponentDescriptor.Kind.PLAIN, d.kind());
        assertNull(d.name());
        assertEquals(List.of(), d.urlPatterns());
        assertEquals(Map.of(), d.initParams());
        assertEquals(Integer.MIN_VALUE, d.loadOnStartup());
        assertFalse(d.asyncSupported());
        assertEquals(Set.of(), d.dispatcherTypes());
        assertNull(d.multipartConfig());
    }

    @Test
    void initParamsKeepDeclarationOrder() {
        var d = WebComponentDescriptor.plain().withInitParams("z", "1", "a", "2");
        assertEquals(List.of("z", "a"), List.copyOf(d.initParams().keySet()));
    }

    @Test
    void oddInitParamsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> WebComponentDescriptor.plain().withInitParams("k"));
    }

    @Test
    void withersReturnModifiedCopies() {
        var base = WebComponentDescriptor.plain();
        var d = base.withKind(WebComponentDescriptor.Kind.FILTER).withName("f")
                .withUrlPatterns("/a", "/b").withDispatcherTypes(DispatcherType.FORWARD);
        assertEquals(WebComponentDescriptor.Kind.PLAIN, base.kind());
        assertEquals(List.of("/a", "/b"), d.urlPatterns());
        assertEquals(Set.of(DispatcherType.FORWARD), d.dispatcherTypes());
    }
}
```

- [ ] **Step 2: Run** — `./mvnw -ntp -pl foy-api test -Dtest=WebComponentDescriptorTest` → compilation failure.
- [ ] **Step 3: Implement** the three types as specified; add
  `exports io.vidocq.foy.spi.gen;` and `exports io.vidocq.foy.spi.cdi;` to
  `foy-api/src/main/java/module-info.java`.
- [ ] **Step 4: Run** — same command → 4 PASS.
- [ ] **Step 5: Commit** — `feat(api): add the WebComponent code-generation SPI`.

---

### Task 2.2: `foy-processor` — module, compile harness, `@WebServlet`

**Files:**
- Create: `foy-processor/pom.xml`
- Modify: `pom.xml` (add `<module>foy-processor</module>` after `foy-api`, and a `dependencyManagement` entry)
- Create: `foy-processor/src/main/java/module-info.java`
- Create: `foy-processor/src/main/java/io/vidocq/foy/processor/FoyWebComponentProcessor.java`
- Create: `foy-processor/src/main/java/io/vidocq/foy/processor/ComponentModel.java`
- Create: `foy-processor/src/main/java/io/vidocq/foy/processor/ComponentSourceWriter.java`
- Create: `foy-processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor`
- Test: `foy-processor/src/test/java/io/vidocq/foy/processor/CompileHarness.java`
- Test: `foy-processor/src/test/java/io/vidocq/foy/processor/WebServletGenerationTest.java`

**Interfaces:**
- Consumes: `WebComponent`, `WebComponentDescriptor` (2.1) — by name in generated source only.
- Produces:
  - Processor FQCN `io.vidocq.foy.processor.FoyWebComponentProcessor`,
    `@SupportedAnnotationTypes("*")`, `getSupportedSourceVersion()` returns `SourceVersion.latestSupported()`.
  - For each concrete, non-abstract, top-level or `static` nested class that
    implements `jakarta.servlet.Servlet`, `jakarta.servlet.Filter`,
    `java.util.EventListener` (servlet listener interfaces only — see 2.3) or
    `jakarta.servlet.ServletContainerInitializer`, and has an accessible
    no-arg constructor: a source file `<package>.<BinarySimpleName>$$FoyComponent`
    where `BinarySimpleName` = binary name minus package (`Outer$Inner`).
  - On `processingOver()`: `META-INF/services/io.vidocq.foy.spi.gen.WebComponent`
    listing all generated FQCNs, and a NOTE
    `"foy: add to module-info: provides io.vidocq.foy.spi.gen.WebComponent with <list>;"`
    when the compiled module is named (`Elements.getModuleOf(..).isUnnamed() == false`).
  - Test helper: `CompileHarness.compile(Path out, Map<String,String> sources) → Result(URLClassLoader loader, List<Diagnostic<?>> diagnostics, boolean success)`.

Generated source shape (exact):

```java
package com.acme;

// Generated by io.vidocq.foy.processor.FoyWebComponentProcessor — do not edit.
public final class Hello$$FoyComponent implements io.vidocq.foy.spi.gen.WebComponent {
    private static final io.vidocq.foy.spi.gen.WebComponentDescriptor DESCRIPTOR =
            io.vidocq.foy.spi.gen.WebComponentDescriptor.plain()
                    .withKind(io.vidocq.foy.spi.gen.WebComponentDescriptor.Kind.SERVLET)
                    .withName("hello")
                    .withUrlPatterns("/h")
                    .withInitParams("k", "v")
                    .withLoadOnStartup(1)
                    .withAsyncSupported(true);
    public Hello$$FoyComponent() {}
    @Override public Class<?> type() { return com.acme.Hello.class; }
    @Override public io.vidocq.foy.spi.gen.WebComponentDescriptor descriptor() { return DESCRIPTOR; }
    @Override public Object newInstance() { return new com.acme.Hello(); }
}
```

String literals are escaped with a `ComponentSourceWriter.literal(String)`
helper (backslash, quote, `\n`, `\r`, `\t`, non-ASCII as `\uXXXX`).

- [ ] **Step 1: Create the module POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.vidocq.foy</groupId>
        <artifactId>foy-parent</artifactId>
        <version>0.4.0-SNAPSHOT</version>
    </parent>
    <artifactId>foy-processor</artifactId>
    <name>Foy :: Processor</name>
    <description>Build-time annotation processor generating WebComponent descriptors and factories.</description>

    <dependencies>
        <dependency>
            <groupId>io.vidocq.foy</groupId>
            <artifactId>foy-api</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>jakarta.servlet</groupId>
            <artifactId>jakarta.servlet-api</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <!-- The processor must not run on itself. -->
                    <proc>none</proc>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

(Copy the license header comment and `<version>` handling from `foy-api/pom.xml`; if the
parent pins junit's version in `dependencyManagement`, leave the version out as above.)

`module-info.java`:

```java
module io.vidocq.foy.processor {
    requires java.compiler;
    exports io.vidocq.foy.processor;
    provides javax.annotation.processing.Processor
            with io.vidocq.foy.processor.FoyWebComponentProcessor;
}
```

The processor reads the Servlet annotations through the `javax.lang.model`
mirror API by **name** (`"jakarta.servlet.annotation.WebServlet"`), so it
does not need the servlet API at processor compile time.

- [ ] **Step 2: Write the compile harness and the failing test**

`CompileHarness` — port the classpath collection of
`cassini/cassini-processor/src/test/java/io/vidocq/cassini/processor/CassiniResourceProcessorTest.java`
(`compileWithProcessor`: `java.class.path` + URLClassLoader ancestors +
module layer locations), with this API:

```java
package io.vidocq.foy.processor;

import javax.tools.*;
import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

final class CompileHarness {
    record Result(URLClassLoader loader, List<Diagnostic<? extends JavaFileObject>> diagnostics,
                  boolean success, Path output) {
        String messages() {
            var sb = new StringBuilder();
            for (var d : diagnostics) sb.append(d.getKind()).append(": ").append(d.getMessage(Locale.ROOT)).append('\n');
            return sb.toString();
        }
        String generatedSource(String fqcn) throws java.io.IOException {
            return Files.readString(output.resolve("generated").resolve(fqcn.replace('.', '/') + ".java"));
        }
        String resource(String path) throws java.io.IOException {
            return Files.readString(output.resolve("classes").resolve(path));
        }
    }

    /** sources: FQCN → source text. Compiles on the classpath with FoyWebComponentProcessor. */
    static Result compile(Path out, Map<String, String> sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var diags = new DiagnosticCollector<JavaFileObject>();
        Path src = Files.createDirectories(out.resolve("src"));
        Path classes = Files.createDirectories(out.resolve("classes"));
        Path generated = Files.createDirectories(out.resolve("generated"));
        List<File> files = new ArrayList<>();
        for (var e : sources.entrySet()) {
            Path p = src.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
            files.add(p.toFile());
        }
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diags, Locale.ROOT, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
            fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(generated.toFile()));
            fm.setLocation(StandardLocation.CLASS_PATH, testClasspath());
            var task = compiler.getTask(null, fm, diags, List.of("-proc:full"), null,
                    fm.getJavaFileObjectsFromFiles(files));
            task.setProcessors(List.of(new FoyWebComponentProcessor()));
            boolean ok = task.call();
            var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},
                    CompileHarness.class.getClassLoader());
            return new Result(loader, diags.getDiagnostics(), ok, out);
        }
    }

    private static List<File> testClasspath() throws Exception {
        // Port of CassiniResourceProcessorTest#compileWithProcessor classpath collection.
        Set<File> cp = new LinkedHashSet<>();
        for (String e : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!e.isBlank()) cp.add(new File(e));
        }
        ModuleLayer layer = CompileHarness.class.getModule().getLayer();
        if (layer != null) {
            layer.configuration().modules().forEach(rm -> rm.reference().location().ifPresent(uri -> {
                if ("file".equals(uri.getScheme())) cp.add(new File(uri));
            }));
        }
        return List.copyOf(cp);
    }

    private CompileHarness() {}
}
```

`WebServletGenerationTest`:

```java
package io.vidocq.foy.processor;

import io.vidocq.foy.spi.gen.WebComponent;
import io.vidocq.foy.spi.gen.WebComponentDescriptor.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebServletGenerationTest {

    @TempDir Path out;

    private static final String HELLO = """
            package com.acme;
            import jakarta.servlet.annotation.*;
            @WebServlet(name = "hello", urlPatterns = "/h", loadOnStartup = 1, asyncSupported = true,
                        initParams = @WebInitParam(name = "k", value = "v\\"q"))
            public class Hello extends jakarta.servlet.http.HttpServlet {}
            """;

    private WebComponent load(CompileHarness.Result r, String fqcn) throws Exception {
        return (WebComponent) r.loader().loadClass(fqcn).getConstructor().newInstance();
    }

    @Test
    void generatesDescriptorAndFactory() throws Exception {
        var r = CompileHarness.compile(out, Map.of("com.acme.Hello", HELLO));
        assertTrue(r.success(), r.messages());
        WebComponent c = load(r, "com.acme.Hello$$FoyComponent");
        assertEquals("com.acme.Hello", c.type().getName());
        assertEquals("com.acme.Hello", c.newInstance().getClass().getName());
        var d = c.descriptor();
        assertEquals(Kind.SERVLET, d.kind());
        assertEquals("hello", d.name());
        assertEquals(List.of("/h"), d.urlPatterns());
        assertEquals(Map.of("k", "v\"q"), d.initParams());
        assertEquals(1, d.loadOnStartup());
        assertTrue(d.asyncSupported());
    }

    @Test
    void valueIsAnAliasAndDefaultNameIsTheClassName() throws Exception {
        var r = CompileHarness.compile(out, Map.of("com.acme.V", """
                package com.acme;
                @jakarta.servlet.annotation.WebServlet("/v")
                public class V extends jakarta.servlet.http.HttpServlet {}
                """));
        assertTrue(r.success(), r.messages());
        var d = load(r, "com.acme.V$$FoyComponent").descriptor();
        assertEquals("com.acme.V", d.name());
        assertEquals(List.of("/v"), d.urlPatterns());
        assertEquals(Integer.MIN_VALUE, d.loadOnStartup());
    }

    @Test
    void staticNestedClassUsesBinaryName() throws Exception {
        var r = CompileHarness.compile(out, Map.of("com.acme.Outer", """
                package com.acme;
                public class Outer {
                    @jakarta.servlet.annotation.WebServlet("/n")
                    public static class Inner extends jakarta.servlet.http.HttpServlet {}
                }
                """));
        assertTrue(r.success(), r.messages());
        assertEquals("com.acme.Outer$Inner", load(r, "com.acme.Outer$Inner$$FoyComponent").type().getName());
    }

    @Test
    void privateConstructorGetsNoteAndNoGeneratedClass() throws Exception {
        var r = CompileHarness.compile(out, Map.of("com.acme.P", """
                package com.acme;
                @jakarta.servlet.annotation.WebServlet("/p")
                public class P extends jakarta.servlet.http.HttpServlet { private P() {} }
                """));
        assertTrue(r.success(), r.messages());
        assertThrows(ClassNotFoundException.class, () -> r.loader().loadClass("com.acme.P$$FoyComponent"));
        assertTrue(r.messages().contains("NOTE") && r.messages().contains("com.acme.P"), r.messages());
    }

    @Test
    void writesServiceFile() throws Exception {
        var r = CompileHarness.compile(out, Map.of("com.acme.Hello", HELLO));
        assertEquals("com.acme.Hello$$FoyComponent",
                r.resource("META-INF/services/io.vidocq.foy.spi.gen.WebComponent").strip());
    }
}
```

- [ ] **Step 3: Run** — `./mvnw -ntp -pl foy-api,foy-processor install -Dtest=WebServletGenerationTest -Dsurefire.failIfNoSpecifiedTests=false` → compilation failure (processor classes missing).

- [ ] **Step 4: Implement**

`ComponentModel` — a record extracted from a `TypeElement`:
`(String packageName, String binarySimpleName, String typeFqcn, Kind kind, String name, List<String> urlPatterns, List<String[]> initParams, int loadOnStartup, boolean asyncSupported, List<String> dispatcherTypes, List<String> servletNames, …fields added in 2.3)`
with `static Optional<ComponentModel> from(TypeElement, ProcessingEnvironment)`.
Read annotation values via `AnnotationMirror` + `Elements.getElementValuesWithDefaults`,
matching `annotationType` by `getQualifiedName().contentEquals(...)`.
Kind detection by `Types.isAssignable(type.asType(), elements.getTypeElement("jakarta.servlet.Servlet").asType())`
(and Filter / SCI); if the servlet API is not on the compile path, skip silently.

`FoyWebComponentProcessor.process`: for every root element (recursing into
member types), if `ComponentModel.from` returns a model and the class is
generatable (not abstract, top-level or static, a no-arg constructor that is
not private), call `ComponentSourceWriter.write(model, filer)`; otherwise
when the class carries a `jakarta.servlet.annotation.Web*` annotation emit
`Kind.NOTE`: `"foy: no generated component for <fqcn> (<reason>); it will be resolved at runtime"`.
Collect generated FQCNs; on `processingOver()` write the services file via
`filer.createResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/services/io.vidocq.foy.spi.gen.WebComponent")`
and the module NOTE.

`ComponentSourceWriter.write`: emit exactly the shape above, chaining only
the `with*` calls whose value differs from `plain()`.

- [ ] **Step 5: Run** — same command → 5 PASS.
- [ ] **Step 6: Commit** — `feat(processor): generate WebComponent descriptors and factories for @WebServlet`.

---

### Task 2.3: Filters, listeners, plain components, security and multipart metadata, diagnostics

**Files:**
- Modify: `foy-processor/src/main/java/io/vidocq/foy/processor/{ComponentModel,ComponentSourceWriter,FoyWebComponentProcessor}.java`
- Test: `foy-processor/src/test/java/io/vidocq/foy/processor/ComponentKindsGenerationTest.java`

**Interfaces:**
- Produces: generated components for:
  - `@WebFilter` → `Kind.FILTER`, name (default FQCN), `urlPatterns`/`value`,
    `servletNames`, `dispatcherTypes` (default `REQUEST`), `initParams`, `asyncSupported`.
  - `@WebListener` → `Kind.LISTENER`.
  - Unannotated concrete Servlet / Filter / listener class (implements one of
    `ServletContextListener`, `ServletContextAttributeListener`,
    `ServletRequestListener`, `ServletRequestAttributeListener`,
    `HttpSessionListener`, `HttpSessionAttributeListener`,
    `HttpSessionIdListener`) → `Kind.PLAIN` (factory only; used for web.xml
    and `addServlet(Class)` instantiation).
  - `ServletContainerInitializer` → `Kind.INITIALIZER` with `handlesTypes`
    from `@HandlesTypes` (FQCNs via `TypeMirror` of the `Class[]` value).
  - `@MultipartConfig` → `.withMultipartConfig(new jakarta.servlet.MultipartConfigElement(location, maxFileSize, maxRequestSize, fileSizeThreshold))`.
  - `@ServletSecurity` → `.withServletSecurity(new jakarta.servlet.ServletSecurityElement(new jakarta.servlet.HttpConstraintElement(...), java.util.List.of(new jakarta.servlet.HttpMethodConstraintElement(...))))`
    rebuilding `@HttpConstraint` (`value` EmptyRoleSemantic, `transportGuarantee`, `rolesAllowed`)
    and each `@HttpMethodConstraint`.
  - `@DeclareRoles` → `.withDeclaredRoles(...)`; `@RunAs` → `.withRunAs(...)`.
- Compile **ERROR** diagnostics (§8.1): `@WebServlet` on a non-Servlet,
  `@WebFilter` on a non-Filter, `@WebListener` on a class implementing none
  of the listener interfaces above, both `value` and `urlPatterns` set,
  `@WebServlet`/`@WebFilter` with neither patterns nor (for filters) servletNames.

- [ ] **Step 1: Write the failing test**

```java
package io.vidocq.foy.processor;

import io.vidocq.foy.spi.gen.WebComponent;
import io.vidocq.foy.spi.gen.WebComponentDescriptor.Kind;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.annotation.ServletSecurity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ComponentKindsGenerationTest {

    @TempDir Path out;

    private WebComponent compileOne(String fqcn, String src) throws Exception {
        var r = CompileHarness.compile(out, Map.of(fqcn, src));
        assertTrue(r.success(), r.messages());
        return (WebComponent) r.loader().loadClass(fqcn + "$$FoyComponent").getConstructor().newInstance();
    }

    private CompileHarness.Result compileFailing(String fqcn, String src) throws Exception {
        var r = CompileHarness.compile(out, Map.of(fqcn, src));
        assertFalse(r.success(), "expected a compile error");
        return r;
    }

    @Test
    void filter() throws Exception {
        var d = compileOne("a.F", """
                package a;
                import jakarta.servlet.*; import jakarta.servlet.annotation.*;
                @WebFilter(urlPatterns = "/*", servletNames = "s", dispatcherTypes = {DispatcherType.FORWARD, DispatcherType.ERROR})
                public class F implements Filter {
                    public void doFilter(ServletRequest q, ServletResponse r, FilterChain c) {}
                }
                """).descriptor();
        assertEquals(Kind.FILTER, d.kind());
        assertEquals("a.F", d.name());
        assertEquals(List.of("/*"), d.urlPatterns());
        assertEquals(List.of("s"), d.servletNames());
        assertEquals(Set.of(DispatcherType.FORWARD, DispatcherType.ERROR), d.dispatcherTypes());
    }

    @Test
    void listenerAndPlainAndInitializer() throws Exception {
        var r = CompileHarness.compile(out, Map.of(
                "a.L", """
                    package a;
                    @jakarta.servlet.annotation.WebListener
                    public class L implements jakarta.servlet.ServletContextListener {}
                    """,
                "a.Plain", """
                    package a;
                    public class Plain extends jakarta.servlet.http.HttpServlet {}
                    """,
                "a.Init", """
                    package a;
                    @jakarta.servlet.annotation.HandlesTypes({java.lang.Runnable.class, jakarta.servlet.annotation.WebServlet.class})
                    public class Init implements jakarta.servlet.ServletContainerInitializer {
                        public void onStartup(java.util.Set<Class<?>> c, jakarta.servlet.ServletContext ctx) {}
                    }
                    """));
        assertTrue(r.success(), r.messages());
        var l = (WebComponent) r.loader().loadClass("a.L$$FoyComponent").getConstructor().newInstance();
        var p = (WebComponent) r.loader().loadClass("a.Plain$$FoyComponent").getConstructor().newInstance();
        var i = (WebComponent) r.loader().loadClass("a.Init$$FoyComponent").getConstructor().newInstance();
        assertEquals(Kind.LISTENER, l.descriptor().kind());
        assertEquals(Kind.PLAIN, p.descriptor().kind());
        assertEquals(Kind.INITIALIZER, i.descriptor().kind());
        assertEquals(List.of("java.lang.Runnable", "jakarta.servlet.annotation.WebServlet"),
                i.descriptor().handlesTypes());
    }

    @Test
    void securityMultipartRolesRunAs() throws Exception {
        var d = compileOne("a.S", """
                package a;
                import jakarta.servlet.annotation.*;
                @WebServlet("/s")
                @MultipartConfig(location = "/tmp", maxFileSize = 10, maxRequestSize = 20, fileSizeThreshold = 5)
                @ServletSecurity(value = @HttpConstraint(rolesAllowed = "admin"),
                                 httpMethodConstraints = @HttpMethodConstraint(value = "GET",
                                     emptyRoleSemantic = ServletSecurity.EmptyRoleSemantic.PERMIT))
                @jakarta.annotation.security.DeclareRoles({"admin", "user"})
                @jakarta.annotation.security.RunAs("admin")
                public class S extends jakarta.servlet.http.HttpServlet {}
                """).descriptor();
        assertEquals("/tmp", d.multipartConfig().getLocation());
        assertEquals(10, d.multipartConfig().getMaxFileSize());
        assertEquals(5, d.multipartConfig().getFileSizeThreshold());
        assertArrayEquals(new String[]{"admin"}, d.servletSecurity().getRolesAllowed().toArray());
        var get = d.servletSecurity().getHttpMethodConstraints().iterator().next();
        assertEquals("GET", get.getMethodName());
        assertEquals(ServletSecurity.EmptyRoleSemantic.PERMIT, get.getEmptyRoleSemantic());
        assertEquals(List.of("admin", "user"), d.declaredRoles());
        assertEquals("admin", d.runAs());
    }

    @Test
    void webServletOnNonServletIsAnError() throws Exception {
        var r = compileFailing("a.X", """
                package a;
                @jakarta.servlet.annotation.WebServlet("/x") public class X {}
                """);
        assertTrue(r.messages().contains("a.X") && r.messages().contains("jakarta.servlet.Servlet"), r.messages());
    }

    @Test
    void valueAndUrlPatternsTogetherIsAnError() throws Exception {
        var r = compileFailing("a.Y", """
                package a;
                @jakarta.servlet.annotation.WebServlet(value = "/a", urlPatterns = "/b")
                public class Y extends jakarta.servlet.http.HttpServlet {}
                """);
        assertTrue(r.messages().contains("value") && r.messages().contains("urlPatterns"), r.messages());
    }

    @Test
    void webListenerWithoutListenerInterfaceIsAnError() throws Exception {
        compileFailing("a.Z", """
                package a;
                @jakarta.servlet.annotation.WebListener public class Z implements java.util.EventListener {}
                """);
    }
}
```

`foy-processor/pom.xml` gains `jakarta.annotation:jakarta.annotation-api` (test scope) for `@DeclareRoles`/`@RunAs`.

- [ ] **Step 2: Run** — `./mvnw -ntp -pl foy-processor test -Dtest=ComponentKindsGenerationTest` → failures (filter/listener not generated, no errors emitted).
- [ ] **Step 3: Implement** the kinds, metadata and diagnostics listed in **Interfaces**. Errors use `messager.printMessage(Kind.ERROR, msg, element, annotationMirror)`.
- [ ] **Step 4: Run** — `./mvnw -ntp -pl foy-processor test` → all PASS.
- [ ] **Step 5: Commit** — `feat(processor): cover filters, listeners, initializers, security and multipart metadata`.

---

### Task 2.4: Class index for `@HandlesTypes`

**Files:**
- Modify: `foy-processor/src/main/java/io/vidocq/foy/processor/FoyWebComponentProcessor.java`
- Create: `foy-processor/src/main/java/io/vidocq/foy/processor/ClassIndexWriter.java`
- Test: `foy-processor/src/test/java/io/vidocq/foy/processor/ClassIndexTest.java`

**Interfaces:**
- Produces: resource `META-INF/foy/class-index.list`, one line per compiled
  type (top-level and nested, including interfaces and abstract classes —
  §8.2.4 matches those too):
  `<binary name>|<comma-separated binary names of all supertypes, transitive>|<comma-separated FQCNs of type-level annotations>`
  Sorted by binary name; header line `# foy class index v1`.
  Format consumed by Task 2.7 (`IndexedHandlesTypesResolver`).

- [ ] **Step 1: Write the failing test**

```java
package io.vidocq.foy.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClassIndexTest {

    @TempDir Path out;

    @Test
    void indexesSupertypesTransitivelyAndAnnotations() throws Exception {
        var r = CompileHarness.compile(out, Map.of(
                "a.Base", "package a; public abstract class Base implements Runnable {}",
                "a.Impl", "package a; @Deprecated public class Impl extends Base { public void run() {} }"));
        assertTrue(r.success(), r.messages());
        var lines = r.resource("META-INF/foy/class-index.list").lines().toList();
        assertEquals("# foy class index v1", lines.getFirst());
        String impl = lines.stream().filter(l -> l.startsWith("a.Impl|")).findFirst().orElseThrow();
        var parts = impl.split("\\|", -1);
        assertTrue(parts[1].contains("a.Base") && parts[1].contains("java.lang.Runnable")
                && parts[1].contains("java.lang.Object"), impl);
        assertEquals("java.lang.Deprecated", parts[2]);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("a.Base|")));
    }
}
```

- [ ] **Step 2: Run** — `./mvnw -ntp -pl foy-processor test -Dtest=ClassIndexTest` → FAIL (resource missing).
- [ ] **Step 3: Implement** `ClassIndexWriter` (collect during rounds via
  `Types.directSupertypes` recursively, `Elements.getBinaryName`; write on
  `processingOver()`).
- [ ] **Step 4: Run** — `./mvnw -ntp -pl foy-processor test` → PASS.
- [ ] **Step 5: Commit** — `feat(processor): emit a class index for @HandlesTypes resolution`.

---

### Task 2.5: Class-File degraded tier

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/gen/ClassFileDescriptorReader.java`
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/gen/HiddenFactoryEmitter.java`
- Modify: `foy-core/src/main/java/module-info.java` (`exports io.vidocq.foy.internal.gen;`)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/gen/ClassFileDescriptorReaderTest.java`
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/gen/HiddenFactoryEmitterTest.java`

**Interfaces:**
- Consumes: `WebComponentDescriptor` (2.1).
- Produces:

```java
/** Decodes Servlet annotations from class bytes (java.lang.classfile) — no reflection. */
public final class ClassFileDescriptorReader {
    /** @return empty if the class bytes are not reachable (e.g. generated/hidden class). */
    public static Optional<WebComponentDescriptor> read(Class<?> type);
    /** Also used by Task 2.7 to read @HandlesTypes. */
    static WebComponentDescriptor read(byte[] classBytes, Class<?> type);
}

/** Emits a hidden Supplier class calling the no-arg constructor (java.lang.classfile). */
public final class HiddenFactoryEmitter {
    /** @throws IllegalAccessException when the type's package is not open to foy-core. */
    public static Supplier<Object> factoryFor(Class<?> type) throws IllegalAccessException;
}
```

Reader details: bytes via `type.getModule().getResourceAsStream(binaryName.replace('.', '/') + ".class")`
(fallback `type.getClassLoader().getResourceAsStream(..)`); `ClassFile.of().parse(bytes)`;
`findAttribute(Attributes.runtimeVisibleAnnotations())`; for each annotation match
`classSymbol().descriptorString()` (`Ljakarta/servlet/annotation/WebServlet;` etc.);
decode `AnnotationValue.OfString/OfInt/OfLong/OfBoolean/OfEnum/OfArray/OfAnnotation/OfClass`.
Kind for unannotated classes: `PLAIN` when `Servlet/Filter/listener/SCI` is
assignable (`Class.isAssignableFrom` is not reflection on members and is allowed).
Defaults identical to the processor (name = FQCN, dispatcherTypes = REQUEST for filters).

Emitter details:

```java
    public static Supplier<Object> factoryFor(Class<?> type) throws IllegalAccessException {
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        ClassDesc target = ClassDesc.of(type.getName());
        ClassDesc self = ClassDesc.of(type.getPackageName().isEmpty()
                ? "FoyFactory" : type.getPackageName() + ".FoyFactory");
        byte[] bytes = ClassFile.of().build(self, cb -> cb
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC)
                .withInterfaceSymbols(ClassDesc.of("java.util.function.Supplier"))
                .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, c -> c
                        .aload(0).invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .return_())
                .withMethodBody("get", MethodTypeDesc.of(ConstantDescs.CD_Object), ClassFile.ACC_PUBLIC, c -> c
                        .new_(target).dup()
                        .invokespecial(target, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .areturn()));
        MethodHandles.Lookup hidden = lookup.defineHiddenClass(bytes, true);
        try {
            @SuppressWarnings("unchecked")
            Supplier<Object> s = (Supplier<Object>) hidden.findConstructor(hidden.lookupClass(),
                    MethodType.methodType(void.class)).invoke();
            return s;
        } catch (Throwable t) {
            throw new IllegalStateException("cannot instantiate hidden factory for " + type.getName(), t);
        }
    }
```

The emitted class file version must be the running JDK's
(`ClassFile.latestMajorVersion()`, the default of `ClassFile.of()`).

- [ ] **Step 1: Write the failing tests**

```java
package io.vidocq.foy.internal.gen;

import io.vidocq.foy.spi.gen.WebComponentDescriptor.Kind;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.annotation.*;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ClassFileDescriptorReaderTest {

    @WebServlet(name = "cf", urlPatterns = {"/a", "/b"}, loadOnStartup = 4, asyncSupported = true,
            initParams = {@WebInitParam(name = "x", value = "1"), @WebInitParam(name = "y", value = "2")})
    @MultipartConfig(maxFileSize = 7)
    public static class Annotated extends HttpServlet {}

    @WebFilter(value = "/f", dispatcherTypes = DispatcherType.ASYNC)
    public static class AFilter implements jakarta.servlet.Filter {
        @Override public void doFilter(jakarta.servlet.ServletRequest q, jakarta.servlet.ServletResponse r,
                                       jakarta.servlet.FilterChain c) {}
    }

    public static class Plain extends HttpServlet {}

    @Test
    void decodesWebServlet() {
        var d = ClassFileDescriptorReader.read(Annotated.class).orElseThrow();
        assertEquals(Kind.SERVLET, d.kind());
        assertEquals("cf", d.name());
        assertEquals(List.of("/a", "/b"), d.urlPatterns());
        assertEquals(Map.of("x", "1", "y", "2"), d.initParams());
        assertEquals(4, d.loadOnStartup());
        assertTrue(d.asyncSupported());
        assertEquals(7, d.multipartConfig().getMaxFileSize());
    }

    @Test
    void decodesWebFilterWithDefaults() {
        var d = ClassFileDescriptorReader.read(AFilter.class).orElseThrow();
        assertEquals(Kind.FILTER, d.kind());
        assertEquals(AFilter.class.getName(), d.name());
        assertEquals(List.of("/f"), d.urlPatterns());
        assertEquals(Set.of(DispatcherType.ASYNC), d.dispatcherTypes());
    }

    @Test
    void unannotatedServletIsPlain() {
        assertEquals(Kind.PLAIN, ClassFileDescriptorReader.read(Plain.class).orElseThrow().kind());
    }

    @Test
    void hiddenOrLambdaClassesYieldEmpty() {
        Runnable r = () -> {};
        assertTrue(ClassFileDescriptorReader.read(r.getClass()).isEmpty());
    }
}
```

```java
package io.vidocq.foy.internal.gen;

import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HiddenFactoryEmitterTest {

    public static class Target extends HttpServlet {}
    static class PackagePrivateCtor extends HttpServlet { PackagePrivateCtor() {} }

    @Test
    void factoryCreatesFreshInstances() throws Exception {
        var f = HiddenFactoryEmitter.factoryFor(Target.class);
        Object a = f.get(), b = f.get();
        assertInstanceOf(Target.class, a);
        assertNotSame(a, b);
    }

    @Test
    void packagePrivateConstructorIsReachable() throws Exception {
        assertInstanceOf(PackagePrivateCtor.class, HiddenFactoryEmitter.factoryFor(PackagePrivateCtor.class).get());
    }
}
```

The "package not open" case (Review Focus 4) is tested in Task 2.6 with a
class defined in a separate `ModuleLayer` built in the test.

- [ ] **Step 2: Run** — `./mvnw -ntp -pl foy-core test -Dtest='ClassFileDescriptorReaderTest,HiddenFactoryEmitterTest'` → compilation failure.
- [ ] **Step 3: Implement** both classes as specified.
- [ ] **Step 4: Run** — same command → 6 PASS.
- [ ] **Step 5: Commit** — `feat(core): Class-File API tier for unprocessed web components`.

---

### Task 2.6: `WebComponentRegistry` — the four tiers

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/gen/WebComponentRegistry.java`
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/gen/RegistryComponentFactory.java`
- Modify: `foy-core/src/main/java/module-info.java` (`uses io.vidocq.foy.spi.gen.WebComponent;`)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/boot/DeployOptions.java` (`defaults(loader)` uses `RegistryComponentFactory`)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/gen/WebComponentRegistryTest.java`

**Interfaces:**
- Consumes: `WebComponent`, `ClassFileDescriptorReader`, `HiddenFactoryEmitter`, `ComponentFactory`.
- Produces:

```java
public final class WebComponentRegistry {
    public enum Tier { SERVICE_LOADER, GENERATED_CLASS, CLASS_FILE, REFLECTION }
    public record Stats(int serviceLoader, int generatedClass, int classFile, int reflection) {}

    public static WebComponentRegistry forClassLoader(ClassLoader loader);
    /** Never null. Cached per Class object. */
    public WebComponent lookup(Class<?> type);
    public Tier tierOf(Class<?> type);            // after lookup
    public Stats stats();
}

public final class RegistryComponentFactory implements ComponentFactory {
    public RegistryComponentFactory(WebComponentRegistry registry, ClassLoader loader);
    public RegistryComponentFactory(WebComponentRegistry registry, ClassLoader loader, Set<String> visibleNames);
    public WebComponentRegistry registry();
}
```

Lookup order:
1. `ServiceLoader.load(WebComponent.class, loader)` — indexed once by `type()`
   (`Class` identity) at construction.
2. `Class.forName(binaryName + "$$FoyComponent", true, type.getClassLoader())`
   instantiated through `MethodHandles.publicLookup().findConstructor(..)`
   (it is public with a public no-arg constructor).
3. Class-File: `ClassFileDescriptorReader.read(type)` +
   `HiddenFactoryEmitter.factoryFor(type)` → an internal `WebComponent`
   record. INFO: `"foy: {0} resolved through the Class-File tier (not processed at build time)"`.
4. Reflection: descriptor from `ClassFileDescriptorReader` if bytes were
   readable, else `WebComponentDescriptor.plain()` + `Class.isAssignableFrom`
   kind; factory `type.getDeclaredConstructor().newInstance()`. WARNING:
   `"foy: {0} resolved by reflection ({1}); add foy-processor to the annotation processor path or open the package to io.vidocq.foy.core"`.
   If that also fails, `newInstance()` throws `IllegalStateException` naming the class and suggesting `opens <pkg> to io.vidocq.foy.core`;
   `RegistryComponentFactory.newInstance` wraps it in `ServletException`.

- [ ] **Step 1: Write the failing test**

```java
package io.vidocq.foy.internal.gen;

import io.vidocq.foy.internal.gen.WebComponentRegistry.Tier;
import io.vidocq.foy.spi.gen.WebComponent;
import io.vidocq.foy.spi.gen.WebComponentDescriptor;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.*;

class WebComponentRegistryTest {

    /** Simulates an APT-generated companion (tier 2). */
    public static class Pre extends HttpServlet {}
    public static final class Pre$$FoyComponent implements WebComponent {
        public Pre$$FoyComponent() {}
        @Override public Class<?> type() { return Pre.class; }
        @Override public WebComponentDescriptor descriptor() {
            return WebComponentDescriptor.plain().withKind(WebComponentDescriptor.Kind.SERVLET).withName("pre");
        }
        @Override public Object newInstance() { return new Pre(); }
    }

    @WebServlet("/u") public static class Unprocessed extends HttpServlet {}

    private final WebComponentRegistry registry = WebComponentRegistry.forClassLoader(getClass().getClassLoader());

    @Test
    void generatedCompanionIsPreferred() {
        // Nested binary name: WebComponentRegistryTest$Pre → companion WebComponentRegistryTest$Pre$$FoyComponent
        var c = registry.lookup(Pre.class);
        assertEquals("pre", c.descriptor().name());
        assertEquals(Tier.GENERATED_CLASS, registry.tierOf(Pre.class));
    }

    @Test
    void unprocessedClassUsesClassFileTier() {
        var c = registry.lookup(Unprocessed.class);
        assertEquals(java.util.List.of("/u"), c.descriptor().urlPatterns());
        assertInstanceOf(Unprocessed.class, c.newInstance());
        assertEquals(Tier.CLASS_FILE, registry.tierOf(Unprocessed.class));
        assertEquals(0, registry.stats().reflection());
    }

    @Test
    void lookupIsCached() {
        assertSame(registry.lookup(Unprocessed.class), registry.lookup(Unprocessed.class));
        assertEquals(1, registry.stats().classFile());
    }

    @Test
    void sameNameDifferentLoaders() throws Exception {
        URL classes = Unprocessed.class.getProtectionDomain().getCodeSource().getLocation();
        try (var isolated = new URLClassLoader(new URL[]{classes}, HttpServlet.class.getClassLoader())) {
            Class<?> other = isolated.loadClass(Unprocessed.class.getName());
            assertNotSame(Unprocessed.class, other);
            Object a = registry.lookup(Unprocessed.class).newInstance();
            Object b = registry.lookup(other).newInstance();
            assertSame(Unprocessed.class, a.getClass());
            assertSame(other, b.getClass());
        }
    }
}
```

Note: the `sameNameDifferentLoaders` isolated loader must be able to load
`HttpServlet`; if `HttpServlet.class.getClassLoader()` is the app loader that
also holds the test classes, use `ClassLoader.getPlatformClassLoader()` as
parent plus the servlet-api jar location in the URL array.

- [ ] **Step 2: Run** — `./mvnw -ntp -pl foy-core test -Dtest=WebComponentRegistryTest` → compilation failure.
- [ ] **Step 3: Implement** the registry and factory; switch `DeployOptions.defaults(loader)` to
  `new RegistryComponentFactory(WebComponentRegistry.forClassLoader(loader), loader)`.
- [ ] **Step 4: Run** — `./mvnw -ntp -pl foy-core test` → all PASS (Phase 1 tests now go through the registry).
- [ ] **Step 5: Commit** — `feat(core): WebComponentRegistry resolving generated, Class-File, then reflective components`.

---

### Task 2.7: `@HandlesTypes` resolution from the class index

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/gen/IndexedHandlesTypesResolver.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/boot/DeployOptions.java` (`defaults` uses it)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/gen/IndexedHandlesTypesResolverTest.java`
- Test resource: `foy-core/src/test/resources/META-INF/foy/class-index.list`

**Interfaces:**
- Consumes: `HandlesTypesResolver` (Phase 1), `WebComponentRegistry` (the
  SCI's `handlesTypes` come from `registry.lookup(sci.getClass()).descriptor().handlesTypes()`),
  index format of Task 2.4.
- Produces: `public IndexedHandlesTypesResolver(WebComponentRegistry registry, ClassLoader loader)` implementing `HandlesTypesResolver`.
  Reads every `META-INF/foy/class-index.list` from `loader.getResources(..)`.
  A class matches when one of its supertypes or annotations is in the SCI's
  `handlesTypes`; the handled types themselves are excluded (§8.2.4: "classes
  that extend, implement, or have been annotated"); matches are loaded with
  `Class.forName(name, false, loader)` (no initialisation), unloadable ones
  are skipped with a FINE log. Returns `null` when the SCI has no
  `@HandlesTypes`, an empty set when it has one but nothing matches
  (behaviour change vs Phase 1's `null`, required by the TCK `sci` tests —
  verify against `servlet.tck.api.jakarta_servlet.servletcontainerinitializer`).
  Phase 3 adds a Class-File jar-scan fallback for jars without an index.

- [ ] **Step 1: Write the failing test**

Test resource `META-INF/foy/class-index.list`:

```
# foy class index v1
io.vidocq.foy.internal.gen.IndexedHandlesTypesResolverTest$Impl|java.lang.Object,java.lang.Runnable|
io.vidocq.foy.internal.gen.IndexedHandlesTypesResolverTest$Marked|java.lang.Object|java.lang.Deprecated
io.vidocq.foy.internal.gen.IndexedHandlesTypesResolverTest$Other|java.lang.Object|
```

```java
package io.vidocq.foy.internal.gen;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.annotation.HandlesTypes;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IndexedHandlesTypesResolverTest {

    public static class Impl implements Runnable { public void run() {} }
    @Deprecated public static class Marked {}
    public static class Other {}

    @HandlesTypes({Runnable.class, Deprecated.class})
    public static class Sci implements ServletContainerInitializer {
        public void onStartup(Set<Class<?>> c, ServletContext ctx) {}
    }
    public static class NoHandles implements ServletContainerInitializer {
        public void onStartup(Set<Class<?>> c, ServletContext ctx) {}
    }

    private final ClassLoader cl = getClass().getClassLoader();
    private final IndexedHandlesTypesResolver resolver =
            new IndexedHandlesTypesResolver(WebComponentRegistry.forClassLoader(cl), cl);

    @Test
    void matchesSubtypesAndAnnotatedClasses() {
        assertEquals(Set.of(Impl.class, Marked.class), resolver.resolve(new Sci()));
    }

    @Test
    void noHandlesTypesYieldsNull() {
        assertNull(resolver.resolve(new NoHandles()));
    }
}
```

- [ ] **Step 2: Run** — `./mvnw -ntp -pl foy-core test -Dtest=IndexedHandlesTypesResolverTest` → compilation failure.
- [ ] **Step 3: Implement**; `DeployOptions.defaults(loader)` uses it.
- [ ] **Step 4: Run** — `./mvnw -ntp -pl foy-core test` → PASS.
- [ ] **Step 5: Commit** — `feat(core): resolve @HandlesTypes from the build-time class index`.

---

### Task 2.8: Remove product reflection; wire discovery and the TCK container to the registry

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppDiscovery.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/security/SecurityConstraintEnforcer.java:60,130`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/container/VidocqServletContext.java:440,487,513-514,528,548`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/async/AsyncContextImpl.java:146`
- Modify: `foy-tck/src/test/java/io/vidocq/foy/tck/arquillian/VidocqDeployableContainer.java` (class scanning `:126`, `:204-252`, `:289-330`)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/gen/NoProductReflectionTest.java`

**Interfaces:**
- Consumes: `WebComponentRegistry`, `RegistryComponentFactory`, `CdiWebComponents`.
- Produces:
  - `VidocqServletContext.setComponentFactory(ComponentFactory)` (set by
    `WebAppDeployer` from `DeployOptions`); `createServlet/createFilter/createListener/addListener(String|Class)`
    use it. `AsyncContextImpl.createListener` uses the context's factory.
  - `SecurityConstraintEnforcer` takes the `ServletSecurityElement` from the
    servlet's descriptor (`ServletDecl` gains `ServletSecurityElement servletSecurity`, nullable) instead of `getAnnotation`.
  - `WebAppDiscovery.discover(BeanManager, WebComponentRegistry)`: if a
    `CdiWebComponents` bean exists, iterate its `componentClasses()`;
    otherwise (non-vauban CDI container) fall back to the current
    `getBeans(Servlet/Filter/EventListener)` walk and log one INFO line.
    Metadata always from `registry.lookup(cls).descriptor()`; instances from
    `bm.getReference(..)` so CDI injection applies.

- [ ] **Step 1: Write the guard test**

```java
package io.vidocq.foy.internal.gen;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Reflection is allowed only in the registry's last tier. */
class NoProductReflectionTest {

    private static final Pattern REFLECTION =
            Pattern.compile("getAnnotation\\(|getDeclaredConstructor\\(|Class\\.forName\\(");
    private static final List<String> ALLOWED = List.of(
            "internal/gen/WebComponentRegistry.java",
            "internal/gen/IndexedHandlesTypesResolver.java",
            "internal/boot/ComponentFactory.java");

    @Test
    void noReflectionOutsideTheRegistry() throws IOException {
        Path root = Path.of("src/main/java/io/vidocq/foy");
        try (Stream<Path> files = Files.walk(root)) {
            var offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> ALLOWED.stream().noneMatch(a -> p.toString().replace('\\', '/').endsWith(a)))
                    .filter(p -> {
                        try { return REFLECTION.matcher(Files.readString(p)).find(); }
                        catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                    })
                    .map(Path::toString).toList();
            assertEquals(List.of(), offenders);
        }
    }
}
```

(`ComponentFactory.reflective(..)` stays for tests and as an explicit opt-out.)

- [ ] **Step 2: Run** — `./mvnw -ntp -pl foy-core test -Dtest=NoProductReflectionTest` → FAIL listing `WebAppDiscovery`, `SecurityConstraintEnforcer`, `VidocqServletContext`, `AsyncContextImpl`.
- [ ] **Step 3: Implement** the replacements listed in **Interfaces**. In
  `VidocqDeployableContainer`, replace `cls.getAnnotation(WebServlet.class)` etc.
  with `registry.lookup(cls).descriptor()` and instance creation with the
  registry; build a `WebComponentRegistry.forClassLoader(warLoader)` per
  deployment and log its `stats()` on undeploy:
  `"[VidocqTCK] undeploy archive=… tiers=" + registry.stats()`.
- [ ] **Step 4: Run** — `./mvnw -ntp install` → all PASS (guard included).
- [ ] **Step 5: TCK gate** — `./run-official-tck-servlet6.1.sh --all > /tmp/foy-tck-after.log 2>&1`;
  regression diff (Phase 1 Task 0.1 script); and
  `grep -o 'tiers=Stats\[[^]]*\]' /tmp/foy-tck-after.log | grep -v 'reflection=0'` → no output.
- [ ] **Step 6: Commit** — `refactor(core)!: resolve all web components through WebComponentRegistry`.

---

### Task 2.9: BCE spike — what vauban supports at build time

**Files:**
- Create: `foy-cdi-vauban/src/test/java/io/vidocq/foy/cdi/vauban/BceCapabilitySpikeTest.java`
- Create: `foy-cdi-vauban/SPIKE-BCE.md` (findings, deleted at the end of Task 2.10)

Goal: answer, with tests compiled through `vauban-processor` (harness modelled
on `vauban/vauban-processor/src/test/java/io/vidocq/vauban/processor/apt/BceCompileTimeTest.java`),
three questions **before** writing the extension:

1. Does `@Enhancement(types = Object.class, withAnnotations = WebServlet.class)`
   reach a class with **no** bean-defining annotation under vauban's default
   discovery mode, and does `ClassConfig.addAnnotation(Dependent.class)` make
   it a bean?
2. Does `@Synthesis` with `SyntheticBeanBuilder.withParam("classes", Class<?>[])`
   + `createWith(SyntheticBeanCreator)` work when the BCE runs inside
   `vauban-processor` (build time), and are the params available at runtime?
3. Does `@Validation` + `Messages.error(..)` fail the compilation?

- [ ] **Step 1:** Write one test per question compiling a tiny app (a
  `@WebServlet` class without scope + a test BCE) with `vauban-processor`
  on the processor path; assert the outcome (bean present via
  `VaubanContainer`/`BeanManager`, synthetic bean param value, compile error).
- [ ] **Step 2:** Run `./mvnw -ntp -pl foy-cdi-vauban test -Dtest=BceCapabilitySpikeTest`; record each answer in `SPIKE-BCE.md`.
- [ ] **Step 3: Decide** (written in `SPIKE-BCE.md`):
  - Q1 no → the processor (Task 2.2) is the only source of discovery for
    unscoped components; the BCE handles only classes already discovered.
  - Q2 no → drop the synthetic bean; `WebAppDiscovery` uses the
    ServiceLoader `WebComponent` set filtered by `bm.getBeans(type)` non-empty.
    Open a vauban issue describing the gap.
  - Q3 no → validation stays in `foy-processor` only.
- [ ] **Step 4: Commit** — `test(cdi-vauban): spike vauban build-time BCE capabilities`.

---

### Task 2.10: `FoyWebExtension` — the BuildCompatibleExtension

**Files:**
- Create: `foy-cdi-vauban/src/main/java/io/vidocq/foy/cdi/vauban/FoyWebExtension.java`
- Create: `foy-cdi-vauban/src/main/java/io/vidocq/foy/cdi/vauban/CdiWebComponentsCreator.java`
- Create: `foy-cdi-vauban/src/main/resources/META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension`
- Modify: `foy-cdi-vauban/src/main/java/module-info.java` (`provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension with io.vidocq.foy.cdi.vauban.FoyWebExtension;`)
- Modify: `foy-cdi-vauban/pom.xml` (`annotationProcessorPaths` → `vauban-processor`, `default-testCompile` `<proc>none</proc>`, as in `cassini/cassini-cdi-vauban/pom.xml:64-80`)
- Test: `foy-cdi-vauban/src/test/java/io/vidocq/foy/cdi/vauban/FoyWebExtensionTest.java`
- Delete: `foy-cdi-vauban/SPIKE-BCE.md`

**Interfaces:**
- Consumes: spike decisions (Task 2.9), `CdiWebComponents` (2.1).
- Produces (each phase only if the spike confirmed it):

```java
public class FoyWebExtension implements BuildCompatibleExtension {
    private final List<String> components = new ArrayList<>();

    /** Web components without a scope become @Dependent beans (one instance per declaration, held by Foy). */
    @Enhancement(types = Object.class, withAnnotations = {WebServlet.class, WebFilter.class, WebListener.class})
    public void defaultScope(ClassConfig clazz) { ... }

    @Registration(types = {Servlet.class, Filter.class, EventListener.class})
    public void collect(BeanInfo bean) { ... }       // keeps classes carrying a Web* annotation

    @Synthesis
    public void index(SyntheticComponents syn) {
        syn.addBean(CdiWebComponents.class).type(CdiWebComponents.class).scope(Singleton.class)
           .withParam("classes", components.stream().map(...).toArray(Class[]::new))  // per spike
           .createWith(CdiWebComponentsCreator.class);
    }

    @Validation
    public void validate(Messages messages) { ... } // duplicate servlet/filter names across beans → error
}
```

- [ ] **Step 1: Write the failing test** — compile (through the harness of
  Task 2.9) an app with `@WebServlet(name="dup") A`, `@WebServlet(name="dup") B`
  → compile error mentioning `dup`; and an app with an unscoped
  `@WebServlet` + `@Inject` field → at runtime `FoyChappeBoot` with the
  vauban `BeanManager` serves the servlet with the field injected, and
  `CdiWebComponents.componentClasses()` lists it.
- [ ] **Step 2: Run** — `./mvnw -ntp -pl foy-cdi-vauban test -Dtest=FoyWebExtensionTest` → FAIL.
- [ ] **Step 3: Implement** per the spike decisions.
- [ ] **Step 4: Run** — `./mvnw -ntp install` → all PASS.
- [ ] **Step 5: Commit** — `feat(cdi-vauban): FoyWebExtension BCE (scope, build-time validation, synthetic index)`.

---

### Task 2.11: Phase exit — TCK, docs, reference page

**Files:**
- Modify: `TCK.md` (§0 entry with tier stats), `docs/en/modules/ROOT/pages/reference.adoc`
  (name `foy-processor` and its zero-dependency status; the BCE), `usage.adoc`
  (`annotationProcessorPaths` snippet with `foy-processor` and `vauban-processor`),
  `internals.adoc` (the four tiers), `README.md` (module list).

- [ ] **Step 1:** Full TCK + regression diff + `reflection=0` check (Task 2.8 Step 5).
- [ ] **Step 2:** Docs as listed; the usage snippet:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths combine.children="append">
            <path>
                <groupId>io.vidocq.foy</groupId>
                <artifactId>foy-processor</artifactId>
                <version>${foy.version}</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

- [ ] **Step 3:** Open the follow-up for **Phase 2b** (separate plan):
  `foy-maven-plugin:generate` for external jars, and the `vidocq` PR adding
  `foy-processor` + `foy-cdi-vauban` to `vidocq-runtime-core-codegen`.
- [ ] **Step 4: Commit** — `docs: document build-time code generation (Phase 2 exit)`.
