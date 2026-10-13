# Wrapper and Error-Async Batch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close two entries in `foy/BUG.md`:
- BUG-20261013-03: an HTTP application wrapper sits above the dispatch wrapper, not under it.
- BUG-20261013-04: an async cycle started in an error page never runs.

**Architecture:** Two fixes in `foy-core`:
- The dispatcher hooks its dispatch wrapper under the *lowest application wrapper of any kind*. It does that above the container's own request, as Tomcat's `ApplicationDispatcher.wrapRequest` does.
- `startAsync()` is refused during an ERROR dispatch.

The batch closes with a full official TCK run, compared class by class with the run before the change.

**Tech Stack:** Java 25, Jakarta Servlet 6.1, JUnit 5, Maven 3.9.16, and the official Servlet 6.1 TCK.

**Spec:** the two `BUG.md` entries above. This branch is stacked on `pr/ybl/foy-dispatch-batch` (foy#44), which introduced both entries and the non-HTTP hook this plan generalises.

## Global Constraints

- **Language:** English for code, Javadoc, comments, commits and Markdown. Write "Java Modules", never "JPMS".
- **Dependencies:** no new runtime dependency.
- **TDD:** write the failing test first, run it, and see it fail for the expected reason.
- **Commits:** `git commit -S -s`, Conventional Commits, explicit paths only. The trailers are `Co-Authored-By: Claude <the model you run as> 5.5 <noreply@anthropic.com>`, then `Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi`.
- **BUG.md:** record the closed entry in a follow-up commit, as `- Status: FIXED (<short description>, <hash>)` with an investigation line naming the pinning tests. Keep the entry's own format.
- **Maven:** run it offline against the private repository, `./mvnw -ntp -o -Dmaven.repo.local=/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq-foy/714bb865-5c9f-4aac-a489-970c2a1d362e/scratchpad/m2 -Dmaven.repo.local.tail=$HOME/.m2/repository ...`. If classes look stale, delete them.
- **TCK:** the official TCK script runs `-pl foy-tck` alone, so it resolves the Foy jars from the local repository. It **must** be given the same `-Dmaven.repo.local=... -Dmaven.repo.local.tail=...`, after an `install` of the branch into that private repository. Otherwise it tests whatever Foy jars other sessions installed into `~/.m2`.

## Rulings (made while planning; the maintainer approves them with the plan)

- **BUG-13-03:** the *container request* is either `HttpServletRequestImpl` or one of Foy's dispatch wrappers (`ForwardedRequest`, `IncludedRequest`, `AsyncDispatchRequest`, `ErrorDispatchRequest`). The dispatcher builds its dispatch wrapper over the first container request of the chain. It then puts it under the application wrapper directly above that request, whatever its type, using `setRequest`, and restores it in a `finally`. When the outer request *is* the container request, the dispatch wrapper is passed directly, as today. The same rule applies to responses, where the container response is `HttpServletResponseImpl` or `IncludedResponse`.
  - A package-private marker in `io.vidocq.foy.internal.bridge`, an empty `interface ContainerRequest` / `ContainerResponse`, is **not** used. The dispatcher lives in `internal.dispatcher`. Instead, recognise the concrete types through a small static helper in `RequestDispatcherImpl`: `isContainerRequest(ServletRequest)`, `isContainerResponse(ServletResponse)`. `ErrorDispatchRequest` is package-private in `bridge`; make it public with a package-private constructor, or recognise it by a public accessor. The implementer picks the smaller change and says which.
- **BUG-13-04:** `startAsync()` (both overloads) throws `IllegalStateException("startAsync() in an error page")` during an ERROR dispatch. Today the cycle is silently never driven. Driving it would mean running async cycles after the error handling, a larger change in the bridge's request loop with no TCK or application need identified. Cost if wrong: an application that wants async in an error page gets an exception instead of an empty response, and the spec does not require the container to support it.

## Review Focus

1. **Identity.** The target receives exactly the object the application passed (`assertSame`), for both an HTTP and a non-HTTP wrapper, on forward and include.
2. **Overrides.** An application `HttpServletRequestWrapper` overriding `getParameter` is seen by the target. The forward path methods (`getServletPath`, `getRequestURI`) still come from the dispatch wrapper, through the application wrapper's delegation, as long as the application does not override them.
3. **Chain restore.** After forward or include, and when the target throws, the application wrapper wraps the original request again. Nested dispatches unwind in LIFO order.
4. **Filters that wrap.** A FORWARD- or INCLUDE-mapped filter that wraps the request it receives, then a second dispatch from the target: the chains still compose.
5. **TCK.** The full official TCK run after the change has no class with more failures than the run before it. The before-run report directory is `/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq-foy/714bb865-5c9f-4aac-a489-970c2a1d362e/scratchpad/tck-reports-before`.

---

### Task 1: Put the dispatch wrapper under any application wrapper (BUG-20261013-03)

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/dispatcher/RequestDispatcherImpl.java`. Today `forward` sits at ~:93-126, `include` at ~:150-179, and `lowestNonHttpWrapper` / `lowestNonHttpResponseWrapper` at ~:181-207. `unwrapHttp` and `unwrapHttpResponse` are at ~:245-261.
- Possibly modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/ErrorDispatchRequest.java`, for its visibility only.
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/dispatcher/DispatcherSemanticsTest.java`
- Modify: `foy/BUG.md`

**Interfaces:**
- Replace `lowestNonHttpWrapper` with `private static ServletRequestWrapper hookAbove(ServletRequest outer)`. It returns the application wrapper whose `getRequest()` is the first container request, or null when `outer` is itself a container request.
- Replace `lowestNonHttpResponseWrapper` with the twin, `hookAbove(ServletResponse outer)`.
- Add `private static HttpServletRequest containerRequest(ServletRequest outer)`. It returns the first container request of the chain, or null.
- Add `containerResponse(ServletResponse)`, which returns the first `HttpServletResponseImpl` or `IncludedResponse`, or null.

- [ ] **Step 1: Write the failing tests** in `DispatcherSemanticsTest`.
  - `anHttpWrapperPassedToForwardIsTheObjectTheTargetReceives`: `/c` forwards `w = new HttpServletRequestWrapper(req) { getParameter -> "h" }`. `/t` records `req == w` and writes `req.getParameter("p") + "|" + req.getDispatcherType() + "|" + req.getServletPath()`. Expected: identity `true`, body `h|FORWARD|/t`. Before the fix, identity is false and the body is `null|FORWARD|/t`.
  - `anHttpWrapperPassedToIncludeIsTheObjectTheTargetReceives`: the same with include. The expected body is `h|INCLUDE|/c`, because during an include the path methods are the includer's. `jakarta.servlet.include.servlet_path` is `/t`; assert it too.
  - `anHttpResponseWrapperPassedToIncludeIsTheObjectTheTargetReceives`: identity of an `HttpServletResponseWrapper` whose `getWriter()` prefixes `[`. The body is exactly `[x`.
  - `theHttpWrapperChainIsRestoredAfterForwardAndInclude`: afterwards `w.getRequest() == req`, including when the target throws.
  - `aFilterWrappingOnForwardComposesWithASecondDispatch`: a FORWARD-mapped filter wraps the request with an `HttpServletRequestWrapper`. The forward target then includes `/i`, which writes its `getDispatcherType()`. Expected: `INCLUDE`, with no exception, and the forward completes. Use the existing filter helpers (`startWith(List.of(filters), mappings)`).
  - Update `anHttpWrapperIsStillPassedAsBefore`, which pins the old placement through `getHeader`. Rename it to `anHttpWrapperDelegatesTheDispatchPathsToTheDispatchWrapper` and assert that the target sees `getServletPath() == "/t"` through the application wrapper.

- [ ] **Step 2: Run the tests to verify they fail.** Run `./mvnw ... -pl foy-core test -Dtest=DispatcherSemanticsTest`.

- [ ] **Step 3: Implement.** `forward`:

```java
HttpServletRequest req = containerRequest(request);
HttpServletResponse res = containerResponseOrHttp(response); // existing unwrapHttpResponse is fine for forward checks
if (req == null || res == null) throw new ServletException("non-HTTP dispatch");
// committed check, resetBuffer: unchanged
ForwardedRequest dispatched = new ForwardedRequest(req, target);
ServletRequestWrapper hook = hookAbove(request);
if (hook == null) {
    invoker.invoke(target, dispatched, response, DispatcherType.FORWARD);
} else {
    hook.setRequest(dispatched);
    try { invoker.invoke(target, request, response, DispatcherType.FORWARD); }
    finally { hook.setRequest(req); }
}
```

  `include` follows the same shape, with `IncludedRequest` over `containerRequest(request)` and `IncludedResponse` over `containerResponse(response)`. Both are hooked with `hookAbove` and restored in nested `finally` blocks (keep the current nesting). Keep the `close`, `asyncEntered` and `insideInclude` logic. Update the Javadoc and comments: say that every application wrapper is kept, citing §6.2.2 and Tomcat.

- [ ] **Step 4: Run the tests to verify they pass.** Run `DispatcherSemanticsTest`, `ServletDispatcherEndToEndTest`, `AsyncSupportedDispatchTest`, `RequestPathCanonicalisationTest` and `ErrorPageDispatchTest`, then `-pl foy-core install`.

- [ ] **Step 5: Run the official TCK subset against the private repository.** Use the subset that exercises wrappers and dispatch:

```bash
./run-official-tck-servlet6.1.sh --no-install -Dmaven.repo.local=<private m2> -Dmaven.repo.local.tail=$HOME/.m2/repository \
  -Dtest='DispatchTests,RequestDispatcherTests,ServletRequestWrapperTests,ServletResponseWrapperTests,FilterRequestDispatcherTests,DispatcherTypeTests,AsyncContextTests,AsyncTests,ErrorPageTests,ErrorPage1Tests'
```

  Every class must report 0 failures and 0 errors. If one regresses, stop and report the failing test names with their messages, taken from `foy-tck/target/surefire-reports`.

- [ ] **Step 6: Commit** with `fix(dispatcher): keep every application wrapper above the dispatch wrapper (BUG-20261013-03)`. Then record BUG.md in a second commit.

---

### Task 2: Refuse startAsync() in an error page (BUG-20261013-04)

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java`, in `startAsync(ServletRequest, ServletResponse)` (~:1299-1331). The no-argument overload delegates to it.
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/error/ErrorPageDispatchTest.java`
- Modify: `foy/BUG.md` and the docs (`usage.adoc`, where the async rules are listed).

- [ ] **Step 1: Write the failing test.** Name it `startAsyncInAnErrorPageThrowsIse`. `/t` calls `sendError(500)`. The error page is mapped async-supported and calls `req.startAsync()` inside `try`. It catches the `IllegalStateException` and writes `ise`. Expected body: `ise`. Before the fix the body is empty, because the cycle starts and is never driven. Add a second test for the two-argument `startAsync(req, res)`.
- [ ] **Step 2: Run it to verify it fails.**
- [ ] **Step 3: Implement.** In `startAsync(ServletRequest, ServletResponse)`, after the `asyncSupported` check:

```java
// The bridge drives async cycles before the error handling: a cycle opened by an error page would
// never run (BUG-20261013-04).
if (dispatcherType == DispatcherType.ERROR) throw new IllegalStateException("startAsync() in an error page");
```

  The field is named `dispatcherType`, at ~:809; check the name.
- [ ] **Step 4: Run the tests.** Run `ErrorPageDispatchTest`, `AsyncSupportedDispatchTest` and `RequestListenersAroundErrorPagesTest`, then `-pl foy-core install`.
- [ ] **Step 5: Update the docs.** Add one sentence to `usage.adoc` where the async rules are listed: `startAsync()` throws `IllegalStateException` in an error page.
- [ ] **Step 6: Commit** with `fix(bridge): refuse startAsync() in an error page (BUG-20261013-04)`. Then record BUG.md in a second commit.

---

### Task 3: Full build and full official TCK, compared with the run before

- [ ] **Step 1: Run the full build.** Run `./mvnw ... clean install` from the foy root, which installs into the private repository. Report the test count.
- [ ] **Step 2: Run the full official TCK.** Run `./run-official-tck-servlet6.1.sh --no-install --all -Dmaven.repo.local=<private m2> -Dmaven.repo.local.tail=$HOME/.m2/repository`, and save `foy-tck/target/surefire-reports`.
- [ ] **Step 3: Compare.** For every class, compare tests, failures, errors and skipped with `/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq-foy/714bb865-5c9f-4aac-a489-970c2a1d362e/scratchpad/tck-reports-before`. Write the comparison table into the report. List every class whose failures or errors grew, with the failing test names and messages. Report every class whose failures dropped too.
- [ ] **Step 4: Update the docs.** In `usage.adoc`, reword the sentence about wrapper-preserving dispatch: every application wrapper, HTTP or not, is now passed to the target.
