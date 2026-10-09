# Phase 4 — Request/Response and Session Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Foy's request, response, dispatcher, error-page and session behaviour matches Servlet 6.1 §3, §5, §7, §9, §10.10, §10.9 and §12.2, including a container default servlet and welcome files, lifting the TCK from 1587 towards 1648/1714.

**Architecture:** local fixes in `HttpServletResponseImpl`, `CookieCodec`, the error-dispatch path of `ChappeServletBridge` and `SessionManager`; a mapping model (`DispatchTarget` carries the `HttpServletMapping` data) shared by the request and its dispatch wrappers; dispatcher semantics rebuilt around one parameter-merge helper and attribute rules per dispatch kind; filter mappings split into URL-pattern and servlet-name kinds resolved against the target servlet; a container default servlet appended at `"/"` when the application maps none, then welcome-file resolution in front of the dispatcher.

**Tech Stack:** Java 25, Jakarta Servlet 6.1, foy-core / foy-chappe, JUnit 5, foy-core end-to-end tests (`TestServerLauncher` pattern), official Servlet TCK via Arquillian.

**Spec:** `docs/superpowers/plans/2026-10-07-servlet-completion-roadmap.md` §"Phase 4", §"Phase 3 exit / follow-ups", the Phase 1 follow-ups line "Phase 4"; Jakarta Servlet 6.1 §3.5–3.8, §5.1–5.7, §7.1–7.3, §9.1–9.7, §10.9, §10.10, §12.1–12.3. Evidence: the TCK triage below (research of 2026-10-09).

## Evidence (Phase 3 exit, 1587/1714)

127 failures: security 37 (Phase 6), streaming/trailers/upgrade/flush timing 10 (Phase 5), multipart 8 (Phase 7), HTTP/2 push 7, JSP/TLD 4 — and **61 Phase 4 candidates**:

| Cluster | Tests | Root cause (file:line at `5644a68`) |
|---|---:|---|
| C1 default servlet | 8 | none exists: unmapped paths 404 (`ChappeServletBridge.java:132-145`), `getRequestDispatcher` → `RequestDispatcherImpl.notFound` (no filter chain). `filterrequestdispatcher` ×4 fails because the forward/include target `/dummy.html` resolves to nothing (NOT because of BUG-20261008-02), `multifiltermapping` ×3, `HttpSessionTests.expireHttpSessionTest` (GET `/index.html`) |
| C2 welcome files | 2 | parsed and stored, never used (`WelcomeFilesTests.partialfound1`: `/foo` → redirect `/foo/` → `foo/index.html`; `FragmentTests.welcomefileTest`: welcome `TestServlet4` resolved against servlet mappings) |
| C3 `getHttpServletMapping` | 9 | `HttpServletRequestImpl.java:502-504` throws UOE; `DispatchTarget` carries no match value/pattern/`MappingMatch`; `DispatchResolver.servletPathFor` (`:63`) gives `""` for DEFAULT; `UrlPatternMatcher.precedence` (`:103-110`) ranks EMPTY below DEFAULT; `*.mapping` dispatch attributes never set |
| C4 include parameters | 14 | `IncludedRequest` does not merge the include query string (§9.1.1) — 9 `spec.requestdispatcher` + 5 `spec.srlistener` |
| C5 dispatcher attributes | 4 | named dispatch sets `include.*`/`forward.*` and fakes paths (`RequestDispatcherImpl.java:95-121`, `DispatchResolver.resolveByName:68-75`); nested forward overwrites `forward.*`; `ForwardedRequest` lacks `getRequestURL` |
| C6 error pages | 6 | `res.clearErrorState()` (`ChappeServletBridge.java:316`) erases the message before it is read (3); exception attributes taken from the wrapping `ServletException`, not the matched root cause (3) |
| C7 response | 9 | `getHeaders` returns an immutable copy (`HttpServletResponseImpl.java:171`, 4); header/status mutations accepted after commit, `sendRedirect` does not reset the buffer nor close output (5) |
| C8 session | 5 | `changeSessionId` UOE (`HttpServletRequestImpl.java:554-558`, 2); `Expires` only with `Max-Age=0` (`CookieCodec.java:77-90`, 2); harness serves each WAR of a test on its own port (`HttpSessionxTests`, 1) |
| C9 default charset | 2 | `HttpServletResponseImpl.getCharacterEncoding` (`:225-228`) returns null instead of ISO-8859-1 |
| C10 legacy descriptors | 2 | 2.2 DOCTYPE rejected (`WebXmlParser.java:74`), pre-2.3 url-pattern without leading `/` rejected |

Not exercised by any failing TCK test (Foy's own tests): session expiry reaper firing `sessionDestroyed`, `setSessionTimeout` from an SCI, tracking modes + URL rewriting, `Secure` cookie on secure requests, default-servlet conditional GET/ranges/HEAD, `getRealPath`, `getVirtualServerName`, request ids / `ServletConnection`, `error.query_string`, response closed after forward, include attributes scoped, BUG-20261008-02 (servlet-name filter mappings), `fireContextDestroyed` isolation.

## Global Constraints

- Runtime modules keep zero dependency beyond Jakarta APIs + chappe/vauban.
- Static codegen doctrine: no new `getAnnotation(` / `getDeclaredConstructor(` / `Class.forName(` in foy-core main outside the `NoProductReflectionTest` allow-list.
- Java Modules: say "Java Modules", never "JPMS".
- Virtual threads for anything scheduled (session reaper): `Executors.newVirtualThreadPerTaskExecutor()` or a virtual-thread `ScheduledExecutorService` factory; no platform thread pool without a documented reason.
- English everywhere; Conventional Commits; `git commit -S -s`; trailers `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi`.
- Gate (phase exit): full TCK ≥ 1587/1714, zero per-class regression against `foy-tck/tck-baseline.txt`, `reflection=0` in every `tiers=Stats` line; target ≥ 1640.

## Review Focus

1. **A path that maps to no servlet when the application also has no `/` mapping** — served by the default servlet (200 for an existing static resource, 404 otherwise), filters mapped to that path run, and `getServletRegistrations()` does not list the container default servlet (Task 4.5 tests).
2. **`WEB-INF/` and `META-INF/` (any case, any encoding Foy accepts) requested over HTTP** — 404 from the default servlet, never the file (Task 4.5 test `protectedTreesAre404`).
3. **A named dispatcher forward/include** — no `forward.*`/`include.*` attribute set, paths unchanged, servlet-name filters of the target applied for the matching dispatcher type (Task 4.4 tests).
4. **`changeSessionId()` with attributes and listeners** — attributes preserved, old id invalid, `HttpSessionIdListener.sessionIdChanged` fired once, new cookie emitted, `changeSessionId()` without a session → `IllegalStateException` (Task 4.7 tests).
5. **A response committed by `flushBuffer()`** — later `setHeader`/`setStatus` ignored, `sendError`/`sendRedirect` throw `IllegalStateException`, `reset()` throws `IllegalStateException` (Task 4.1 tests).

---

### Task 4.1: Response fixes — headers, commit, redirect, charset, cookie expiry

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletResponseImpl.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/http/CookieCodec.java`
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/bridge/HttpServletResponseImplTest.java` (create or extend the existing response test class — grep first), `foy-core/src/test/java/io/vidocq/foy/internal/http/CookieCodecTest.java`

**Interfaces:**
- Produces:
  - `getHeaders(name)` returns a fresh mutable `ArrayList` copy (changes never affect the response).
  - Once committed (`isCommitted()`), `setHeader/addHeader/setIntHeader/addIntHeader/setDateHeader/addDateHeader/setStatus/setContentType/setContentLength*/setCharacterEncoding/setLocale` are silently ignored; `sendError`, `sendRedirect`, `reset`, `resetBuffer` throw `IllegalStateException` (§5.1, §5.2, Javadoc of each).
  - `sendRedirect(location[, sc[, clearBuffer]])`: when the buffer is cleared (default `true`), `resetBuffer()` first; then status (default 302), `Location` (absolute per §5.7, relative resolved against the request URL), and the response is **closed**: further writes are discarded, `isCommitted()` true. Same "closed" state after `sendError`.
  - `getCharacterEncoding()` returns `"ISO-8859-1"` when no encoding was set by `setCharacterEncoding`, `setContentType` charset, `setLocale` (via `locale-encoding-mapping`) or the context default (`ServletResponse#getCharacterEncoding` Javadoc). The emitted `Content-Type` header keeps omitting the charset when none was explicitly set (unchanged wire behaviour) — check what the Phase 1 code emits and keep it.
  - `setLocale` after `getWriter()` does not change the charset (`charsetLocked`), after commit does nothing.
  - `CookieCodec`: every cookie with `Max-Age >= 0` also gets `Expires=<now + maxAge, IMF-fixdate>` (`Max-Age=0` → `Thu, 01 Jan 1970 00:00:00 GMT` as today). Use a `java.time.Clock` parameter or package-private seam for tests.

- [ ] **Step 1: Failing tests** — one test per bullet above (unit level on the response impl with a fake transport if one exists — check how existing response tests instantiate it; otherwise end-to-end with the test server): `getHeadersIsAMutableCopy`, `headersIgnoredAfterFlushBuffer`, `sendErrorAfterCommitThrows`, `sendRedirectClearsTheBufferAndClosesTheResponse` (write "Test FAILED", sendRedirect, write more → body contains neither), `resetAfterCommitThrows`, `defaultCharacterEncodingIsIso88591`, `setLocaleAfterGetWriterKeepsTheCharset`; `CookieCodecTest.maxAgeAlsoEmitsExpires` with a fixed clock.
- [ ] **Step 2: Run** `./mvnw -ntp -pl foy-core test -Dtest='HttpServletResponseImplTest,CookieCodecTest'` → FAIL.
- [ ] **Step 3: Implement.** Grep `getCharacterEncoding()` callers in foy-core/foy-chappe: any code that used `null` to mean "not set" must switch to an explicit "charset set" flag.
- [ ] **Step 4: Run** `./mvnw -ntp -pl foy-core,foy-chappe install`, then `./run-official-tck-servlet6.1.sh --family api` and `--family spec` → C7/C9/C8-cookie tests pass (target +13), zero per-class regression, `reflection=0`.
- [ ] **Step 5: Commit** — `fix(core): honour response commit, redirect buffer clearing, default charset and cookie expiry`.

---

### Task 4.2: Error-page dispatch

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/ChappeServletBridge.java` (`maybeHandleError`)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/error/ErrorPageRegistry.java`
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/error/ErrorPageDispatchTest.java` (end-to-end)

**Interfaces:**
- Produces:
  - The status message from `sendError(sc, msg)` is captured **before** `clearErrorState()` and exposed as `jakarta.servlet.error.message` (empty string when none, never null).
  - Exception matching (§10.9.2): first the thrown exception's class hierarchy; if no page matches and it is a `ServletException`, repeat with its `getRootCause()` (recursively through nested `ServletException`s only). The error attributes (`exception`, `exception_type`, `message`) describe **the exception that matched** (the unwrapped one when the match came from unwrapping).
  - `jakarta.servlet.error.query_string` (Servlet 6.1) set to the original query string (null when none); `error.request_uri`, `error.servlet_name`, `error.status_code` unchanged.
  - An exception thrown by the error page itself is logged and the response becomes a plain 500 (no recursion).

- [ ] **Step 1: Failing tests** — `statusErrorPageGetsTheMessage` (sendError(411, "my msg") → page sees "my msg"), `wrappedExceptionMatchesTheRootCause` (`throw new ServletException(new TestException())`, page for `TestException` → attributes report `TestException`), `hierarchyMatch` (page for `RuntimeException`, thrown `IllegalThreadStateException` → type reported is `IllegalThreadStateException`), `queryStringAttribute`, `throwingErrorPageGivesPlain500`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -ntp -pl foy-core,foy-chappe install`; TCK `--family api` and `--family spec` → C6 (+6), zero regression.
- [ ] **Step 5: Commit** — `fix(core): keep the error message and report the matched root cause on error pages`.

---

### Task 4.3: Mapping model — `HttpServletMapping`, DEFAULT/EMPTY paths

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/dispatcher/{DispatchTarget,DispatchResolver,UrlPatternMatcher}.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/{HttpServletRequestImpl,ForwardedRequest,IncludedRequest,AsyncDispatchRequest}.java`
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/dispatcher/ServletMappingImpl.java`
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/dispatcher/HttpServletMappingTest.java` (unit + end-to-end)

**Interfaces:**
- Produces: `record ServletMappingImpl(String matchValue, String pattern, String servletName, MappingMatch mappingMatch) implements HttpServletMapping`; `DispatchTarget` gains `HttpServletMapping mapping`. Rules (§12.2, `HttpServletMapping` Javadoc):
  - EXACT `/TestServlet` → matchValue `TestServlet` (no leading `/`), pattern `/TestServlet`;
  - PATH `/foo/*` on `/foo/bar` → matchValue `bar`, pattern `/foo/*`;
  - EXTENSION `*.ts` on `/a.ts` → matchValue `a`, pattern `*.ts`;
  - DEFAULT `/` → matchValue `""`, pattern `/`, servletPath = the whole path, pathInfo `null`;
  - CONTEXT_ROOT `""` → matchValue `""`, pattern `""`, servletPath `""`, pathInfo `/`; EMPTY/CONTEXT_ROOT has exact-match precedence (above PATH and DEFAULT).
  - `getHttpServletMapping()`: REQUEST → the request's mapping; FORWARD → the target's; INCLUDE → the **caller's** (unchanged); ASYNC → the dispatch target's; named forward/include → the caller's.
  - Dispatch attributes: `jakarta.servlet.forward.mapping` (the original request's mapping) on forward, `jakarta.servlet.include.mapping` (the included target's) on include, `jakarta.servlet.async.mapping` on async dispatch — alongside the existing `*.request_uri` etc. (named dispatch sets none — Task 4.4).

- [ ] **Step 1: Failing tests** — mirror the 9 TCK expectations (exact strings in Evidence C3) end-to-end with servlets mapped `/TestServlet`, `*.ts`, `/` (named `defaultServlet`), a forward to `a.ts`, a named forward/include, a POST include, a filter forwarding to `/`, an async dispatch; plus unit tests for `servletPathFor`/precedence (`""` beats `/`, `/` gives servletPath = path and pathInfo null).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -ntp -pl foy-core,foy-chappe install`; TCK `--family api` → `HttpServletRequest40Tests` 9 pass, zero regression.
- [ ] **Step 5: Commit** — `feat(core): implement HttpServletMapping and the default and context-root paths`.

---

### Task 4.4: Dispatcher semantics and servlet-name filter mappings

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/dispatcher/RequestDispatcherImpl.java`, `DispatchResolver.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/{ForwardedRequest,IncludedRequest}.java`
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/DispatchParameters.java` (the shared query-merge helper)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/dispatcher/{FilterMapping,FilterRegistry}.java`, `foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppDeployer.java` (servlet-name mappings), `foy-chappe/.../ChappeServletBridge.java` (chain lookup with the target servlet name)
- Modify: `BUG.md` (BUG-20261008-02 → FIXED)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/dispatcher/DispatcherSemanticsTest.java`, `FilterRegistryServletNameTest.java`

**Interfaces:**
- Produces:
  - `DispatchParameters.merge(Map<String,String[]> original, String queryString, Charset)` → include/forward parameters where the dispatch query's values come **first** (§9.1.1), used by both wrappers; parameters revert after the include returns.
  - Named dispatch (`getNamedDispatcher`): no `forward.*`/`include.*`/`*.mapping` attributes, request paths and query unchanged (§9.3.1, §9.4.2), still `DispatcherType.FORWARD/INCLUDE`.
  - Nested forward: `forward.*` keep the values of the **original** request (set only when absent).
  - `ForwardedRequest.getRequestURL()` built from scheme/host/port + the forwarded `getRequestURI()`.
  - Include attributes set for the duration of the include and restored (previous values, or removed) afterwards.
  - After `forward` returns, the response is committed and closed (§9.4): flush the buffer, further writes discarded.
  - Exceptions from the dispatched target: `ServletException`, `IOException` and `RuntimeException` propagate **unchanged** to the caller (no wrapping), as the TCK's `requestDispatcher*RuntimeExceptionTest` expects — check the current wrapping in `RequestDispatcherImpl`/`ChappeServletBridge.invoke`.
  - `FilterMapping` is either URL-pattern or servlet-name (never expanded into the servlet's patterns); `FilterRegistry.chainFor(path, DispatcherType, String targetServletName)` returns, in order, the URL-pattern matches (declaration order) then the servlet-name matches (declaration order, `*` = every servlet), each filter at most once per chain (§6.2.4). Named dispatch passes the target's name. BUG-20261008-02 fixed.

- [ ] **Step 1: Failing tests** — include with `?testname=x` → target sees `x` first, caller sees its own after return; nested include/forward; named include and named forward set no attribute and keep paths; nested forward keeps original `forward.request_uri`; `getRequestURL` inside a forward; RuntimeException from an included servlet reaches the caller as the same type; response closed after forward; `FilterRegistryServletNameTest`: URL + servlet-name mapping of the same filter → invoked once, URL mappings before servlet-name ones, servlet-name filter not applied when another servlet wins the pattern, `*` servlet name.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -ntp -pl foy-core,foy-chappe install`; TCK `--family spec`, `--family api`, `--family pluggability` → `spec.requestdispatcher` + `spec.srlistener` (+18), zero regression. Check the srlistener `requestInitialized`/`requestDestroyed` balance in the log.
- [ ] **Step 5: Commit** — `fix(core): dispatcher parameter merge, named dispatch and servlet-name filter mappings`.

---

### Task 4.5: Container default servlet

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/container/DefaultServlet.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppDeployer.java` (append the `/` mapping when the app maps none)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/container/VidocqServletContext.java` (registrations exclude it; `getRequestDispatcher` no longer needs `notFound` for unmapped paths)
- Modify: `foy-chappe/.../ChappeServletBridge.java` (the no-match branch becomes a last-resort fallback; drop the "200 + empty body means 404" heuristic once the default exists)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/container/DefaultServletTest.java` (end-to-end)

**Interfaces:**
- Produces: `DefaultServlet` (name `"foy.default"` — cannot clash with application names such as the TCK's `defaultServlet`), mapped to `/` with `asyncSupported=true` only when no application servlet maps `/`; serves `GET`/`HEAD` from `ServletContext.getResource*`:
  - path = `servletPath + pathInfo` for REQUEST/FORWARD, `jakarta.servlet.include.servlet_path` + `include.path_info` for INCLUDE;
  - refuses anything `ClassPathResourceProvider.isServable` refuses (generalise it to a provider-independent static helper, case-insensitive `WEB-INF`/`META-INF`) → 404;
  - `Content-Type` from `getMimeType`, `Content-Length`, `Last-Modified`, weak `ETag` (`W/"<length>-<lastModified>"`), `If-Modified-Since`/`If-None-Match` → 304, `Range` single range → 206 with `Content-Range`, unsatisfiable → 416, multiple ranges → 200 full body (acceptable simplification, documented);
  - missing resource → 404 on REQUEST/FORWARD, `FileNotFoundException` on INCLUDE (Tomcat behaviour);
  - other methods → 405 with `Allow: GET, HEAD, OPTIONS`;
  - a directory → handled by Task 4.6 (here: 404).
- Not listed in `getServletRegistrations()` / `getServletRegistration("foy.default")` returns null.

- [ ] **Step 1: Failing tests** — unmapped `/dummy.html` served with `text/html` and the filter mapped to `/dummy.html` runs first; forward and include to `/dummy.html` run the FORWARD/INCLUDE filters; `protectedTreesAre404` (`/WEB-INF/web.xml`, `/web-inf/web.xml`, `/META-INF/MANIFEST.MF`); conditional GET 304 by date and by ETag; Range 206/416; HEAD has headers and no body; POST → 405; app mapping `/` → its servlet wins, no container default; `getServletRegistrations()` unchanged.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -ntp -pl foy-core,foy-chappe install`; TCK `--family api`, `--family spec`, `--family pluggability` → C1 (+8), zero regression.
- [ ] **Step 5: Commit** — `feat(core): serve static resources through a container default servlet`.

---

### Task 4.6: Welcome files (§10.10)

**Files:**
- Modify: `foy-chappe/.../ChappeServletBridge.java` (before `dispatcher.find(path)`)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/container/DefaultServlet.java` (directory requests)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/container/WelcomeFilesTest.java` (end-to-end)

**Interfaces:**
- Produces: for a request whose path is a directory (`"/"` or a path for which `getResourcePaths(path + "/")` is non-null, or which ends with `/`):
  - without trailing `/` (and not the context root) → 302 redirect to `path + "/"` (query string kept);
  - with trailing `/` → for each welcome file in order: a static resource `path + wf` that exists → forward (FORWARD dispatcher type is NOT used by Tomcat; use an internal re-dispatch as a REQUEST to `path + wf`, so `getServletPath` reflects the welcome resource — document the choice); else a servlet exact/prefix/extension match for `path + wf` (§10.10: partial match) → that servlet; first hit wins; none → the default servlet's directory 404 (no listing).
- [ ] **Step 1: Failing tests** — `/foo` → 302 `/foo/` → `foo/index.html`; `/` with welcome `TestServlet4` mapped `/TestServlet4` → that servlet; welcome list order respected; extension match (`*.ts` welcome `index.ts`); no welcome hit → 404.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -ntp -pl foy-core,foy-chappe install`; TCK `--family spec`, `--family pluggability` → C2 (+2), zero regression.
- [ ] **Step 5: Commit** — `feat(core): resolve welcome files`.

---

### Task 4.7: Session core

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/session/SessionManager.java` (+ the session impl), `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java` (`changeSessionId`), `HttpServletResponseImpl.java` (`encodeURL`/`encodeRedirectURL`), `foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppDeployer.java` (session timeout after SCIs), `foy-chappe/.../ChappeServletBridge.java` (`Secure` cookie on secure requests; `;jsessionid=` parsing already present)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/session/SessionCoreTest.java`

**Interfaces:**
- Produces:
  - `changeSessionId()`: `IllegalStateException` without a session; otherwise re-key in `SessionManager` atomically, keep attributes and creation time, fire `HttpSessionIdListener.sessionIdChanged(event, oldId)` once (check `ListenerRegistry` supports the type; add it if not), return the new id; the bridge emits the new cookie.
  - Expiry reaper: a virtual-thread scheduled task (period = min(60 s, timeout/2), configurable for tests via a package-private seam) invalidates expired sessions, firing `sessionDestroyed` and unbinding attributes (`HttpSessionBindingListener.valueUnbound`); lazy expiry in `find` also fires them; the reaper stops on undeploy (`Deployment.close`).
  - `ServletContext.setSessionTimeout(int)` from an SCI or a `ServletContextListener` is honoured: the session manager reads the context's timeout after initializers/listeners ran (Phase 1 follow-up).
  - Tracking modes: when the effective modes contain `URL` and the session is not known to come from a cookie, `encodeURL`/`encodeRedirectURL` append `;jsessionid=<id>` (path parameter name fixed by §7.1.3) before the query/fragment; when they don't, URLs are returned unchanged. Cookie mode off → no session cookie.
  - The session cookie gets `Secure` when the request is secure, unless `SessionCookieConfig.isSecure()` was explicitly set (§7.1.1).
- [ ] **Step 1: Failing tests** — one per bullet (`changeSessionIdKeepsAttributesAndFiresTheListener`, `changeSessionIdWithoutSessionThrows`, `reaperFiresSessionDestroyed` with a short timeout + seam, `undeployStopsTheReaper`, `sciSetSessionTimeoutIsHonoured`, `encodeUrlAddsJsessionidWhenUrlTrackingIsOn`, `secureRequestGetsSecureCookie`).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -ntp -pl foy-core,foy-chappe install`; TCK `--family api` and `--family pluggability` → `changeSessionIDTest1` ×2 pass, zero regression.
- [ ] **Step 5: Commit** — `feat(core): changeSessionId, session expiry reaper, URL rewriting and secure session cookies`.

---

### Task 4.8: Legacy (DTD 2.2/2.3) descriptors

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/webxml/WebXmlParser.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/dispatcher/UrlPatternMatcher.java` (or the merger) for pre-2.3 patterns
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/webxml/WebXmlParserLegacyTest.java`

**Interfaces:**
- Produces: a `<!DOCTYPE web-app PUBLIC "-//Sun Microsystems, Inc.//DTD Web Application 2.2//EN" …>` (or 2.3) is accepted **without** resolving any external DTD or entity: keep `FEATURE_SECURE_PROCESSING`, set `disallow-doctype-decl=false`, `load-external-dtd=false`, external general/parameter entities `false`, `setExpandEntityReferences(false)`, and an `EntityResolver` returning an empty `InputSource` — an internal subset declaring entities (billion laughs) is still rejected (secure-processing entity limits; add a test). The DTD public id sets `version` to `2.2`/`2.3`. For descriptors with version < 2.4, a `<url-pattern>` without a leading `/` that is not `*.ext` and not empty gets `/` prepended (Tomcat compatibility); for ≥ 2.4 it stays an error.
- [ ] **Step 1: Failing tests** — 2.2 DOCTYPE accepted and version `2.2`; external DTD URL never fetched (an `EntityResolver`-observable or a `file:` DTD path that does not exist → still parses); internal-subset billion-laughs rejected; `WithoutLeadingSlashTest` pattern → `/WithoutLeadingSlashTest` for 2.2, error for 6.1.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -ntp -pl foy-core,foy-chappe install`; TCK `--family compat` → 2/2, plus `--family spec` sanity.
- [ ] **Step 5: Commit** — `feat(webxml): accept legacy DTD descriptors safely`.

---

### Task 4.9: TCK harness — one host for several deployments

**Files:**
- Modify: `foy-tck/src/test/java/io/vidocq/foy/tck/arquillian/VidocqDeployableContainer.java`, `foy-tck/src/main/java/io/vidocq/foy/tck/ServletTestHarness.java`
- Test: `foy-tck/src/test/java/io/vidocq/foy/tck/ServletTestHarnessTest.java`

**Interfaces:**
- Produces: the container starts **one** Chappe server for the Arquillian container instance and mounts every deployed archive under its context path on it (path-prefix routing, cross-context via the existing `CrossContextRegistry`); undeploy unmounts one context; `[VidocqTCK] deploy/undeploy` log lines keep their format (the tally greps them).
- [ ] **Step 1: Failing test** — deploy two WARs, both reachable on the same host:port under their context paths; undeploying one leaves the other serving.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.**
- [ ] **Step 4: Run** harness tests, then TCK `--family api` (`HttpSessionxTests` passes) and `--family spec`, `--family pluggability` → zero regression.
- [ ] **Step 5: Commit** — `refactor(tck): serve every deployment of a container from one host`.

---

### Task 4.10: Leftovers without TCK coverage

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/container/VidocqServletContext.java` (`getRealPath`, `getVirtualServerName`), `HttpServletRequestImpl.java` (`getRequestId`, `getProtocolRequestId`, `getServletConnection`), `foy-core/src/main/java/io/vidocq/foy/internal/listener/ListenerRegistry.java` (`fireContextDestroyed` isolation), `foy-chappe/.../FoyChappeBoot.java` (`Builder.virtualServerName`)
- Test: matching tests per item

**Interfaces:**
- Produces: `getRealPath(path)` → the file-system path when the resource provider is directory-backed and the file exists, else `null`; `getVirtualServerName()` configurable (default unchanged `"vidocq"`); `getRequestId()` → a unique per-request id (monotonic counter as string), `getProtocolRequestId()` → `""` for HTTP/1.1 and the stream id for HTTP/2 if chappe exposes it, `getServletConnection()` → an implementation reporting connection id, protocol, `isSecure`, remote/local addresses from chappe; `fireContextDestroyed` calls every listener in reverse order even if one throws (each failure logged); the temp-dir leak test uses its own `@TempDir` instead of the shared `java.io.tmpdir`.
- [ ] **Steps:** failing test per item → implement → `./mvnw -ntp -pl foy-core,foy-chappe install` → commit `feat(core): request ids, servlet connection, real path and context-destroyed isolation`.

---

### Task 4.11: Phase exit — full TCK, baseline, docs

**Files:**
- Modify: `foy-tck/tck-baseline.txt` (only with zero regression), `TCK.md` (§0 entry), `docs/en/modules/ROOT/pages/{usage,reference,internals,concepts,tck}.adoc` (default servlet, welcome files, mapping, dispatcher semantics, sessions, legacy descriptors), `README.md` figures, `docs/superpowers/plans/2026-10-07-servlet-completion-roadmap.md` ("Phase 4 exit / follow-ups"), `BUG.md` statuses.

- [ ] **Step 1:** `./run-official-tck-servlet6.1.sh --all > /tmp/foy-tck-phase4-exit.log 2>&1`; per-class regression diff (TCK.md §0); `grep -o 'tiers=Stats\[[^]]*\]' /tmp/foy-tck-phase4-exit.log | grep -v 'reflection=0'` → empty.
- [ ] **Step 2:** refresh the baseline (`LC_ALL=C` sort) and TCK.md §0 with per-family figures.
- [ ] **Step 3:** docs and roadmap as listed (xref anchors without `_` prefix).
- [ ] **Step 4: Commit** — `docs: document request, response, dispatcher and session semantics (Phase 4 exit)` and `build(tck): refresh the baseline to the Phase 4 exit tally`.
