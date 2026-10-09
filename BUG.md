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
