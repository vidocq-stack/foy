# BUG.md — foy

Suivi des bugs reproductibles. Convention : voir `../CLAUDE.md` (workspace root).

Statuts : `OPEN` → `INVESTIGATING` → `FIXED` (commit hash) → `CLOSED`.

---

## BUG-20260611-01 — chunked request with HTTP trailers never gets a response (connection hangs)

- **Date** : 2026-06-11
- **Statut** : FIXED (foy PR #2 + chappe PR #2/#3 — see investigations)
- **Module touché** : `foy-chappe` / chappe transport (trailer parsing after the final chunk)
- **Symptôme** : a `Transfer-Encoding: chunked` request carrying trailer fields
  gets **no response at all** — the connection stays open and silent. A client
  reading the socket without `SO_TIMEOUT` blocks forever (this froze the first
  full Servlet TCK run: `HttpServletRequest40Tests.TrailerTest` stuck in
  `Socket.read()`, thread dump captured 2026-06-11).
- **Reproduction minimale** :
  ```
  ./run-official-tck-servlet6.1.sh "-Dtest=HttpServletRequest40Tests#TrailerTest"
  # or: send POST with Transfer-Encoding: chunked, a body chunk, then
  # "0\r\nmyTrailer: foo\r\n\r\n" — no bytes ever come back
  ```
- **Hypothèse de cause** : the chunked decoder does not consume/parse trailer
  fields after the terminal `0\r\n` chunk, so the request never completes and
  the dispatch never fires (or blocks reading what it thinks is more body).
  `request.getTrailerFields()` (Servlet 4.0 §) depends on the same parsing.
  Related TCK fallout: the rest of `HttpServletRequest40Tests` (10/11 errors).
- **Investigations** :
  - 2026-06-11 : found during the first full Servlet 6.1 TCK measurement run
    (see `TCK.md` §3.2 and §5). Mitigated harness-side with a JUnit global
    timeout (`junit-platform.properties`) so measurement runs cannot freeze;
    the implementation gap itself remains open. Audit hint: see the workspace
    memory of CHAPPE-004-style single-read bugs — the trailer path may be
    another "assumes everything arrives in one read()" case.
  - 2026-06-12 : full root-cause chain identified and fixed — FOUR stacked
    issues, none of them the initially suspected chunked decoder:
    1. chappe threw the parsed trailer fields away → exposed via
       `Request.trailers()` (chappe PR #2);
    2. chappe's `Router.mount`/`withPathParams` anonymous wrappers fell back
       to the `trailers()`/`onDisconnect()` interface defaults (CASSINI-002
       pattern) → `ForwardingRequest` + reflective parity test (chappe PR #3);
    3. foy inherited the spec-default `getTrailerFields()` (empty map) →
       implemented on `chappe.trailers()` (lowercase keys) +
       `isTrailerFieldsReady()` on body-EOF tracking;
    4. the REAL hang: the TCK 6.1.0 client reads the response to EOF on a
       keep-alive connection and relies on the container's keep-alive
       timeout — chappe's idle timeout was a silent no-op
       (`setSoTimeout` on a `SocketChannel`, CHAPPE-005) → `BoundedReads`
       watchdog (chappe PR #3); upstream later fixed the test itself
       (`Connection: close`, jakartaee/servlet `079ceb29cb`).
    Also fixed here: `foy-tck` froze `foy.version=0.1.0-SNAPSHOT` while the
    reactor is 0.2.0 — the TCK was validating stale M2 jars (same trap as
    cassini-tck); bumped, with a warning comment. `TrailerTest` now PASSES.

## BUG-20261007-01 — form POST parameters ignored

- **Date** : 2026-10-07
- **Statut** : FIXED (this commit)
- **Module touché** : `foy-core` / `HttpServletRequestImpl`
- **Symptôme** : `getParameter*` only read the query string; an
  `application/x-www-form-urlencoded` POST body never became parameters (§3.1.1).
- **Reproduction minimale** : `ServletFormParametersEndToEndTest`.
- **Hypothèse de cause** : M1 shortcut ("query-string only for this milestone").

## BUG-20261008-01 — declared filter registrations expose no mappings

- **Date** : 2026-10-08
- **Statut** : FIXED (this commit)
- **Module touché** : `foy-core` (`WebAppDeployer.registerStatic`)
- **Symptôme** : for a filter declared in web.xml, a web fragment or by `@WebFilter`,
  `ServletContext.getFilterRegistration(name).getUrlPatternMappings()` and
  `getServletNameMappings()` are empty, although the filter is mapped and invoked.
  TCK: `spec.annotationservlet.webfilter.WebFilterTests.test2` (forward1).
- **Reproduction minimale** : deploy a model with filter `f` mapped to `/x` and to servlet `a`,
  then `ctx.getFilterRegistration("f").getUrlPatternMappings()` → `[]`
  (`WebAppDeployerEndToEndTest.declaredRegistrationsExposeTheirMappings`).
- **Hypothèse de cause** : `registerStatic` called `ctx.registerStaticFilter(name, type, params,
  async)` without the model's filter mappings; routing used the model directly, so only the
  introspection API was wrong. Servlet registrations already received their patterns.
- **Investigations** :
  - 2026-10-08 : found while routing the TCK harness through the product merge (Task 3.10 review).
    Fixed: the model mappings of each declared filter are added to its static registration
    (URL patterns and servlet names, with their dispatcher types); routing unchanged.

## BUG-20261008-02 — a filter mapped by URL pattern and by servlet name runs twice for one request

- **Date** : 2026-10-08
- **Statut** : FIXED (4393baf, 2026-10-09, Phase 4 Task 4.4)
- **Module touché** : `foy-core` (`WebAppDeployer.buildFilterMappings`, `FilterRegistry` chain building)
- **Symptôme** : a filter with a `<url-pattern>` mapping and a `<servlet-name>` mapping that both
  match the same request is invoked twice in the chain. Tomcat invokes it once (§6.2.4: the chain
  is built from the matching mappings, a filter appears once).
- **Reproduction minimale** : filter `f` mapped to `/x` and to servlet `a`, servlet `a` mapped to
  `/x`; `GET /x` → `f.doFilter` runs twice.
- **Hypothèse de cause** : servlet-name mappings are expanded into the target servlet's URL
  patterns (one `FilterMapping` per pattern) and the chain is assembled from every matching
  mapping with no deduplication by filter name.
- **Investigations** :
  - 2026-10-08 : noted during the Task 3.10 review; left unfixed (Phase 4 dispatch work).
  - 2026-10-09 : cause confirmed. Expanding servlet-name mappings into URL patterns also broke the
    §6.2.4 order (URL-pattern matches before servlet-name matches), applied a servlet-name filter
    to requests another servlet serves, gave named dispatches the `/*` filters instead of the
    target's servlet-name filters, and ignored `<servlet-name>*</servlet-name>`.
- **Correction** : `FilterMapping` is either a URL-pattern or a servlet-name mapping (never
  expanded); `FilterRegistry.chainFor(path, type, servletName)` returns the URL-pattern matches in
  declaration order, then the servlet-name matches (`*` = every servlet), each filter at most once;
  a named dispatch passes no path, only the target's name. Tests: `FilterRegistryServletNameTest`,
  `WebAppDeployerEndToEndTest.filterMappedByUrlAndServletNameRunsOnce` and siblings.

## BUG-20261009-01 — forward/include drop a non-HTTP application wrapper passed to the dispatcher

- **Date** : 2026-10-09
- **Statut** : OPEN
- **Module touché** : `foy-core` (`RequestDispatcherImpl.unwrapHttp` / `unwrapHttpResponse`)
- **Symptôme** : when a servlet or filter passes a plain `ServletRequestWrapper` (or
  `ServletResponseWrapper`) that is not an `HttpServletRequest` to `forward`/`include`, the target
  receives the inner HTTP request (wrapped by the container's dispatch wrapper), not the
  application's wrapper. Section 9.1/6.2.2: the target must see the objects that were passed in.
  HTTP wrappers (`HttpServletRequestWrapper`) are kept.
- **Reproduction minimale** : servlet `C` calls
  `getRequestDispatcher("/T").include(new ServletRequestWrapper(req) { getParameter -> "w" }, resp)`;
  `T` reads `getParameter(...)` → the wrapper's override is not seen.
- **Hypothèse de cause** : the dispatcher unwraps to the first `HttpServletRequest` to build the
  `ForwardedRequest`/`IncludedRequest`; the dispatch wrapper should instead be inserted under the
  application's wrapper chain (Tomcat `ApplicationDispatcher.wrapRequest`).
- **Investigations** :
  - 2026-10-09 : noted during the Task 4.4 fix round; no failing TCK test identified.

## BUG-20261009-02 — getServletPath/getPathInfo are not percent-decoded

- **Date** : 2026-10-09
- **Statut** : FIXED (1906b44, fix round in the following `fix(core)` commit — Task 4.5b)
- **Module touché** : `foy-core` (`ChappeServletBridge.handle`, `HttpServletRequestImpl`)
- **Symptôme** : the bridge derives the servlet path and path info from the raw chappe
  `request.path()`, which is not percent-decoded. Servlet 6.1 section 3.6 (and the
  `getServletPath`/`getPathInfo` Javadoc) require decoded values. Mapping and static resource
  lookup therefore see the encoded form: `GET /my%20file.txt` answers 404 although the WAR holds
  `/my file.txt`.
- **Reproduction minimale** : deploy a resource `/my file.txt` (container default servlet), then
  `GET /ctx/my%20file.txt` → 404; a servlet mapped `/a b` is not reached by `/a%20b`.
- **Hypothèse de cause** : no decoding step between the chappe request path and the dispatcher;
  `getRequestURI` must stay raw, the servlet path and path info must be decoded (UTF-8), with the
  default servlet's encoded-separator check (`ResourcePaths`) kept against double encoding.
- **Investigations** :
  - 2026-10-09 : found during Task 4.5 (default servlet). The default servlet refuses encoded
    `.`, `/`, `\` and NUL, so the raw path is not a traversal risk today.
  - 2026-10-09 : fixed by `RequestPaths.canonicalize` (section 3.5.2), called once in
    `ChappeServletBridge.handle`: path parameters stripped from every segment, encoded `/`, `\`,
    NUL, raw `\`, control and non-ASCII characters refused with 400 (Tomcat
    `encodedSolidusHandling=reject`), one UTF-8 percent-decoding (malformed → 400), dot segments and
    repeated slashes normalised (`..` above the root → 400). Servlet/filter mapping, the default
    servlet and the security enforcer see the canonical path (kept on the request);
    `getRequestURI`/`getRequestURL` stay raw. A 400 honours a status error page. Dispatch paths
    (`getRequestDispatcher`, `DispatchResolver`) are normalised but not decoded again; one climbing
    above the root gets no dispatcher. Tests: `RequestPathsTest`, `RequestPathCanonicalisationTest`.
  - 2026-10-09 (fix round) : a zero-argument `AsyncContext.dispatch()` on an application-wrapped
    request now uses the canonical path found through the wrapper chain
    (`HttpServletRequestImpl.canonicalDispatchUri`); the context path is removed on a segment
    boundary (`/ctx;x=1/a` → `/a`, `/ctx2/a` → 404); segments made only of dots and spaces
    (`...`, `..%20`) are refused with 400; `OPTIONS *` is dispatched unchanged.

## BUG-20261009-03 — error dispatch shows the error-page location's paths only to the default servlet

- **Date** : 2026-10-09
- **Statut** : OPEN
- **Module touché** : `foy-core` (`ChappeServletBridge.maybeHandleError` / `errorTargetRequest`)
- **Symptôme** : on an ERROR dispatch the target receives the original request, whose
  `getServletPath`/`getPathInfo` describe the failing request, not the error-page location
  (section 10.9: the error dispatch behaves like a forward to the location). Only the container
  default servlet gets a wrapper with the location's paths (an `instanceof DefaultServlet` special
  case), so that static error pages are found.
- **Reproduction minimale** : error page `500 → /err` mapped to a servlet printing
  `req.getServletPath()`; a servlet throwing → the error servlet prints the failing servlet's path.
- **Hypothèse de cause** : `maybeHandleError` invokes the target with the container request instead
  of a forward-style wrapper; generalise `errorTargetRequest` to every target once the TCK impact
  is checked.
- **Investigations** :
  - 2026-10-09 : special case introduced by Task 4.5; generalisation deferred.

## BUG-20261009-04 — decoded paths leak into dispatch URIs (query split, wrapper URIs)

- **Date** : 2026-10-09
- **Statut** : OPEN (low)
- **Module touché** : `foy-core` (`AsyncContextImpl.dispatch()`, `ForwardedRequest`, `IncludedRequest`, `AsyncDispatchRequest`)
- **Symptôme** : (1) a zero-argument `AsyncContext.dispatch()` builds its target from the decoded
  canonical path; an encoded `%3F` in the request path decodes to `?`, which the dispatch then
  splits as a query string (Tomcat behaves the same). (2) `jakarta.servlet.include.request_uri`,
  the forwarded `getRequestURI()` and the async-dispatched `getRequestURI()` are built from the
  decoded dispatch path instead of a re-encoded URI.
- **Reproduction minimale** : servlet mapped `/a?b/*` (decoded), `GET /ctx/a%3Fb/x` → startAsync +
  `dispatch()` → resolves `/ctx/a` with query `b/x`. Forward to `/my file.txt` →
  `getRequestURI()` is `/ctx/my file.txt`, not `/ctx/my%20file.txt`.
- **Hypothèse de cause** : dispatch paths are decoded context-relative paths (section 9.1.1); the
  wrappers concatenate them into the URI without percent-encoding, and the zero-argument dispatch
  passes a path through the string-based `dispatch(String)` API that splits on `?`.
- **Investigations** :
  - 2026-10-09 : found in the Task 4.5b review; not exercised by the TCK.

## BUG-20261009-05 — changeSessionId throws UnsupportedOperationException; HttpSessionIdListener ignored

- **Date** : 2026-10-09
- **Statut** : FIXED (Phase 4 Task 4.7, `feat(core): changeSessionId, session expiry reaper, ...`)
- **Module touché** : `foy-core` (`HttpServletRequestImpl.changeSessionId`, `ListenerRegistry`)
- **Symptôme** : `HttpServletRequest.changeSessionId()` answered 500 `UnsupportedOperationException:
  changeSessionId not implemented` (TCK `HttpSessionIdListenerTests.changeSessionIDTest1`,
  `HttpServletRequestWrapperTests.changeSessionIDTest1`); `ListenerRegistry.register` silently dropped
  `HttpSessionIdListener` instances.
- **Reproduction minimale** : a servlet calling `req.getSession(); req.changeSessionId()`.
- **Hypothèse de cause** : not implemented (Phase 1 stub).
- **Investigations** :
  - 2026-10-09 : `SessionManager.changeSessionId` re-keys the session in the store
    (`SessionStore.rename`, a backward-compatible default method), keeps attributes and times,
    fires `sessionIdChanged` once; the bridge emits the new cookie (requested id != current id).
  - 2026-10-09 (Task 4.7 review, fix round 1) : race with invalidation/undeploy — the guard
    checked `isInvalidated()` only, so `sessionIdChanged` could fire on a session being destroyed
    and `rename` could re-insert a dead session under its new id. The change now runs under the
    session monitor (which every invalidation claim takes) and refuses a session whose
    invalidation started; the store removal of an invalidated session takes the same
    `renameLock` as the re-keying.

## BUG-20261009-06 — expired sessions dropped without sessionDestroyed; wrong last-accessed time

- **Date** : 2026-10-09
- **Statut** : FIXED (Phase 4 Task 4.7, same commit)
- **Module touché** : `foy-core` (`SessionManager`, `HttpSessionImpl`)
- **Symptôme** : (1) `SessionManager.find` removed an expired session from the store without firing
  `HttpSessionListener.sessionDestroyed` nor unbinding its attributes, and a session never asked for
  again was never expired (no reaper). (2) `invalidate()` unbound the attributes and marked the
  session invalid *before* `sessionDestroyed`, so a listener reading an attribute got an
  `IllegalStateException`. (3) `getLastAccessedTime()` returned the start of the current request
  instead of the previous request's time (TCK `HttpSessionTests.expireHttpSessionTest`:
  "indicates last accessed at … which is after it was last accessed").
- **Reproduction minimale** : (1) timeout 1 s, create a session holding an
  `HttpSessionBindingListener`, wait 2 s, request it → no `sessionDestroyed`/`valueUnbound`.
  (3) two requests on one session → the second sees its own start time.
- **Hypothèse de cause** : lazy-only expiry with a bare `store.remove`; access time updated on lookup.
- **Investigations** :
  - 2026-10-09 : Tomcat semantics adopted: a request begins an access on lookup and ends it in the
    bridge after processing (async included); the end becomes the last-accessed time; a session in
    use is never expired. Expiry (lazy in `find`/`peek` and by a virtual-thread reaper, period
    `min(60 s, max(1 s, timeout / 2))`) fires `sessionDestroyed` while the session is still valid,
    then `valueUnbound`/`attributeRemoved`. Undeploy stops the reaper and invalidates every live
    session with its listeners (no persistence).

## BUG-20261009-07 — tracking modes not enforced on input; COOKIE-only default

- **Date** : 2026-10-09
- **Statut** : FIXED (Phase 4 Task 4.7 fix round 1, `fix(core): ...`)
- **Module touché** : `foy-core` (`HttpServletRequestImpl.getRequestedSessionId`, `VidocqServletContext`)
- **Symptôme** : a `;jsessionid=` path parameter resolved the session even when `URL` was not an
  effective tracking mode, and the session cookie did when `COOKIE` was not; the default modes
  were `{COOKIE}` only, so URL rewriting was off by default.
- **Reproduction minimale** : `<tracking-mode>COOKIE</tracking-mode>`, create a session, request
  `/ctx/x;jsessionid=<id>` without the cookie → the session is found.
- **Hypothèse de cause** : the request read both sources unconditionally.
- **Investigations** :
  - 2026-10-09 : ruling — default `{COOKIE, URL}` (Tomcat; `SSL` never by default), each source
    read only when its mode is effective (section 7.1).
  - 2026-10-09 (fix round 2) : session fixation — with both a `;jsessionid=` and a session
    cookie, the URL id won; the cookie now wins when `COOKIE` is effective (Tomcat), the path
    parameter applies only without a session cookie.

## BUG-20261009-08 — HttpSessionTests.expireHttpSessionTest flaky: a request not calling getSession does not access the session

- **Date** : 2026-10-09
- **Statut** : FIXED (Phase 4 Task 4.10, `fix(core): access the requested session on every request`)
- **Module touché** : `foy-core` (`ChappeServletBridge.handle`, `HttpServletRequestImpl`)
- **Symptôme** : api `HttpSessionTests.expireHttpSessionTest` passed in some TCK runs and failed
  in others, in isolation too. The failure is in its `getLastAccessedTime` step, not the expiry:
  `Session created before 1791573543421 ... last accessed at 1791573543420 which is before
  creation` (`task49b-api.log`).
- **Reproduction minimale** : `./run-official-tck-servlet6.1.sh --no-install
  -Dtest=servlet.tck.api.jakarta_servlet_http.httpsession.HttpSessionTests` (intermittent);
  deterministic: `SessionCoreTest.aRequestCarryingTheSessionIdAccessesTheSessionWithoutGetSession`.
- **Hypothèse de cause** : the test sets the max interval (request 1), records `t1` on the
  client, fetches the static `/index.html` with the session cookie, records `t2`, then asks for
  `t1 <= getLastAccessedTime() <= t2`, i.e. it expects the static request to be the last access
  (section 7.6: "accessed when a request that is part of the session is first handled"). Foy
  began an access only when the application called `getSession`, which the default servlet
  never does, so the last access stayed the end of request 1. That end is recorded before the
  response is written, so it is `< t1` unless the client read the reply within the same
  millisecond: pass or fail depended on timing.
- **Investigations** :
  - 2026-10-09 : javap of `HttpSessionTests`/`GetLastAccessedTime` (data only) confirmed the
    three-request sequence; failure message reproduced from the TCK log. Fix: the bridge calls
    `HttpServletRequestImpl.accessRequestedSession()` once the request is set up, before any
    filter or servlet, which resolves the requested session (`getSession(false)`, as Tomcat's
    `ALWAYS_ACCESS_SESSION`) so that every request carrying a valid id begins and ends an access
    (and keeps the session from expiring while it runs). 5 consecutive isolated TCK runs of
    `HttpSessionTests`: 25/25 each.
