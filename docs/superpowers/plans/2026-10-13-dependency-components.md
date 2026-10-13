# Dependency Web Components Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Foy's side of Vidocq/vidocq#246, tracked as foy#43. `vidocq:generate` runs `FoyWebComponentProcessor` with `javac -proc:only` over the compiled classes of dependency jars, so that a `@WebListener` shipped in a library such as Vaadin's `flow-server.jar` is discovered. Foy must do three things:
- run correctly over class files;
- declare a cheap selection hint and a marker that `vidocq:generate` can read without hardcoding Foy;
- write that marker.

**Architecture:** All the changes are in `foy-processor`, a build-time artifact:
- the processor visits each type once;
- a public `DependencyHints` class carries the hint and the marker constants;
- the processor writes the marker resource whenever it generates anything.

**Tech Stack:** Java 25, the `javax.annotation.processing` API, JUnit 5, and the existing `CompileHarness` test helper.

**Spec:**
- foy#43;
- vidocq#246, including its spike comment of 2026-10-13. That spike found that `-proc:only` over binary types already works for Foy, and that passing `Outer$Inner` as well as `Outer` fails with `FilerException: Attempt to recreate a file`.

## Global Constraints

- **Language:** English for code, Javadoc, comments, commit messages and Markdown. Write "Java Modules", never "JPMS".
- **Dependencies:** no new runtime dependency. `foy-processor` stays dependency-free.
- **TDD:** write the failing test first, run it, and see it fail.
- **Commits:**
  - `git commit -S -s`, Conventional Commits, explicit paths only.
  - Trailers: `Co-Authored-By: Claude <model> 5.5 <noreply@anthropic.com>`, then `Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi`.
- **Worktree:** work in `/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq-foy/714bb865-5c9f-4aac-a489-970c2a1d362e/scratchpad/wt-foy43`, on branch `pr/ybl/foy-dependency-components`. Never use the main foy checkout.
- **Maven:** run offline against the private repo: `./mvnw -ntp -o -Dmaven.repo.local=/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq-foy/714bb865-5c9f-4aac-a489-970c2a1d362e/scratchpad/m2 -Dmaven.repo.local.tail=$HOME/.m2/repository ...`.

## Rulings (made while planning; the maintainer approves them with the plan)

- **Hint and marker as constants.** The selection hint and the marker are public constants in `io.vidocq.foy.processor.DependencyHints`, not a Vidocq SPI implementation. The SPI that `-codegen` bundles implement is not designed yet (vidocq#246, step 2). The Foy codegen bundle in the vidocq repository reads these constants, so the values live in exactly one place. If vidocq#246 later settles on a different form, the bundle adapts; Foy keeps the constants.
- **Values:**
  - `RESOURCE_HINTS = List.of("META-INF/services/jakarta.servlet.ServletContainerInitializer", "META-INF/web-fragment.xml")`
  - `CLASS_BYTE_HINTS = List.of("jakarta/servlet/annotation/Web")`, a byte search over class files that matches `WebServlet`, `WebFilter`, `WebListener` and `WebInitParam`, plus `"jakarta/servlet/annotation/HandlesTypes"`.
  - `MARKER = "META-INF/io.vidocq.foy/generated"`
- **The marker.** The processor writes `MARKER` in its final round whenever it generated at least one component or one class-index entry. The content is a single line, `foy-processor <version>`. The version is read from the processor jar's `Implementation-Version` manifest attribute, or `unknown` when absent. A jar that already carries `MARKER` was processed, at its own build or by an earlier `vidocq:generate`, and is skipped. A Foy-built library therefore never gets a second set of components.
- **Merging service files and indexes** with those already in a jar is `vidocq:generate`'s job (spike rule 3). The processor keeps writing fresh files, which is all `javac` allows it to do.

## Review Focus

1. **Nested types.** When both `Outer` and `Outer$Inner` are passed as root elements, the processor generates one companion each, with no `FilerException`. The same holds when only `Outer` is passed.
2. **Source mode is unchanged.** The existing generation tests stay green, and the output of a normal source compile is byte-identical, apart from the new marker resource.
3. **Marker absent when there is nothing to do.** A compile that generates no component and no class-index entry writes no marker.
4. **Hints are documented** in the reference page, where vidocq maintainers will look for them.

---

### Task 1: Visit each type once (over sources and class files)

**Files:**
- Modify: `foy-processor/src/main/java/io/vidocq/foy/processor/FoyWebComponentProcessor.java`, in `visit` (~:62-85). Add a `Set<String> visited` holding the binary names (`Elements.getBinaryName`).
- Test: `foy-processor/src/test/java/io/vidocq/foy/processor/`, a new `ClassFileProcessingTest.java`. First read `CompileHarness.java`, and extend it with a `-proc:only` mode over class names if it lacks one.

- [ ] **Step 1: Write the failing tests.**
  - `processingCompiledClassesGeneratesTheCompanions`: compile `a.S`, a `@WebServlet` with a nested `@WebListener a.S.L`, into a temp directory, with `-proc:none`. Then run `javac -proc:only -processor io.vidocq.foy.processor.FoyWebComponentProcessor -cp <that dir + servlet api + foy-api> a.S`. Assert three things:
    - the generated sources `a/S$$FoyComponent.java` (or whatever names `ComponentSourceWriter` uses; read it) and the nested listener's companion exist;
    - the services file lists both;
    - the compiler reports no error.
  - `passingANestedTypeAsWellAsItsOwnerDoesNotRecreateAFile`: the same run, passing `a.S a.S$L`. Before the fix this fails with `FilerException: Attempt to recreate a file`.
- [ ] **Step 2: Run the tests to verify they fail.** Run `./mvnw ... -pl foy-processor test -Dtest=ClassFileProcessingTest`. The first test may already pass, because the spike says so; the second must fail.
- [ ] **Step 3: Implement.** In `visit`, return early when `!visited.add(binaryName(type))`.
- [ ] **Step 4: Run the tests to verify they pass.** Run the whole `foy-processor` suite, then `-pl foy-processor install`.
- [ ] **Step 5: Commit** with `fix(processor): visit each type once, so dependency classes can be processed with -proc:only (#43)`.

### Task 2: DependencyHints and the marker

**Files:**
- Create: `foy-processor/src/main/java/io/vidocq/foy/processor/DependencyHints.java`, a public final class with a private constructor, the three constants from the Rulings, and Javadoc explaining what `vidocq:generate` does with each.
- Modify: `foy-processor/src/main/java/module-info.java`. Export `io.vidocq.foy.processor` if it is not already exported, and say so.
- Modify: `FoyWebComponentProcessor.finish()`, to write `MARKER`.
- Test: `ClassFileProcessingTest`, plus an existing source-mode test.

- [ ] **Step 1: Write the failing tests.**
  - `theMarkerIsWrittenWhenComponentsAreGenerated`: after the `-proc:only` run from Task 1, `MARKER` exists in the class output and starts with `foy-processor `.
  - `noMarkerWhenNothingIsGenerated`: compile a plain class with no Servlet annotation, and check that `MARKER` is absent. Read `ClassIndexWriter` first: if it indexes every class, so that the class-index always has entries, then define the marker condition as "at least one generated component, or a non-empty class index", and pin whichever behaviour the index really has.
  - `dependencyHintsMatchTheServletAnnotations`: `CLASS_BYTE_HINTS` contains a prefix of the internal names of `WebServlet`, `WebFilter`, `WebListener` and `HandlesTypes`. Build each name as `Class.getName().replace('.', '/')` and assert that one of the hints is a prefix of it.
- [ ] **Step 2: Run the tests to verify they fail.**
- [ ] **Step 3: Implement.** In `finish()`, after the services file, write `MARKER` with `processingEnv.getFiler().createResource(CLASS_OUTPUT, "", MARKER, anyOrigin)` when something was generated. Read the version from `FoyWebComponentProcessor.class.getPackage().getImplementationVersion()`. Check that the foy-processor jar manifest carries `Implementation-Version`; if it does not, add `addDefaultImplementationEntries` in its pom's jar plugin configuration and say so.
- [ ] **Step 4: Run the tests to verify they pass.** Run the `foy-processor` suite, then a full `./mvnw ... install` from the foy root, because other modules run the processor.
- [ ] **Step 5: Commit** with `feat(processor): declare the dependency hints and write a processed marker (#43)`.

### Task 3: Documentation

- [ ] **Step 1:** In `docs/en/modules/ROOT/pages/reference.adoc`, in the section that describes `foy-processor` and the generated `$$FoyComponent` classes, add a short subsection, "Processing dependency jars". It explains four things:
  - the processor runs over compiled classes (`javac -proc:only` with top-level or nested binary names);
  - the `DependencyHints` constants and what each one selects;
  - the marker and its content;
  - that merging the service file and `class-index.list` with a jar's existing ones is the caller's job.

  Link vidocq#246.
- [ ] **Step 2: Commit** with `docs: describe processing dependency jars with foy-processor (#43)`.
