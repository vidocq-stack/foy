# BUG.md — foy

## BUG-20261013-04 — an async cycle started in an error page never runs

- Date: 2026-10-13 (final fix wave of the dispatch batch).
- Symptom: an error page that calls `startAsync()` (or `startAsync(req, res)`) and then
  `AsyncContext.dispatch()` gets an empty response. The dispatch never runs, and no exception tells the
  application.
- Minimal repro: a servlet calls `sendError(500)`. The `<error-page>` location is async-supported; it calls
  `startAsync(req, res)` then `dispatch()`. The client receives an empty body.
- Cause: `ChappeServletBridge` runs the async cycles (`runAsyncCycles`) before the error handling
  (`maybeHandleError`), so a cycle opened by the ERROR dispatch is never driven. A zero-argument
  `startAsync()` also binds the container request, not the `ErrorDispatchRequest`.
- Status: OPEN. Fix direction: either drive the async cycles after an ERROR dispatch, or refuse
  `startAsync()` during an ERROR dispatch with an `IllegalStateException` so the gap is no longer silent.

## BUG-20261013-03 — forward and include put the container wrapper above an HTTP application wrapper instead of under it

- Date: 2026-10-13 (review of the BUG-20261009-01 fix).
- Symptom: when an application passes an `HttpServletRequestWrapper` (or `HttpServletResponseWrapper`)
  to `forward`/`include`, the target does not receive the object the application passed (section
  6.2.2): it receives the container's `ForwardedRequest`/`IncludedRequest` wrapping it. The application
  wrapper's overrides of `getParameter*`, the path methods and `getAttribute` are hidden, because the
  dispatch wrapper answers them itself. Tomcat puts its dispatch wrapper under the application's chain
  for HTTP wrappers too (`ApplicationDispatcher.wrapRequest`).
- Minimal repro: servlet `C` calls `getRequestDispatcher("/T").forward(new HttpServletRequestWrapper(req) {
  getParameter -> "h" }, resp)`; `T` reads `getParameter("p")` → `null` instead of `h`.
  `DispatcherSemanticsTest.anHttpWrapperIsStillPassedAsBefore` pins today's behaviour through a
  delegated method (`getHeader`).
- Cause: `RequestDispatcherImpl` only hooks the lowest *non-HTTP* wrapper (BUG-20261009-01). When the
  outer object is HTTP, it still wraps the outer object in the dispatch wrapper.
- Status: OPEN. Before changing it, gate on a full `run-official-tck-servlet6.1.sh --all` run before
  and after the change: the request-dispatcher and wrapper suites wrap requests in filters.

## BUG-20261013-02 — FoyChappeBoot fails on the module path: foy-core does not declare `uses CdiContextListeners`

- Date: 2026-10-13 (vidocq PR 245, `vidocq-runtime-it-foy-servlet` red in CI).
- Symptom: booting through `FoyChappeBoot` with Foy on the module path fails with
  `ServiceConfigurationError: io.vidocq.foy.spi.cdi.CdiContextListeners: module io.vidocq.foy.core does
  not declare 'uses'` (`FoyServletModulePathTest`, `FoyServletContextPathTest` in vidocq).
- Minimal repro: `LayeredProvidersTest.theModuleMayLoadTheCdiContextListenersService`.
- Cause: the BUG-20261012-02 fix (foy#38) moved the `ServiceLoader.load` call for the CDI context
  listeners from `foy-chappe` (which declares `uses`) to `ApplicationSources.providers` in `foy-core`,
  whose `module-info.java` did not declare the service. The foy unit tests that ran it did so on a
  class path or with a service already declared, so only the runtime's module-path IT saw it.
- Status: FIXED by `uses io.vidocq.foy.spi.cdi.CdiContextListeners;` in `foy-core`'s `module-info.java`.

## BUG-20261013-01 — the case check does not detect case folding on Linux case-insensitive mounts

- Date: 2026-10-13 (final review of the security batch).
- Symptom: on a Linux case-insensitive mount (ext4 casefold, vfat/exFAT, CIFS, some container bind
  mounts), a directory resource root still serves `/STATIC/x.txt` from `static/x.txt`, around a
  `/static/*` constraint: the BUG-20261011-05 bypass remains there.
- Minimal repro: by code reading; deploy a directory resource root on a casefold or vfat mount with a
  constraint on `/static/*` and request `/STATIC/x.txt`. Not pinned by a test (no such mount on CI).
- Cause: `ResourcePaths.realNameMatches` reads the real name through `toRealPath(NOFOLLOW_LINKS)`, which
  corrects the case only where the JDK's file system reports case-insensitive-and-preserving (macOS,
  and the real path on Windows). On Linux it returns the requested spelling.
- Status: OPEN (documented limit, in the `realNameMatches` Javadoc, `usage.adoc` and `internals.adoc`:
  serve static resources from jars or from a case-sensitive file system on such hosts). Fix direction:
  a per-segment exact-name check against the directory listing, at a cost per request.

## BUG-20261012-02 — a provider module in two layers registers its CDI context listeners twice

- Date: 2026-10-12 (foy#38).
- Symptom: under Vidocq, the startup report lists `VaubanWebContextsListener` twice, and each
  request and session event activates and deactivates the CDI contexts twice.
- Minimal repro: `LayeredProvidersTest`, where the same provider module sits in a parent layer and in a
  child layer whose loader delegates to the parent one.
- Cause: Vidocq re-layers the application over the boot layer, so `io.vidocq.foy.cdi.vauban` is in
  both layers. `ServiceLoader.load(service, loader)` with the application layer's loader returns its
  provider once per layer. `ServletContainerInitializer` discovery had the same exposure.
- Status: FIXED by `ApplicationSources.providers`, which keeps the first provider of each class
  name (the child layer's), for the CDI context listeners and the initializers.

## BUG-20261012-01 — components of an excluded fragment stay deployed under a shared class loader

- Date: 2026-10-12.
- Symptom: with an absolute ordering that excludes a fragment, its servlets, filters, listeners
  and `ServletContainerInitializer`s are still deployed when the harness loads the classes of
  several jars from one class loader (vidocq#228, `Aordering4Tests.absoluteOrderingTest`).
- Minimal repro: `FoyChappeBootFragmentsTest.aSharedLoaderHarnessExcludesTheFragmentThroughComponentSource`
  (classes load parent-first from `shared.jar`, `META-INF/web-fragment.xml` is found in `ex.jar`).
- Cause: a component was attributed to a jar by the code source of its class only, which names the
  shared jar and not the jar of the fragment.
- Status: FIXED by `FoyChappeBoot.Builder#componentSource`, which attributes each class to its real
  jar for the exclusions, the application roots and the initializers.

## FOY-TLD-001 · 2026-10-11 · A factory can hide its actual listener class

- Symptom: an interface/base descriptor type bypasses deduplication by actual
  listener class when its factory constructs a subtype.
- Minimal repro: `TldListenerLifecycleTest.factoryTypesMustIdentifyTheExactConstructedClassForDeduplication`.
- Cause: `type.isInstance` accepts subtypes while the container deduplicates by
  the declared type before constructing listeners.
- Status: FIXED; factories must return the exact declared class.

## BUG-20261011-01 — duplicate JSP taglib fields overwrite earlier values

- Date: 2026-10-11.
- Symptom: repeated `taglib-uri` fields silently select the last URI.
- Minimal repro: `JspConfigurationTest.duplicateTaglibFieldsAreRejectedInsteadOfSilentlyOverwriting`.
- Cause: collecting fields with `Map.put` loses duplicates.
- Status: FIXED in the JSP-engine SPI branch; reject duplicates and unknown fields.

## BUG-20261011-02 — existing Vauban request-scope test prevents a full local install

- Date: 2026-10-11.
- Symptom: `FoyWebExtensionTest.requestScopedThroughFoyChappeBoot` gets HTTP 500
  with `ContextNotActiveException: RequestScope is not active`.
- Minimal repro: Java 25, local Vauban 0.4.0-SNAPSHOT, `./mvnw -o -ntp -pl foy-cdi-vauban -am test -Dtest=FoyWebExtensionTest#requestScopedThroughFoyChappeBoot -Dsurefire.failIfNoSpecifiedTests=false`.
- Cause hypothesis: existing Vauban request activation gap, also referenced as
  vauban#147 in BUG-20261010-07; unrelated to JSP service discovery.
- Status: FIXED upstream by Vauban `f420ea84` (`RequestContextController` and
  `@ActivateRequestContext`, vauban#147). Verified 2026-10-13: `FoyWebExtensionTest` passes in full
  (11/11, `requestScopedThroughFoyChappeBoot` included) against the current Vauban 0.4.0-SNAPSHOT,
  with no Foy change.

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
- **Statut** : FIXED (the dispatch wrapper goes under the lowest non-HTTP application wrapper, restored in a finally, 1de5b96; review round 37a7de4)
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
  - 2026-10-13 : fixed in 1de5b96. `RequestDispatcherImpl` sets the `ForwardedRequest` /
    `IncludedRequest` / `IncludedResponse` under the lowest non-HTTP wrapper with
    `setRequest`/`setResponse` and restores it in a `finally`; `Invoker.invoke` takes
    `ServletRequest`/`ServletResponse`. Pinned by `DispatcherSemanticsTest`
    (`aPlainRequestWrapperPassedToIncludeReachesTheTarget`,
    `aPlainRequestWrapperPassedToForwardReachesTheTarget`,
    `aPlainResponseWrapperPassedToIncludeReachesTheTarget`,
    `theApplicationChainIsRestoredAfterTheDispatch`,
    `nestedDispatchesUnwindTheApplicationChainInOrder`, `anHttpWrapperIsStillPassedAsBefore`).
    Review round 37a7de4 added `theApplicationChainIsRestoredAfterAForward` and
    `aPlainResponseWrapperPassedToForwardReachesTheTarget`.
    Out of scope: the async dispatch path (`ChappeServletBridge` async `dispatch`) still wraps the
    container request, not `AsyncContext.getRequest()`.

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
- **Statut** : FIXED (`ErrorDispatchRequest` gives every error-page target the location's paths, URI and mapping, 926deae; relative dispatcher resolved against the location, 3fd3288)
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
  - 2026-10-13 : fixed in 926deae. `ErrorDispatchRequest` replaces the `DefaultServlet`-only wrapper
    and overrides `getServletPath`, `getPathInfo`, `getRequestURI`, `getRequestURL` and
    `getHttpServletMapping` from the `DispatchTarget` (Servlet 6.1 section 10.9.1); the query string,
    parameters and `jakarta.servlet.error.*` attributes are unchanged. Static error pages still pass.
    Pinned by `ErrorPageDispatchTest#anErrorPageSeesTheLocationsServletPath`,
    `anErrorPageAfterAnExceptionSeesTheLocationsPaths`, `theHttpServletMappingOfAnErrorPageIsTheLocations`,
    `aPathMappedErrorPageSeesTheServletPathAndPathInfoOfItsLocation`,
    `anErrorPageRequestUrlEndsWithItsLocationPath` and
    `anErrorInsideAForwardGivesTheErrorPageItsLocationButTheOriginalErrorUri`.

## BUG-20261009-04 — decoded paths leak into dispatch URIs (query split, wrapper URIs)

- **Date** : 2026-10-09
- **Statut** : FIXED (dispatch URIs are percent-encoded and a zero-argument async dispatch keeps its path and query apart, 363b082, cae4c77)
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
  - 2026-10-13 : fixed in 363b082. `RequestPaths.encodeUriPath` re-encodes the dispatch path in the
    request URI of `ForwardedRequest`, `AsyncDispatchRequest` and `ErrorDispatchRequest` and in
    `jakarta.servlet.include.request_uri`; `AsyncContextImpl` passes the path and the query apart and
    `DispatchResolver.resolve(path, query)` no longer splits the path; `canonicalDispatchPath` reads
    the target's decoded path. Pinned by `RequestPathsTest.encodesADecodedPathForARequestUri` /
    `encodingAPathIsIdempotent` and `RequestPathCanonicalisationTest.theForwardTargetRequestUriIsEncoded`,
    `theIncludeRequestUriAttributeIsEncoded`, `zeroArgumentAsyncDispatchKeepsADecodedQuestionMarkInThePath`
    and `zeroArgumentAsyncDispatchAfterAForwardUsesTheForwardTargetPath`.
  - 2026-10-13 : review follow-up in cae4c77. A lone or mismatched surrogate is encoded as U+FFFD
    instead of swallowing the next character as `?`. Pinned by
    `RequestPathsTest.surrogatePairsAreEncodedTogetherAndLoneSurrogatesAsReplacementCharacters` and
    `RequestPathCanonicalisationTest.anErrorPageRequestUriIsEncoded`,
    `anAsyncDispatchTargetReportsTheUrlOfItsOwnPath` and
    `zeroArgumentAsyncDispatchAfterANamedForwardUsesTheCallerPath`.

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

## BUG-20261009-09 — getServletConnection id not unique for the JVM lifetime; no HTTP/2 protocol request id

- **Date** : 2026-10-09
- **Statut** : OPEN (chappe follow-up)
- **Module touché** : `foy-core` (`HttpServletRequestImpl.getServletConnection`, `getProtocolRequestId`); needs `chappe-api` `Request`
- **Symptôme** : `getServletConnection().getConnectionId()` is the socket address pair
  (`remote-ip:port-local-ip:port`, `?-?` when Chappe gives no address): two connections that
  reuse the same ephemeral port get the same id, whereas the Javadoc requires an id unique for
  the lifetime of the JVM. `getProtocolRequestId()` is `""` for HTTP/2 instead of the stream id.
- **Reproduction minimale** : two sequential `Connection: close` requests from a client bound to
  the same local port → equal `getConnectionId()`; any HTTP/2 request → `getProtocolRequestId()`
  is `""`.
- **Hypothèse de cause** : Chappe's `Request` API exposes neither a connection identity nor the
  HTTP/2 stream id (`Http2Stream` keeps it internal), so Foy cannot report them.
- **Investigations** :
  - 2026-10-09 (Phase 4 Task 4.10) : interim behaviour documented in the Javadoc. Chappe
    follow-up: add a per-connection id (e.g. a JVM-wide counter assigned on accept) and the HTTP/2
    stream id to `io.vidocq.chappe.api.Request`; Foy then maps them to `getConnectionId()`,
    `getProtocolConnectionId()` (HTTP/2: `""`, HTTP/3: the QUIC connection id) and
    `getProtocolRequestId()`. The chappe repository is not modified here.

## BUG-20261009-10 — dispatch paths stripped of a context-path prefix they never carried

- **Date** : 2026-10-09
- **Statut** : FIXED (2f7fcf0)
- **Module touché** : `foy-core` (`VidocqServletContext.getRequestDispatcher`, `ChappeServletBridge` async dispatch, `HttpServletRequestImpl.getRequestDispatcher`, `AsyncContextImpl.dispatch()`)
- **Symptôme** : paths that are already context-relative were stripped of a leading copy of the
  context path with `startsWith`: in context `/app`, `getServletContext().getRequestDispatcher("/apple.jsp")`
  returned `null` (`"le.jsp"`); in context `/views`, a dispatcher for `/views/x.jsp` served `/x.jsp`;
  `AsyncContext.dispatch("/application/x")` answered an empty 200.
- **Reproduction minimale** : `ContainerGuardsEndToEndTest#aDispatchPathStartingLikeTheContextPathIsNotStripped`,
  `#aDispatchPathEqualToTheContextPathPrefixIsKept`, `#anAsyncDispatchPathIsContextRelative`.
- **Hypothèse de cause** : `ServletRequest.getRequestDispatcher` prepended the context path and the
  servlet context stripped it again, a round trip that misfired on any path starting with the
  context path's characters.
- **Investigations** :
  - 2026-10-09 (Phase 4 final review) : every internal dispatch path is now context-relative; the
    request no longer prepends the context path, the strips and the dead `"/".equals` guards are
    removed, and the zero-argument `dispatch()` takes the canonical context-relative path.

## BUG-20261009-11 — chappe HTTP/1.1 writer truncates header chars to one byte (header injection)

- **Date** : 2026-10-09
- **Statut** : FIXED in chappe (CHAPPE-008, commit `fix(http): validate response header fields everywhere`,
  branch `pr/ybl/chappe-008-014`); mitigated in foy by 2f7fcf0 before that
- **Module touché** : chappe `chappe-http` (`HttpResponseWriter.putAsciiString`)
- **Symptôme** : `putAsciiString` casts each `char` of a header name or value to a `byte`, so a
  character above U+00FF loses its high byte: U+010D U+010A become CR LF on the wire, which lets
  a value such as `"/xčĊSet-Cookie: a=b"` inject a header line.
- **Reproduction minimale** : a Chappe handler answering `Response.builder().header("X", "ačĊSet-Cookie: a=b")`
  → the client receives a separate `Set-Cookie` header.
- **Hypothèse de cause** : the writer assumes ASCII header text and never validates it.
- **Investigations** :
  - 2026-10-09 (Phase 4 final review) : foy now rejects CR, LF, NUL and chars above U+00FF at
    every header entry point (`IllegalArgumentException`) and percent-encodes redirect locations.
    Chappe follow-up: reject (or encode) non-Latin-1 and CR/LF/NUL characters in
    `HttpResponseWriter` itself. The chappe repository is not modified here.
  - 2026-10-12: fixed on the chappe side by CHAPPE-008. Header names must be tokens and values may
    hold only field-vchar, obs-text, SP and HTAB, checked in the API (`IllegalArgumentException`),
    in the HTTP/1.1 writer (500 and close) and in the HTTP/2 encoder (500).

## BUG-20261009-12 — dispatch query string decoded as UTF-8 regardless of the request encoding

- **Date** : 2026-10-09
- **Statut** : CLOSED (by design: every query string is decoded as UTF-8, as the request's own; 418ba09)
- **Module touché** : `foy-core` (`ForwardedRequest`, `AsyncDispatchRequest` parameter merging)
- **Symptôme** : the parameters of a dispatch query string (`getRequestDispatcher("/x?a=%E9")`,
  `AsyncContext.dispatch("/x?a=...")`) are always decoded as UTF-8, even when the request
  character encoding (`setCharacterEncoding`, `<request-character-encoding>`) is another charset.
- **Reproduction minimale** : request encoding `ISO-8859-1`, forward to `/x?a=%E9` → `getParameter("a")`
  is U+FFFD instead of `é`.
- **Hypothèse de cause** : `ForwardedRequest` and `AsyncDispatchRequest` pass
  `StandardCharsets.UTF_8` to the decoder instead of the request's effective encoding.
- **Investigations** :
  - 2026-10-09 (Phase 4 final review) : found in review; not exercised by the TCK.
  - 2026-10-13 : closed by design. The request's own query string is decoded as UTF-8
    (`HttpServletRequestImpl`), so a dispatch query decoded with the request encoding would make one
    parameter name decode differently depending on the dispatch type. The request encoding applies to the
    form body only (documented in `usage.adoc`). Fixed the async parser: `AsyncDispatchRequest` now
    uses `DispatchParameters.View` like forward and include (a malformed escape skips the pair instead
    of throwing, target values precede the original ones), in 418ba09. Pinned by
    `RequestPathCanonicalisationTest#aMalformedEscapeInAnAsyncDispatchQueryIsSkipped` and
    `#asyncDispatchQueryParametersPrecedeTheOriginalOnes`.

## BUG-20261009-13 — resource metadata and openStream may describe different roots

- **Date** : 2026-10-09
- **Statut** : FIXED — `openStream` now opens only the first root's file, the one `metadata`
  describes, and returns `null` when it cannot be opened instead of streaming the next root's file;
  pinned by `ClassPathResourceProviderTest.metadataAndStreamDescribeTheSameRoot`.
- **Module touché** : `foy-core` (`ClassPathResourceProvider.metadata` / `openStream`)
- **Symptôme** : `metadata(path)` reports the first root holding a file at `path`, while
  `openStream(path)` falls through to the next root when opening the first one fails: the default
  servlet can then announce the length and last-modified time of one file and stream another.
- **Reproduction minimale** : two roots with `META-INF/resources/x.txt`, the first unreadable →
  `Content-Length` from the first, body from the second.
- **Hypothèse de cause** : the two lookups iterate the roots independently.
- **Investigations** :
  - 2026-10-09 (Phase 4 final review) : found in review. Fix direction: one lookup returning the
    opened stream with its metadata.

## BUG-20261009-14 — session listeners invoked while session or manager locks are held

- **Date** : 2026-10-09
- **Statut** : FIXED — `createNew` counts itself under a `ReentrantLock` and fires `sessionCreated`
  after releasing it (`close` sets `closed` at once and waits for the creations being announced);
  `changeSessionId` claims the change under the session monitor and fires `sessionIdChanged` outside
  it, an invalidation claimed meanwhile completing once the listener returned. Pinned by
  `SessionListenerLockingTest` (both scenarios blocked 2 s before the fix).
- **Module touché** : `foy-core` (`SessionManager.createNew`, `SessionManager.changeSessionId`, `HttpSessionImpl` invalidation)
- **Symptôme** : `sessionCreated` runs under the manager's close read lock and `sessionIdChanged`
  under the session monitor; a listener that blocks or calls back into another session (or waits
  for another request on the same session) can stall undeploy or deadlock.
- **Reproduction minimale** : an `HttpSessionIdListener` that waits for another thread which calls
  `invalidate()` on the same session → both threads block.
- **Hypothèse de cause** : the locks guarantee that a listener never sees a destroyed session and
  that no session is created after close, at the price of running application code under them.
- **Investigations** :
  - 2026-10-09 (Phase 4 final review) : found in review. Fix direction: claim the state change
    under the lock, fire the listener after releasing it, with a state flag replacing the lock.

## BUG-20261010-01 — the CDI request context is never active for a servlet request

- **Date** : 2026-10-10
- **Statut** : FIXED (branch `feat/foy-request-scope-weld`, foy#18)
- **Module touché** : `foy-core`, `foy-chappe`
- **Symptôme** : a `@RequestScoped` bean injected into a servlet fails on first use:
  `WELD-001303: No active contexts for scope type jakarta.enterprise.context.RequestScoped` under Weld,
  and the same under Vauban; no `@Initialized(RequestScoped.class)` event fires.
- **Reproduction minimale** : `foy-it-weld`, `WeldPortabilityTest.requestScopedBeanIsOnePerRequest`
  and `requestContextEventsFire` on `main`.
- **Hypothèse de cause** : nothing in Foy activated the request context; a Servlet container must make it
  active during `service()` and the request listeners (CDI 4.1 §6.7.1). Every test used `@Dependent` or
  `@ApplicationScoped` beans only.
- **Correction** : `CdiRequestScopeListener` (foy-core) activates the context through
  `RequestContextController` and deactivates it at the end of the request, on the same thread;
  `FoyChappeBoot` registers it ahead of the application's request listeners
  (`ListenerRegistry.addFirst`) whenever a `BeanManager` is given. A `BeanManager` that cannot supply a
  controller leaves the request without a context, as before. The session context is still not activated
  (no portable API).

## BUG-20261010-02 — async timeout lets the pipeline thread and an async thread write the response concurrently

- **Date** : 2026-10-10
- **Statut** : PARTIAL — data race FIXED (Task 5.6 + fix round 1); residual gap below (threads the application manages itself)
- **Module touché** : `foy-core` (`ChappeServletBridge.runPipeline`, `ServletOutputStreamImpl`, `ResponsePipe`)
- **Symptôme** : after an `AsyncContext` timeout, `awaitAsyncIfStarted` returns while the async
  thread (`AsyncContext.start`) may still be writing. The pipeline thread then runs
  `finishBody()` → `ServletOutputStreamImpl.push()`/`ResponsePipe.write` while the async thread is
  inside `write()`/`push()` on the same unsynchronised buffer. `ResponsePipe` is single-producer.
  Possible effects: interleaved or lost body bytes, a corrupted `ByteArrayOutputStream` count, a
  body finished while the async thread still writes (its write then fails with
  "response body already ended"), or, when not yet committed, a buffered head sent while a late
  commit's `head.complete` returns false.
- **Reproduction minimale** : servlet calls `startAsync()`, `setTimeout(50)`, then
  `ac.start(() -> { for (;;) { out.write(block); out.flush(); } })` — the timeout fires while the
  async thread is writing; run repeatedly (race, not deterministic).
- **Hypothèse de cause** : the Task 5.4 streaming model assumes one writer at a time, which the
  async-completion handshake guarantees except on the timeout path: `AsyncContextImpl.awaitCompletion`
  completes internally on timeout without waiting for (or fencing) the async thread.
- **Investigations** :
  - 2026-10-10 (Task 5.4 review, fix round 1) : found in review; not fixed in 5.4 by ruling —
    Task 5.6 (async lifecycle: timeout → error dispatch, onComplete at the end of the cycle) owns it.
    Fix direction: after a timeout, fence the response (writes from the stale async thread fail or
    are dropped) before the pipeline touches the stream, or serialise stream access.
  - 2026-10-10 (Task 5.6) : fixed with both. (1) One `ReentrantLock` in `ServletOutputStreamImpl`
    serialises write/flush/close/push/finish/reset/toByteArray; the response writer and
    `flushBuffer`/`finishBody` take it too (before the writer's monitor). `abort` aborts the pipe
    before taking the lock, so a writer blocked on a full pipe wakes up. (2) When an async cycle
    ends (complete, dispatch, timeout, error), `AsyncContextImpl.awaitCycleEnd` lets the pipeline
    thread claim the output (`HttpServletResponseImpl.claimOutput`) before any listener runs: from
    then on a write/flush/close from another thread fails with `IOException` (a stale loop stops);
    through the `PrintWriter` the characters are discarded and `checkError()` turns true. A new
    `startAsync` re-opens the output (`openOutput`). Tests: `ResponseOutputFenceTest`,
    `ServletAsyncEndToEndTest.lateAsyncWritesFailOnceTheTimeoutFired` and
    `aCommittedResponseTimingOutStopsTheStaleWriter` (writer looping past the timeout, latch-driven).
  - 2026-10-10 (Task 5.6 fix round 1) : (a) lock-order deadlock fixed: PrintWriter's println(x),
    printf and format hold the writer monitor before calling write(); `ResponseWriter` now overrides
    them to take the stream lock first, so the order is always stream lock → monitor (test
    `printlnOnOneThreadAndFlushBufferOnAnotherDoNotDeadlock`, hung 10 s before). (b) Per-cycle claim:
    `AsyncContext.start` registers its virtual thread before it runs; when a cycle ends those threads
    are retired for good (`claimOutput(cycleThreads)`), so a re-opened output (new `startAsync`)
    does not let them write again. (c) Every cycle, including one opened by an ASYNC target that then
    throws, ends in `ChappeServletBridge.runAsyncCycles` (claim, onError, container completion).
    (d) `finish`/`abort` are mutually exclusive (one `AtomicBoolean` claimed by compare-and-set).
    **Residual gap** : a thread the application created itself (its own executor, not
    `AsyncContext.start`) is refused from the end of its cycle only until a later cycle re-opens
    the output; it is not known to the container, so it cannot be retired. Memory safety holds
    (every output operation takes the lock); only the logical interleaving of bytes is the app's.

## BUG-20261010-03 — kept non-blocking bytes are sent blocking at the end of a cycle, with no Foy-side bound

- **Date** : 2026-10-10
- **Statut** : OPEN (documented limit, Phase 5 exit)
- **Module touché** : `foy-core` (`ServletOutputStreamImpl.endNonBlocking`, `ChappeServletBridge.finishBody`)
- **Symptôme** : in non-blocking mode (`WriteListener`), a write the pipe cannot take is kept by
  the stream (at most one buffer plus one write). When the async cycle ends (complete, dispatch,
  timeout, error) with bytes still kept, the stream returns to blocking mode and `finishBody`
  sends them on the pipeline thread, blocking until the client reads them. A client that never
  reads holds the pipeline thread and the connection until chappe's write timeout closes the
  channel (`ServerConfig.writeTimeout`, 30 s per blocked write by default; never with a zero
  write timeout).
- **Reproduction minimale** : async servlet with a `WriteListener` writing until `isReady()` is
  `false`, then `complete()`; the client sends the request and never reads. The pipeline thread
  stays in `ResponsePipe.write` until the write timeout fires.
- **Hypothèse de cause** : by design of Task 5.8 (blocking mode after the cycle keeps byte order and
  matches what blocking mode does anyway); the container has no write deadline of its own.
- **Investigations** :
  - 2026-10-10 (Task 5.8 review, deferred minor; recorded at the Phase 5 exit, Task 5.10) : fix
    direction — bound the end-of-cycle drain with a container deadline (abort the body, so chappe
    drops the connection) or rely on a mandatory non-zero chappe write timeout.

---

## BUG-20261010-04 — startAsync() accepted after upgrade(); a live body pump could race chappe's upgrade drain

- **Date** : 2026-10-10
- **Statut** : FIXED (commit `fix(core): close the upgrade gaps of the Phase 5 final review`)
- **Module touché** : `foy-core` (`HttpServletRequestImpl.startAsync`, `ChappeServletBridge.upgraded`)
- **Symptôme** : `upgrade()` refused an async-started request, but `startAsync()` after `upgrade()`
  was accepted. A `ReadListener` set then started a body pump still alive when the bridge returned
  the `ConnectionUpgrade`: chappe drained the unread body while the pump read the same channel, and
  the connection was upgraded although body bytes went to the pump.
- **Reproduction minimale** : `ServletUpgradeEndToEndTest#startAsyncAfterUpgradeThrowsIse`
  (`async accepted` before the fix). Pump race (experiment, `startAsync` guard removed): a servlet
  calls `upgrade()`, `startAsync()`, sets a ReadListener on a `Content-Length: 10` body whose bytes
  arrive after the upgrade; without the hand-back the client got `101 Switching Protocols`.
- **Hypothèse de cause** : missing mirror of the `upgrade()`-after-`startAsync()` guard; `upgraded()`
  only stopped the pump (`endNonBlockingIo`) without joining it.
- **Fix** : `startAsync()` throws `IllegalStateException` once an upgrade handler is set; `upgraded()`
  hands the body back (`handBackInput()`: interrupt + join a live pump) before returning the
  `ConnectionUpgrade`.

---

## BUG-20261010-05 — WebConnection.close() cut a non-blocking write still in flight

- **Date** : 2026-10-10
- **Statut** : FIXED (commit `fix(core): close the upgrade gaps of the Phase 5 final review`)
- **Module touché** : `foy-core` (`WebConnectionImpl`, `UpgradedOutputStream`)
- **Symptôme** : `out.write(lastFrame); wc.close();` from `onWritePossible` lost the last frame: the
  write runs on a writer thread and `close()` closed the connection under it.
- **Reproduction minimale** : `ServletUpgradeEndToEndTest#closeDeliversTheNonBlockingWriteInFlight`
  (0 of 4 MiB received before the fix).
- **Hypothèse de cause** : close did not wait for the write in flight.
- **Fix** : the connection closes once the write in flight is done, waiting at most
  `WebConnectionImpl.CLOSE_WRITE_GRACE` (2 s); past it the connection closes anyway
  (`#closeAfterANonBlockingWriteIsBoundedOnAStalledPeer`).

## BUG-20261010-06 — upgrade() called during an ASYNC dispatch is silently dropped

- **Date** : 2026-10-10
- **Statut** : FIXED (3abdaff, 0c03460 — upgrade() throws IllegalStateException outside the REQUEST dispatch the bridge reads)
- **Module touché** : `foy-core` (`HttpServletRequestImpl.upgrade`, `ChappeServletBridge`)
- **Symptôme** : a servlet reached by `AsyncContext.dispatch()` that calls `upgrade(...)` gets no
  exception, the response switches to discard mode, and the client receives an empty plain HTTP
  response (possibly carrying the 101 status and `Upgrade` headers set by the application). The
  handler instance is never initialised nor destroyed.
- **Reproduction minimale** : servlet A calls `startAsync()` then `dispatch("/b")`; servlet B calls
  `setStatus(101)`, `setHeader("Upgrade", "x")`, `upgrade(Handler.class)`.
- **Hypothèse de cause** : `upgrade()` only refuses when `isAsyncStarted()`, which is false during
  the ASYNC dispatch; the bridge reads `upgradeHandler()` only on the REQUEST exit, never after an
  async cycle.
- **Fix envisagé** : throw `IllegalStateException` from `upgrade()` when the dispatcher type is
  `ASYNC` (or whenever an async cycle exists), mirroring "upgrade() after startAsync()". No TCK
  test exercises this path.
- **Investigation 2026-10-13** : fixed with a dedicated `inAsyncDispatch` flag set by the bridge around both async-dispatch invocations (the dispatcher type is untouched). Pinned by `ServletUpgradeEndToEndTest.upgradeDuringAsyncDispatchThrowsIse`.
- **Investigation 2026-10-13 (final review)** : an error page dispatched after the REQUEST invocation
  (`maybeHandleError`) passed the async-only guard and lost its body to the same dropped upgrade. The
  guard is now a whitelist: the bridge sets a `volatile upgradable` flag only around the REQUEST
  invocation whose `upgradeHandler()` it reads (nested forwards and includes stay upgradable) and
  clears it in a `finally`; `upgrade()` throws `IllegalStateException` ("upgrade() outside the request
  dispatch") whenever the flag is down, so the `inAsyncDispatch` flag and its async-dispatch wrappers are
  gone. Pinned by `ServletUpgradeEndToEndTest.upgradeFromAnErrorPageThrowsIse` (and
  `upgradeDuringAsyncDispatchThrowsIse`).

## BUG-20261010-07 — no CDI context on AsyncContext.start() threads nor in AsyncListener.onComplete

- **Date** : 2026-10-10
- **Statut** : FIXED — `onComplete` now fires before `requestDestroyed` (on the pipeline thread, contexts
  bound), and `AsyncContextImpl.start` runs the `Runnable` with the container's context listeners
  (`addFirst`/`registerFirst`, i.e. the CDI ones) bound on its thread through
  `ListenerRegistry.bindingContainerContexts`, with a request wrapper as the event's request so a listener
  keyed by request keeps the serving thread's state. The start thread shares the session and gets a request
  context of its own. The application's request listeners are not fired again. Pinned by
  `AsyncContainerContextsTest`.
- **Module touché** : `foy-core` (`AsyncContextImpl`, `ChappeServletBridge`)
- **Symptôme** : a `@RequestScoped` or `@SessionScoped` bean used from the `Runnable` given to
  `AsyncContext.start()`, or from `AsyncListener.onComplete`, fails with `ContextNotActiveException`.
  Async dispatches are not affected: `ChappeServletBridge.runAsyncCycles` runs them on the pipeline
  thread, which keeps the contexts bound until `fireRequestDestroyed`. `onTimeout` and `onError` fire on
  the pipeline thread before `fireRequestDestroyed` (`AsyncContextImpl.awaitCycleEnd`), so the contexts
  are active there; `onComplete` fires after it (`lastCycle.endCycle()` in `ChappeServletBridge.handle`),
  so no context is active in `onComplete`; `onStartAsync` runs on the thread that calls `startAsync()`,
  without contexts when that is an `AsyncContext.start()` thread. The `SessionScoped` Javadoc requires the
  session context to be active when the container calls an `AsyncListener`.
- **Reproduction minimale** : under Weld (`foy-it-weld`), a servlet calls `startAsync()`, then
  `AsyncContext.start(() -> { requestScopedBean.ping(); ac.complete(); })`: the async thread gets
  `ContextNotActiveException`. Under Vauban the same holds for a `@SessionScoped` bean
  (`foy-cdi-vauban`); Vauban's request context is never active anyway (vauban#147).
- **Hypothèse de cause** : the CDI contexts are bound per thread by the request listeners Foy registers
  first when given a `BeanManager` — `CdiRequestScopeListener` (foy#18, through
  `RequestContextController`) and the `CdiContextListeners` services (foy#21; on Vauban
  `VaubanWebContextsListener`, a `ThreadLocal` in `vauban-webcontexts`). They bind on the pipeline thread
  in `requestInitialized` and unbind in `requestDestroyed`. `AsyncContextImpl.start` runs the `Runnable`
  on a new virtual thread and propagates no context to it; `onComplete` runs after `requestDestroyed`.
- **Fix envisagé** : activate the contexts around the `Runnable` in `AsyncContextImpl.start` (needs a
  per-thread hook in the `CdiContextListeners` contract and a request context per thread), and fire
  `onComplete` before the request listeners are destroyed, or bind the contexts around it.

## BUG-20261010-08 — error pages run without the CDI request and session contexts

- **Date** : 2026-10-10
- **Statut** : FIXED — on the mapped path `requestDestroyed` fires after `maybeHandleError` and after
  `onComplete`; a denial's error page runs before it; `rejected(...)` and the unmapped-path branch run their
  error page inside `requestInitialized`/`requestDestroyed`. Pinned by `RequestListenersAroundErrorPagesTest`;
  full official TCK run recorded in the commit.
- **Module touché** : `foy-core` (`ChappeServletBridge`)
- **Symptôme** : an error page (a `<error-page>` location dispatched after an exception, after
  `sendError(...)`, or after an async timeout) runs with no CDI context driven by Foy's request
  listeners active: a `@RequestScoped` bean (Weld) or a `@SessionScoped` bean (Vauban) used there fails
  with `ContextNotActiveException`.
- **Reproduction minimale** : by code reading (not pinned by a test): a servlet calls `resp.sendError(404)`
  (or throws); a `<error-page>` maps that status (or exception) to a servlet that injects a
  `@RequestScoped` bean; under Weld (`foy-it-weld`) the error page fails with
  `ContextNotActiveException`. Requests that reach no listener at all have the same gap: the
  unmapped-path 404 branch and `rejected(...)` dispatch their error page without `fireRequestInitialized`.
- **Hypothèse de cause** : on the mapped path, `ChappeServletBridge.handle` fires `requestDestroyed`
  (where `CdiRequestScopeListener` deactivates the request context and the `CdiContextListeners`
  listeners unbind the session) before `maybeHandleError` dispatches the error page; the async timeout's
  error page goes through the same `maybeHandleError`. The order dates from foy#18 (request listeners
  added before the CDI work); Tomcat fires request-destroyed after its error pages (`StandardHostValve`).
- **Fix envisagé** : fire `requestDestroyed` after the error handling (after `maybeHandleError`, still
  before `onComplete`), and initialise the request listeners around the unmapped-path error dispatch;
  needs a full official Servlet TCK run (`run-official-tck-servlet6.1.sh --all`) since every error
  dispatch changes order.

## BUG-20261011-03 — CLIENT-CERT authentication is not supported

- **Date** : 2026-10-11
- **Statut** : FIXED (2026-10-12, branch `pr/ybl/foy-client-cert`, on chappe#29: TLS client
  authentication and `Request.tls()`). CLIENT-CERT authenticates the TLS client certificate through
  `IdentityStore.validate(X509Certificate[])`, and `foy-tck` serves the security wars that target it from
  an `https` Arquillian container: `spec.security.clientcert.ClientCertTests#clientCertTest` and
  `spec.security.clientcertanno.ClientCertAnnoTests#clientCertTest` now pass.
- **Module touché** : `foy-core` (`WebSecurity.authenticatorFor`, `DenyingAuthenticator`), chappe (TLS)
- **Symptôme** : an application whose `login-config` says `<auth-method>CLIENT-CERT</auth-method>` gets
  `403` on every protected resource, whatever certificate the client presents; `login()` throws
  `ServletException`. The official TCK `spec.security.clientcert.ClientCertTests` and
  `spec.security.clientcertanno.ClientCertAnnoTests` (1 test each) fail.
- **Reproduction minimale** : any application with `auth-method` CLIENT-CERT and a
  `security-constraint` with an `auth-constraint`: a request to the constrained path answers `403`
  (a `WARNING` at deploy says the method is not supported).
- **Hypothèse de cause** : not implemented by decision. It needs chappe TLS client authentication
  (requested or required `SSLEngine` client auth) and the peer certificate chain exposed to Foy
  (`jakarta.servlet.request.X509Certificate`), a certificate-to-identity mapping in the identity SPI,
  and an `https` Arquillian container in `foy-tck` for the two TCK tests.
- **Fix envisagé** : chappe exposes the peer certificates; Foy adds a CLIENT-CERT authenticator mapping
  the subject to an `Identity` through the store; `foy-tck` gets a TLS container.

## BUG-20261011-04 — SecFormTests.test2 and test2_anno need a JSP engine

- **Date** : 2026-10-11
- **Statut** : ACCEPTED GAP (until a JSP-engine SPI exists)
- **Module touché** : `foy-tck` (no Foy defect)
- **Symptôme** : `servlet.tck.spec.security.secform.SecFormTests.test2` and `test2_anno` fail: after
  a failed login, the expected error page content is never produced.
- **Reproduction minimale** : run `./run-official-tck-servlet6.1.sh --no-install -Dtest=SecFormTests`:
  a failed `j_security_check` forwards to `form-error-page` `/error.jsp`, which lands on `*.jsp`; Foy
  has no JSP engine, so the page is not executed.
- **Hypothèse de cause** : the TCK test application uses JSP pages for its login error page. FORM
  authentication itself behaves (the 23 other SecForm tests pass).
- **Fix envisagé** : the JSP-engine SPI plugged by Ibarra (Jakarta Pages 4.0), like the 3 JSP tests of
  `ServletContext40Tests`.

## BUG-20261011-05 — case-sensitive constraint matching vs a case-insensitive file system

- **Date** : 2026-10-11
- **Statut** : FIXED (a182b8b, e5bf01d — real-name case check in ClassPathResourceProvider and DefaultServlet)
- **Module touché** : `foy-core` (`ConstraintTable`, container default servlet, resource providers)
- **Symptôme** : security constraints match url-patterns case-sensitively, as the specification
  requires, but a resource provider over a case-insensitive file system (macOS directory roots, Windows)
  serves `/STATIC/x` from the same file as `/static/x`. A `/static/*` constraint therefore does not
  protect `/STATIC/x`, which the default servlet serves: a bypass by case.
- **Reproduction minimale** : on macOS, deploy with a directory resource root holding
  `META-INF/resources/static/x.txt` and a `security-constraint` on `/static/*` with an `auth-constraint`;
  `GET /static/x.txt` answers `401`, while `GET /STATIC/x.txt` is expected to answer `200` with the
  file (by code reading and the Phase 6 preflight scan, S-11; not pinned by a test yet).
- **Hypothèse de cause** : the constraint decision runs on the canonical request path, the file lookup is
  delegated to a file system that folds case.
- **Fix envisagé** : make the default servlet (or the resource provider) refuse a resource whose resolved
  real name differs in case from the request path (404), so the served path is always the matched one.
- **Investigation 2026-10-13** : fixed by `ResourcePaths.realNameMatches` (real name via `toRealPath(NOFOLLOW_LINKS)`),
  applied in `ClassPathResourceProvider.DirLoc.resolve` and `DefaultServlet.locate` (any `file:` provider).
  Pinned by `ClassPathResourceProviderTest.aPathThatDiffersInCaseFromTheRealNameIsMissing` and
  `WebSecurityEndToEndTest.aResourceRequestedWithAnotherCaseIsNotServedAroundAConstraint` (skipped on case-sensitive file systems).

## BUG-20261011-06 — CallbackSerializerTest.holdWaitsForTheRunningCallbackAndDefersTheQueuedOnes is flaky

- **Date** : 2026-10-11
- **Statut** : FIXED (test only; the test now releases the first callback only once `hold()` took
  effect, through `CallbackSerializer.isHeld()`; reproduced deterministically by delaying the holder
  thread 300 ms, which fails the old test at the same assertion and passes the new one)
- **Module touché** : `foy-core` tests (`CallbackSerializerTest`)
- **Symptôme** : the test failed once in three full `./mvnw -ntp clean install` runs, at line 108
  (`assertFalse(second.await(200, ms))` got `true`: the queued callback ran although `hold()` was
  expected to defer it). It passes on reruns.
- **Reproduction minimale** : run `./mvnw -ntp -pl foy-core test -Dtest=CallbackSerializerTest` in a
  loop under load; it fails now and then.
- **Hypothèse de cause** : a race in the test, not in `CallbackSerializer`: the holder virtual thread is
  started and `proceed.countDown()` is called at once, so the first callback may end and the serializer
  start the second one before the holder thread has called `hold()`.
- **Fix envisagé** : release `proceed` only once the holder thread is blocked in `hold()` (wait for its
  state, or add a test hook signalling that `hold()` is waiting).

## BUG-20261011-07 — a security denial on the redirect or unmapped branch runs without request listeners

- **Date** : 2026-10-11
- **Statut** : FIXED — the directory-redirect and unmapped-path branches run the security gate inside the
  request listeners. Pinned by `RequestListenersAroundErrorPagesTest.aDenialOnTheDirectoryRedirectRunsInsideTheRequestListeners`.
- **Module touché** : `foy-core` (`ChappeServletBridge.gate`)
- **Symptôme** : on the directory-redirect branch of the default servlet and on the unmapped-path branch,
  `WebSecurity.admit` (and a FORM forward to the login page) runs before any `requestInitialized`: a
  login page reached through those branches has no CDI request or session context driven by Foy's
  request listeners.
- **Reproduction minimale** : by code reading: a FORM application whose login page injects a
  `@RequestScoped` bean, and a constraint on an unmapped path; a request to that path forwards to the
  login page outside the request listeners (same family as BUG-20261010-08).
- **Hypothèse de cause** : those two branches never fire the request listeners; `gate` was added there to
  fail closed.
- **Fix envisagé** : fire the request listeners around those branches, or restrict them to mechanisms
  that do not forward (BASIC).

## BUG-20261011-08 — a fragment conflict on security-role-ref is silently resolved

- **Date** : 2026-10-11
- **Statut** : FIXED (c2c421f — fragment role refs merged through MergeSlots.Keyed)
- **Module touché** : `foy-core` (`ComponentMerger.servlets`)
- **Symptôme** : when two `web-fragment.xml` declare the same servlet with a `security-role-ref` of the
  same `role-name` but different `role-link`s, and `web.xml` does not reference that role-name, the
  first fragment in the ordering wins silently (`putIfAbsent`). Section 8.2.3 makes such a conflict an
  error that fails the deployment, as Foy already does for conflicting `init-param`s.
- **Reproduction minimale** : fragments A and B both declare servlet `s` with
  `<security-role-ref><role-name>MGR</role-name><role-link>admin</role-link>` (A) and
  `<role-link>user</role-link>` (B); deploy: it succeeds, and `isUserInRole("MGR")` in `s` follows A.
- **Hypothèse de cause** : role refs from fragments are merged with `Map.putIfAbsent` instead of the
  `MergeSlots.Keyed` slot used for `init-param` (which records the source and reports a conflict).
- **Fix envisagé** : merge fragment role refs through a `Keyed<String>` per servlet (web.xml entries
  override, equal values from two fragments are not a conflict), and fail the deployment otherwise.
- **Investigation** : 2026-10-13, fixed on `pr/ybl/foy-security-batch`. Fragment role refs are merged through
  `MergeSlots.Keyed`; web.xml wins per role-name, equal values are not a conflict, and a disagreement fails the
  deployment. Pinned by `FragmentMergerTest.securityRoleRefConflictBetweenFragmentsFails`.

## BUG-20261011-09 — InMemoryIdentityStore digest encodes the password with Charset.encode

- **Date** : 2026-10-11
- **Statut** : FIXED (392c400 — strict UTF-8 encoder into an exact-size wiped buffer)
- **Module touché** : `foy-core` (`InMemoryIdentityStore.digest`)
- **Symptôme** : the password is encoded with `StandardCharsets.UTF_8.encode(CharBuffer)`, whose
  coding error action is REPLACE: two passwords differing only by lone (unpaired) surrogates encode to the
  same bytes (`?`), so they collide and either one authenticates. `Charset.encode` may also grow its
  output buffer, leaving an intermediate copy of the encoded password that is never wiped (only the
  final `ByteBuffer`'s array and the copied `byte[]` are), and the returned `ByteBuffer`'s backing array
  itself is not wiped.
- **Reproduction minimale** : register `"a\uD800"` and authenticate with `"a\uDC00"`: by code reading,
  both digest to SHA-256(salt + `a?`) and the login succeeds.
- **Hypothèse de cause** : convenience encoder with REPLACE semantics and an internal buffer growth policy.
- **Fix envisagé** : use `UTF_8.newEncoder().onMalformedInput(REPORT).onUnmappableCharacter(REPORT)`
  into a `ByteBuffer` sized `maxBytesPerChar * length` (exact upper bound, no reallocation), refuse a
  password that does not encode (no identity), and wipe that buffer's backing array.
- **Investigation** : 2026-10-13, fixed on `pr/ybl/foy-security-batch`. `digest` now throws
  `IllegalArgumentException` on a password that is not valid UTF-16; `Builder.user` rejects it before
  registering anything; `validate` digests the decoy then refuses it. Pinned by
  `InMemoryIdentityStoreTest.aMalformedCandidateIsRefused` (and `aPasswordWithALoneSurrogateDoesNotDigest`,
  `aMalformedPasswordCannotBeRegistered`).

## BUG-20261011-10 — FoyVaubanBootstrap offers no way to configure an IdentityStore

- **Date** : 2026-10-11
- **Statut** : FIXED (2026-10-11), on the Vidocq side by vidocq#222 (merged 2026-10-11, closes
  vidocq#220): the Vidocq Servlet extension passes an `IdentityStore` bean or the
  `vidocq.servlet.users.*` configuration to `FoyChappeBoot`. No merge commit exists (the PR was
  rebased onto vidocq `main`); its commits there are `a4c11687` (feat: configure Foy's identity
  store and transport rules) to `12518f83`. Found by code reading in the final Phase 6 review.
- **Module touché** : `foy-cdi-vauban` (`FoyVaubanBootstrap`)
- **Symptôme** : the identity store is configured through `FoyChappeBoot` only; an application booted
  with `FoyVaubanBootstrap` (CDI) cannot pass an `IdentityStore`, so its security settings keep the
  default empty store and no BASIC, FORM or programmatic login can ever succeed: every protected
  resource answers 401 (or the login page / error page) for every credential.
- **Reproduction minimale** : boot through `FoyVaubanBootstrap` an application with a
  `security-constraint` and an `auth-constraint`; send valid-looking Basic credentials: `401`.
- **Hypothèse de cause** : `SecuritySettings`/`identityStore` were wired into `FoyChappeBoot.Builder` in
  Phase 6, not into the Vauban bootstrap.
- **Fix envisagé** : accept an `IdentityStore` in the Vauban bootstrap builder, or resolve one as a CDI
  bean (an `IdentityStore` bean in the application), and pass it to the deploy options.

## BUG-20261011-11 — chappe drains an over-cap request body without bound

- **Date**: 2026-10-11
- **Status**: FIXED by chappe#28 (CHAPPE-020 chunked cap, CHAPPE-021 maxDrainSize); pinned by
  ChunkedBodyLimitEndToEndTest.anEndlessChunkedBodyIsCutOffAfterTheAnswer (found in Phase 7)
- **Module**: chappe (`chappe-http`, `HttpBodyReader.drain`); seen from `foy-core`
- **Symptom**: once `maxChunkedRequestBodySize` trips, Foy stops reading the body and answers, but
  chappe's `HttpBodyReader.drain` still reads the rest of a chunked body before the next request on
  the connection or its close. Memory stays bounded (the bytes are discarded), but the connection and
  its virtual thread are held for as long as the client keeps sending.
- **Minimal repro**: `ChunkedBodyLimitEndToEndTest` (`foy-core`) with an endless
  `HttpRequest.BodyPublishers.ofInputStream` publisher: Foy gives up at the cap, but the connection
  keeps reading the upload (found by code reading; no committed test, since such a test never ends).
- **Cause hypothesis**: chappe caps only `Content-Length` bodies (`ServerConfig.maxRequestSize`); a
  chunked body is drained to its end whatever its size.
- **Planned fix**: in chappe, cap chunked bodies at `maxRequestSize` and close the connection instead of
  draining past the cap.

## BUG-20261011-12 — the web.xml version is never the effective Servlet version

- **Date**: 2026-10-11
- **Status**: FIXED by the commit `fix(core): apply the web.xml version as the effective Servlet
  version` (branch `pr/ybl/effective-version`). Found by Vidocq: the official Servlet TCK on the
  assembled runtime, against Foy `main` `ee6c899` (vidocq#217).
- **Module**: `foy-core` (`DescriptorMerger`, `WebAppDescriptor`), seen through `foy-chappe`
  (`FoyChappeBoot.build`)
- **Symptom**: `ServletContext.getEffectiveMajorVersion()` / `getEffectiveMinorVersion()` always
  answer 6 and 1, whatever the `web-app/version` attribute. The official tests
  `ServletContext30Tests.getEffectiveMajorVersionTest` and `getEffectiveMinorVersionTest` (api and
  pluggability families) fail on the runtime: a `version="5.0"` web.xml expects 5 and 0.
- **Minimal repro**: `FoyChappeBootTest.webXmlVersionIsTheEffectiveVersion`.
- **Cause**: no product code called `WebAppModel.Builder.effectiveVersion`; only the foy-tck harness
  (`VidocqDeployableContainer`) did, so the in-reactor TCK passed and hid the gap.
- **Fix**: `DescriptorMerger` (the deploy path shared by `FoyChappeBoot` and the TCK harness) applies
  the effective descriptor's version. Fragments never change it (`FragmentMerger` keeps web.xml's), nor
  does `metadata-complete`. `WebAppDescriptor.version()` is now `null` when no version is declared (it
  was `"6.0"`), so an application without web.xml or without a version keeps the container version
  6.1. The harness override is removed, so the TCK guards the product path.
