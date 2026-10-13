# Dispatch Batch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the open dispatch bugs in `foy/BUG.md`:
- BUG-20261009-01: a non-HTTP application wrapper is dropped by forward and include.
- BUG-20261009-03: an error page sees the failing request's paths.
- BUG-20261009-04: decoded paths leak into dispatch URIs.
- BUG-20261009-12: the charset of the dispatch query string.

**Architecture:** There are four fixes in `foy-core` (`dispatcher/`, `bridge/`, `async/`), one task each. The last task runs the official TCK classes these paths touch.

**Tech Stack:** Java 25, Jakarta Servlet 6.1, JUnit 5, Maven 3.9.16, and the official Servlet 6.1 TCK through `run-official-tck-servlet6.1.sh`.

**Spec:** the `BUG.md` entries listed above. Their "Hypothèse de cause" and fix directions are the requirements, except where a task rules differently (see "Rulings").

## Global Constraints

- **Language:** English for code, Javadoc, comments, commit messages and Markdown. Write "Java Modules", never "JPMS".
- **Dependencies:** no new runtime dependency.
- **TDD:** write the failing test first, run it, and see it fail for the expected reason.
- **Commits:** `git commit -S -s`, Conventional Commits, explicit paths only. The last paragraph is:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi
  ```
- **BUG.md:** each closed bug gets `**Statut** : FIXED (<short description>, <commit hash>)` plus an investigation line naming the pinning test. Keep the entry's own format.
- **Maven:** run it offline, `./mvnw -ntp -o -Dmaven.repo.local=/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq-foy/714bb865-5c9f-4aac-a489-970c2a1d362e/scratchpad/m2 -Dmaven.repo.local.tail=$HOME/.m2/repository ...`. If classes look stale (the IDE writes into `target/`), delete them.
- **Per-task check:** each task ends with `./mvnw ... -pl foy-core install` green.

## Rulings (made while planning; the maintainer approves them with the plan)

- **BUG-01:** follow Tomcat's `ApplicationDispatcher.wrapRequest`. The container's dispatch wrapper goes *under* the application's wrapper chain, through `ServletRequestWrapper.setRequest`, and is restored in a `finally`. The target receives the object the application passed in.
- **BUG-03:** an error page sees the location's path elements, as a forward would (§10.9.1): `getServletPath`, `getPathInfo`, `getRequestURI`, `getRequestURL` and `getHttpServletMapping`. The original values stay in the `jakarta.servlet.error.*` attributes, which are already set. No `forward.*` attributes are added, because the dispatch type is ERROR. This drops the `instanceof DefaultServlet` special case.
- **BUG-04:**
  - Dispatch URIs (the forwarded and async `getRequestURI`, and `include.request_uri`) are built by percent-encoding only the characters a URI path cannot carry. That covers space, `?`, `#`, `%` when it is not followed by two hex digits, controls and non-ASCII. Valid `%XX` escapes are kept as they are, because a dispatch path given as `/my%20file.txt` is not decoded (`forwardPathsAreNormalisedButNotDecoded`). Both spellings, `/my file.txt` and `/my%20file.txt`, then give `/ctx/my%20file.txt`.
  - A zero-argument `AsyncContext.dispatch()` passes its path and its query separately, so a decoded `?` in the path is never split off as a query.
- **BUG-12:** Foy decodes *every* query string as UTF-8: the request's own (`HttpServletRequestImpl:333`) and the dispatch ones. This is Tomcat's default (`URIEncoding=UTF-8`, `useBodyEncodingForURI=false`). Decoding only the dispatch query with the body charset would make `getParameter` disagree with itself between a request and its forward. The ruling:
  - Keep UTF-8 and document it.
  - Close the entry as `CLOSED (by design)`.
  - Fix the real defect found while planning: `AsyncDispatchRequest` has its own query parser with no guard against malformed escapes. It moves to the shared `DispatchParameters.View`.

## Review Focus

1. **Wrapper chain restore (BUG-01).** `setRequest`/`setResponse` must be restored in a `finally`, even when the target throws or the forward closes the response. Nested dispatches must not leave a container wrapper inside the application's chain.
2. **Error page path elements (BUG-03).** A static error page (`/HTMLErrorPage.html`) must still be found and served by the default servlet. The error attributes must keep the original request's values.
3. **Double encoding (BUG-04).** A path that is already percent-encoded must never be encoded twice. A path with a literal space or `?` must be encoded exactly once.
4. **Zero-argument dispatch after a forward.** `canonicalDispatchPath` currently strips the context from `getRequestURI()` of `ForwardedRequest` and `AsyncDispatchRequest`. Once those URIs are encoded, it must read the target's own path instead.
5. **Official TCK.** `ErrorPageTests`, `ErrorPage1Tests`, `DispatchTests`, both `RequestDispatcherTests`, `ServletRequestWrapperTests`, `ServletResponseWrapperTests`, `AsyncContextTests`, `AsyncTests` and `EncodingTests` all pass today (`foy-tck/tck-baseline.txt`). They must still pass.

---

### Task 1: Keep a non-HTTP application wrapper across forward and include (BUG-20261009-01)

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/dispatcher/RequestDispatcherImpl.java`. The relevant code is `forward` (~:84-104), `include` (~:128-140), `unwrapHttp`/`unwrapHttpResponse` (~:178-194) and the `Invoker` interface (~:54).
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/ChappeServletBridge.java`, the `invoke` overloads (~:1013-1027), which implement `Invoker`.
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/dispatcher/DispatcherSemanticsTest.java`
- Modify: `foy/BUG.md`

**Interfaces:**
- `Invoker.invoke(DispatchTarget target, ServletRequest request, ServletResponse response, DispatcherType type)`. It widens from the HTTP types. `ChappeServletBridge.invoke` already finds the `HttpServletRequestImpl` by walking the chain (~:1027). Keep that walk, and make it work from a non-HTTP outer object.
- A new private helper in `RequestDispatcherImpl`:
  ```java
  /** The wrapper whose wrapped request is the first HTTP request of the chain, or null when the outer request is HTTP. */
  private static ServletRequestWrapper lowestNonHttpWrapper(ServletRequest outer)
  ```
  Write the response twin, `lowestNonHttpResponseWrapper`, the same way.

- [ ] **Step 1: Write the failing tests** in `DispatcherSemanticsTest`, using its lambda `servlet(Body)` style and its bridge-level `start` helper.
  - `aPlainRequestWrapperPassedToIncludeReachesTheTarget`: servlet `/c` calls
    ```java
    req.getRequestDispatcher("/t").include(new ServletRequestWrapper(req) {
        @Override public String getParameter(String n) { return "w"; } }, resp)
    ```
    Servlet `/t` writes `req.getParameter("p") + "|" + req.getDispatcherType() + "|" + (req instanceof HttpServletRequest)`. The expected body is `w|INCLUDE|false`. Before the fix the parameter is `null`, the type INCLUDE and the HTTP check `true`.
  - `aPlainRequestWrapperPassedToForwardReachesTheTarget`: the same with `forward`. The expected body is `w|FORWARD|false`.
  - `aPlainResponseWrapperPassedToIncludeReachesTheTarget`: `/c` passes a `ServletResponseWrapper` whose `getWriter()` returns a writer that prefixes every `write(String)` with `[`. The target writes `x`, and the expected body contains `[x`.
  - `theApplicationChainIsRestoredAfterTheDispatch`: after `include` returns, `/c` checks that `wrapper.getRequest() == req` and writes `restored`. Also cover the case where the target throws: catch the `ServletException` in `/c`, then check again.
  - `anHttpWrapperIsStillPassedAsBefore`: an `HttpServletRequestWrapper` overriding `getParameter` still reaches the target, and the target sees `getDispatcherType() == FORWARD`. This regression guard may already pass.

- [ ] **Step 2: Run the tests to verify they fail.** Run `./mvnw ... -pl foy-core test -Dtest=DispatcherSemanticsTest`. The plain-wrapper tests fail with `null|...|true`.

- [ ] **Step 3: Implement** in `forward` and `include`. Leave the existing logic in place when the outer request (or response) is HTTP. Otherwise:

```java
HttpServletRequest http = unwrapHttp(request);            // unchanged helper, first HTTP request
ServletRequestWrapper hook = lowestNonHttpWrapper(request); // wrapper whose getRequest() == http, or null
HttpServletRequest dispatched = new ForwardedRequest(http, target);   // IncludedRequest for include
ServletRequest passed = request;
if (hook != null) hook.setRequest(dispatched); else passed = dispatched;
try {
    invoker.invoke(target, passed, passedResponse, FORWARD);
} finally {
    if (hook != null) hook.setRequest(http);
}
```

  Do the same for the response with `IncludedResponse`, which applies to include only. Forward passes the response chain as is: today `forward` uses `unwrapHttpResponse(res)` for the invoke, so pass the outer response object instead. Keep every existing step: the committed-response check, the buffer reset, `close(response)`, and the `insideInclude` / `asyncEntered` checks. Those walk the chain, so they work on the outer objects.

- [ ] **Step 4: Widen `Invoker` and `ChappeServletBridge.invoke`** to `ServletRequest`/`ServletResponse`. Every other caller, such as the error dispatch and the async dispatch, still passes HTTP objects and compiles unchanged. Inside `invoke`, the filter chain and `servlet.service` already take `ServletRequest`. Any `HttpServletRequest`-only use there must go through the impl found by the chain walk.

- [ ] **Step 5: Run the tests to verify they pass.** Run `DispatcherSemanticsTest`, `ServletDispatcherEndToEndTest` and `AsyncSupportedDispatchTest`, then `-pl foy-core install`.

- [ ] **Step 6: Update BUG.md.** Set BUG-20261009-01 to FIXED, with the hash and a line naming the pinning tests.

- [ ] **Step 7: Commit** with `fix(dispatcher): pass a non-HTTP application wrapper through forward and include (BUG-20261009-01)`.

---

### Task 2: Give an error page the location's path elements (BUG-20261009-03)

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/ChappeServletBridge.java`, at `errorTargetRequest` (~:731-737) and its call (~:996-999).
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/ErrorDispatchRequest.java`. It is package-private and replaces the anonymous wrapper.
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/error/ErrorPageDispatchTest.java`
- Modify: `foy/BUG.md`

**Interfaces:**
- Consumes `DispatchTarget`, which provides `servletPath()`, `pathInfo()` and `path()`. Use the accessors `ForwardedRequest` uses for its mapping (`getHttpServletMapping`); look at `ForwardedRequest` (~:60-120) and mirror it.
- Produces `final class ErrorDispatchRequest extends HttpServletRequestWrapper`, with the constructor `(HttpServletRequest original, DispatchTarget target)`. It overrides `getServletPath`, `getPathInfo`, `getRequestURI` (the context path plus the location path, through the Task 3 encoder if Task 3 has landed, otherwise the context path plus `target.path()`), `getRequestURL` and `getHttpServletMapping`. `getQueryString` and the parameters stay the original request's.

- [ ] **Step 1: Write the failing tests** in `ErrorPageDispatchTest`, using its `start()`, which maps `/t` and `/err`.
  - `anErrorPageSeesTheLocationsServletPath`: `/t` calls `resp.sendError(500)`, and the error page `/err` writes `req.getServletPath() + "|" + req.getRequestURI() + "|" + req.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)`. Expected: `/err|/err|/t`, adjusted for the test's context path, which you read from `start()`. Before the fix the result is `/t|/t|/t`.
  - `anErrorPageAfterAnExceptionSeesTheLocationsPaths`: the same, but `/t` throws a `ServletException`.
  - `theHttpServletMappingOfAnErrorPageIsTheLocations`: the error page writes `req.getHttpServletMapping().getPattern()`, and the expected value is `/err`.
  - Keep `exceptionInsideForwardReportsOriginalRequestUriAndServletName` green. It asserts attributes, which must not change.

- [ ] **Step 2: Run the tests to verify they fail.** Run `./mvnw ... -pl foy-core test -Dtest=ErrorPageDispatchTest`.

- [ ] **Step 3: Implement `ErrorDispatchRequest`.** Then:
  - Make `errorTargetRequest` return `new ErrorDispatchRequest(req, target)` for every target.
  - Remove the `instanceof DefaultServlet` branch.
  - Update the Javadoc to cite Servlet 6.1 §10.9.1.

- [ ] **Step 4: Run the tests to verify they pass.** Run `ErrorPageDispatchTest`, `DefaultServletTest` (static error pages `/HTMLErrorPage.html` and `/WEB-INF/views/error.html`), `RequestPathCanonicalisationTest` and `AsyncSupportedDispatchTest`. Then run `-pl foy-core install`.

- [ ] **Step 5: Update BUG.md.** Set BUG-20261009-03 to FIXED.

- [ ] **Step 6: Commit** with `fix(bridge): give an error page the path elements of its location (BUG-20261009-03)`.

---

### Task 3: Encode dispatch URIs and keep a decoded '?' in a zero-argument dispatch (BUG-20261009-04)

**Files:**
- Create or modify the home of the shared encoder: `foy-core/src/main/java/io/vidocq/foy/internal/http/RequestPaths.java` if it exists. Otherwise put it in the package that already holds the path helpers; grep for `canonical` in `internal/http` and `internal/bridge`.
- Modify: `ChappeServletBridge.java`. Its private `encodePath` (~:655-668), used for the directory redirect, moves into the shared helper or is kept beside it. Also modify the async-dispatch split (~:809-846).
- Modify: `bridge/ForwardedRequest.java` (`getRequestURI` ~:102-105), `bridge/IncludedRequest.java` (`INCLUDE_REQUEST_URI` ~:58) and `bridge/AsyncDispatchRequest.java` (`getRequestURI` ~:112-115).
- Modify: `async/AsyncContextImpl.java` (zero-argument `dispatch()` ~:125-140) and `HttpServletRequestImpl.canonicalDispatchPath` (~:144-165).
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/bridge/RequestPathCanonicalisationTest.java`, plus a unit test for the encoder.
- Modify: `foy/BUG.md`

**Interfaces:**
- Produces `static String encodeUriPath(String path)`. It percent-encodes, as UTF-8 bytes, every character that is not an RFC 3986 `pchar` or `/`. It keeps a `%` that is followed by two hex digits, and encodes any other `%` as `%25`. It is idempotent: `encodeUriPath(encodeUriPath(p)).equals(encodeUriPath(p))`.
- The directory redirect keeps its current behaviour. If its encoder differs (it encodes `;` and every `%`), keep it as it is and add the new one beside it. Do not change the redirect.
- `AsyncContextImpl` passes the path and the query separately to the bridge. Add an internal method, for example `dispatch(ServletContext ctx, String path, String query)`, or a record, and keep the public `dispatch(String)` splitting on `?` as the API requires.
- `canonicalDispatchPath` reads the dispatch wrapper's target path, through a package-private `target()` accessor like the one `IncludedRequest` already has (~:67). It no longer reads `getRequestURI()`.

- [ ] **Step 1: Write the failing tests.**
  - An encoder unit test:
    - `/my file.txt` → `/my%20file.txt`
    - `/my%20file.txt` unchanged
    - `/a?b` → `/a%3Fb`
    - `/café` → `/caf%C3%A9`
    - `/100%` → `/100%25`
    - `/x%2` → `/x%252`
    - idempotence on each of the above.
  - In `RequestPathCanonicalisationTest`, which uses its `Paths` servlet printing SP/PI/URI/URL:
    - Forward to `/my file.txt`: the target's `getRequestURI()` is `/ctx/my%20file.txt`.
    - Include of `/my file.txt`: the `jakarta.servlet.include.request_uri` attribute is `/ctx/my%20file.txt`.
    - A servlet mapped `/a?b/*` (the decoded pattern; use the existing raw-socket `Reply` helper) receives `GET /ctx/a%3Fb/x`, calls `startAsync()` and then `dispatch()`. The async target's servlet path is `/a?b`, its path info is `/x`, its query string is null, and its `getRequestURI()` is `/ctx/a%3Fb/x`. Before the fix the dispatch resolves `/ctx/a` with the query `b/x`.
    - `zeroArgumentAsyncDispatchOfAWrappedRequestUsesTheCanonicalPath` and `forwardPathsAreNormalisedButNotDecoded` still pass, and so does a zero-argument dispatch after a forward.

- [ ] **Step 2: Run the tests to verify they fail.**

- [ ] **Step 3: Implement.** Add the encoder, use it in the three URI sites, apply the path/query split, and fix `canonicalDispatchPath`.

- [ ] **Step 4: Run the tests to verify they pass.** Run `RequestPathCanonicalisationTest`, `DispatcherSemanticsTest`, `AsyncSupportedDispatchTest` and `ServletDispatcherEndToEndTest`, then `-pl foy-core install`.

- [ ] **Step 5: Update BUG.md.** Set BUG-20261009-04 to FIXED.

- [ ] **Step 6: Commit** with `fix(dispatcher): encode dispatch URIs and keep a decoded '?' in the async dispatch path (BUG-20261009-04)`.

---

### Task 4: One query-string decoder for every dispatch (BUG-20261009-12)

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/AsyncDispatchRequest.java`. Remove `parseQuery` (~:57-72) and use `DispatchParameters.View`, as `ForwardedRequest` (~:61-62) does.
- Test: the async dispatch test class that covers query parameters. Grep for `dispatch("/` with `?` in foy-core tests. If there is none, use `RequestPathCanonicalisationTest`.
- Modify: `foy/BUG.md` and `docs/en/modules/ROOT/pages/reference.adoc` (or `usage.adoc`, wherever request parameters and encodings are described).

- [ ] **Step 1: Write the failing tests.**
  - `aMalformedEscapeInAnAsyncDispatchQueryIsSkipped`: `AsyncContext.dispatch("/t?a=%ZZ&b=1")`. The target sees `getParameter("b") == "1"`, and no exception reaches the client. Before the fix, `URLDecoder.decode` throws `IllegalArgumentException`. Assert the current outcome first: if it is not a failure, then document that the test pins the merge order instead.
  - `asyncDispatchQueryParametersPrecedeTheOriginalOnes`: the original request is `?a=1`, and it dispatches to `/t?a=2`. `getParameterValues("a")` is `[2, 1]`, which is the same rule as forward (§9.1.1). It may already pass, in which case it is a guard.
- [ ] **Step 2: Run the tests to verify they fail.**
- [ ] **Step 3: Implement.** Use `new DispatchParameters.View(super::getParameterMap, query, StandardCharsets.UTF_8)`, and route `getParameter`, `getParameterValues`, `getParameterNames` and `getParameterMap` through it, as `ForwardedRequest` does.
- [ ] **Step 4: Run the tests to verify they pass.** Run the focused tests, then `-pl foy-core install`.
- [ ] **Step 5: Update the docs.** Add one sentence where parameters are documented: query strings, both the request's own and those of forward, include and async dispatch, are decoded as UTF-8, and the request character encoding applies to the form body only.
- [ ] **Step 6: Update BUG.md.** BUG-20261009-12 becomes `**Statut** : CLOSED (by design: every query string is decoded as UTF-8, as the request's own; <hash>)`. Add an investigation line explaining the consistency argument, and name the async parser fix.
- [ ] **Step 7: Commit** with `fix(bridge): decode an async dispatch query with the shared dispatch parser (BUG-20261009-12)`.

---

### Task 5: Documentation, official TCK subset and full build

- [ ] **Step 1: Update the docs.** Add one sentence per changed behaviour, only where a page already covers the area (`usage.adoc`, `reference.adoc`, `internals.adoc`):
  - A non-HTTP wrapper passed to a dispatcher reaches the target.
  - An error page sees its location's path elements, and the original values stay in the error attributes.
  - Dispatch URIs are percent-encoded.

  Grep for "error page", "errorTargetRequest", "DefaultServlet" and "include.request_uri" to find a stale sentence about the default-servlet special case, and remove it.
- [ ] **Step 2: Run the full build.** Run `./mvnw ... install` from the foy root, and report the test count.
- [ ] **Step 3: Run the official TCK subset.** Run it from the foy root:
  ```bash
  ./run-official-tck-servlet6.1.sh --no-install -Dtest='ErrorPageTests,ErrorPage1Tests,DispatchTests,RequestDispatcherTests,ServletRequestWrapperTests,ServletResponseWrapperTests,AsyncContextTests,AsyncTests,EncodingTests,FilterRequestDispatcherTests,DispatcherTypeTests'
  ```
  Read the script header first, to check that it accepts a comma list in `-Dtest`. If it does not, run one class at a time. Compare the results with `foy-tck/tck-baseline.txt`. Every class must have 0 failures. If a class regresses, stop and report the failing tests with their messages. Do not edit the baseline.
- [ ] **Step 4: Commit** the doc changes with `docs: describe wrapper-preserving dispatch, error page paths and encoded dispatch URIs`.
