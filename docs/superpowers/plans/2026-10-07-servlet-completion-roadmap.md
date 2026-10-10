# Foy — Servlet 6.1 Completion Roadmap

> Master plan. Each phase gets its own bite-sized implementation plan in this
> folder when it starts (Phases 1 and 2 are already written). Phases are
> ordered: a later phase assumes the earlier ones are merged.

**Goal:** turn Foy from a "TCK-harness-assisted" Servlet engine into a
self-standing Jakarta Servlet 6.1 implementation whose product boot path does
everything the spec requires, with build-time code generation as the primary
mechanism, and pass the official TCK.

**Baseline (re-measured 2026-10-07 on `main` @ `65b1197`, chappe/vauban
0.4.0-SNAPSHOT):** `./run-official-tck-servlet6.1.sh --all` → 1714 run,
**921 pass**, 793 errors, 11 min. `api.*` 821/859, `pluggability.*` 5/646,
`spec.*` (incl. security) 95/207, `compat.*` 0/2. Identical to the
2026-06-12 re-run.

## Key finding that drives the ordering

The TCK score largely measures **the test harness**, not the product.
`FoyChappeBoot.build()` (the product boot path) only discovers CDI beans
annotated `@WebServlet/@WebFilter/@WebListener` by reflection, and never:
calls `init()`/`destroy()`, reads `web.xml`, runs `ServletContainerInitializer`s,
materialises dynamic registrations, calls `markInitialized()`, or registers
with `CrossContextRegistry`. All of that lives in `foy-tck`
(`ServletTestHarness.start()`, `VidocqDeployableContainer.deploy()`).
`WebXmlContributor` in foy-core is dead code. Fixing anything else first would
improve the harness, not Foy.

## Code-generation doctrine (applies to every phase)

Resolution order for every piece of web-component metadata and every
instantiation, most preferred first:

1. **APT** (`foy-processor`) — `X$$FoyComponent` generated at compile time
   (descriptor literal + `new X()`), registered via `META-INF/services`.
2. **BuildCompatibleExtension** (`foy-cdi-vauban`, run by `vauban-processor`
   at build time) — CDI-managed web components: default scope, build-time
   validation, synthetic index bean. No runtime bean walk.
3. **Class-File API (JEP 484) at runtime, degraded mode** — for classes that
   were not processed (external, non-enriched jars; TCK wars): annotations are
   decoded from the class bytes, the factory is a hidden class emitted with
   `java.lang.classfile`. One INFO line per class.
4. **Reflection, last resort** — only when 3 is impossible (module not open
   to Foy). One WARNING line per class.

Counters (`preGenerated`, `classFileGenerated`, `reflective`) are exposed so
tests can assert which path was taken. Pattern mirrored from cassini's
`AdapterRegistry` / `RuntimeAdapterGenerator` and vauban's processor + BCE
pipeline. A `foy-maven-plugin:generate` goal pre-enriches external jars
(Phase 2b), as `cassini-maven-plugin:generate` and `vauban-maven-plugin` do.
An external WAR is converted at build time (`war-import`, Phase 2b, option A)
or, in degraded mode, deployed at runtime by `foy-war` (Phase 3b, option B).

## Phases

### Phase 0 — Hygiene and quick wins (folded into the Phase 1 plan, tasks 0.x)
- `foy-tck/README.md` rewritten for the in-reactor `tck` profile (it still
  describes `vidocq-servlet-chappe-tck-runner` out of reactor).
- `run-official-tck-servlet6.1.sh`: stale "standalone hors reactor" echo, and
  it calls `mvn` instead of `./mvnw`.
- `TCK.md` §0 entry for the 2026-10-07 re-measurement.
- **Form POST parameters (§3.1.1)** — `application/x-www-form-urlencoded`
  bodies never reach `getParameter*`
  (`HttpServletRequestImpl.java:185`, "query-string only for this milestone").
- `BUG.md` entries for the confirmed gaps that are bugs rather than missing
  features (form params, `changeSessionId` UOE, expired sessions dropped
  without `sessionDestroyed`, named dispatcher setting `forward.*`).

### Phase 1 — Product bootstrap in foy-core (plan: `2026-10-07-phase1-product-bootstrap.md`)
`WebAppModel` (single deployment description) + `WebAppDeployer` (the
lifecycle, moved out of the harness) + `Deployment` (undeploy/destroy).
Harness and `FoyChappeBoot` both become thin adapters over it. `web.xml`
loading in the product path; annotation/web.xml merge rules (§8.2.3,
`metadata-complete`). **Exit:** TCK ≥ 921 with the harness delegating to
foy-core; product path covered by foy-core tests.

### Phase 2 — Static code generation (plan: `2026-10-07-phase2-static-codegen.md`)
`io.vidocq.foy.spi.gen` SPI, `foy-processor` (APT), `WebComponentRegistry`
with Class-File API fallback then reflection, `FoyWebExtension` BCE,
class index for `@HandlesTypes`. **Exit:** no `getAnnotation` /
`getDeclaredConstructor` outside the reflective tier; TCK ≥ 921 with the TCK
wars served through the Class-File tier.
**Phase 2b — Enriching what was not compiled with Foy:**
- `foy-maven-plugin:generate` — pre-generates `$$FoyComponent` classes and the
  class index for external, non-enriched jars from their bytecode (Class-File
  API, no sources), as `cassini-maven-plugin:generate` does (same split-package
  / repackaging rules for named modules).
- **Option A — `foy-maven-plugin:war-import`:** a WAR is a *build input*, not a
  runtime artefact. The goal explodes the WAR into native artefacts —
  `WEB-INF/classes` → an application jar, `WEB-INF/lib/*.jar` → dependencies,
  `WEB-INF/web.xml` → `META-INF/web.xml`, root static content →
  `META-INF/resources/` — then runs the bytecode generation above and the
  vauban generator for its CDI beans. The result is AOT-friendly and goes
  through the APT/BCE tiers like any native application.
- Add `foy-processor` + `foy-cdi-vauban` to the vidocq runtime codegen bundle
  (cross-repo PR in `vidocq`).

### Phase 3 — Deployment descriptors and pluggability (native, war-less layout)
The **native deployment model has no WAR**: the application is a set of jars on
the module path. Mapping of the WAR concepts:

| WAR | Native Foy / Vidocq |
|---|---|
| `WEB-INF/classes` | application classes, processed by `foy-processor` + `vauban-processor` |
| `WEB-INF/lib/*.jar` | Maven dependencies |
| `WEB-INF/web.xml` | `META-INF/web.xml` (`WEB-INF/web.xml` on the class path also accepted, Phase 1) |
| `WEB-INF/lib/x.jar!/META-INF/web-fragment.xml` | `META-INF/web-fragment.xml` in every dependency jar |
| static content at the WAR root | `META-INF/resources/` in any jar (§4.6 convention) |

Scope: full `web.xml` schema coverage (welcome-file-list, mime-mapping,
security-constraint, login-config, security-role, multipart-config,
session-config/cookie-config/tracking-mode,
request/response-character-encoding, deny-uncovered-http-methods,
default-context-path, tri-state `async-supported`); **web fragments** discovered
as `META-INF/web-fragment.xml` resources of every jar of the application
(and inside `WEB-INF/lib` jars for Phase 3b); `<absolute-ordering>` /
`<ordering>` (§8.2.2, including `<others/>` and cycle detection);
`@HandlesTypes` resolution against the generated class index with a
Class-File jar scan fallback; `META-INF/resources` static resources.
**TCK target:** `pluggability.*` 5 → ~640 (≈ +37 points, overall ≈ 91 %).

### Phase 3b — Option B: runtime WAR deployment (`foy-war`, degraded mode)
Optional product module for TCK and migration use, documented as **not AOT**:
- `WarDeployment`: opens a WAR (file or exploded directory), builds a
  dedicated **child-first class loader** over `WEB-INF/classes` +
  `WEB-INF/lib/*.jar` (§10.7.2: container/Jakarta API classes always
  parent-first), a `ResourceProvider` over the WAR root, parses `web.xml` +
  fragments, and feeds `WebAppModel` → `WebAppDeployer`.
- Components resolve through `WebComponentRegistry`'s Class-File tier, then
  reflection (nothing in a WAR was processed at build time).
- CDI: the WAR's beans go through vauban's runtime fallback
  (`processEnhancementOnly` for unprocessed archives) — injection works but
  without the build-time guarantees; the limitation is documented.
- Several WARs → several contexts, using the existing `CrossContextRegistry`.
- **The TCK harness (`VidocqDeployableContainer`) is rebuilt on `foy-war`**, so
  the official TCK exercises the product WAR path instead of harness-only
  logic. Exit: TCK ≥ the Phase 3 figure with the harness delegating to
  `foy-war`.

### Phase 4 — Request/response and session core
`getHttpServletMapping` / `MappingMatch`; `changeSessionId` +
`HttpSessionIdListener`; `SessionCookieConfig` and tracking modes honoured
(cookie built from config, URL rewriting via `encodeURL`/`encodeRedirectURL`);
session expiry reaper firing `sessionDestroyed` (virtual-thread scheduler);
welcome files; default servlet (static resources, conditional GET, ranges);
`getRealPath`; error attribute `jakarta.servlet.error.query_string` (6.1);
context default request/response encodings applied; `mime-mapping`;
`getVirtualServerName` configurable; dispatcher fixes (named dispatch must not
set `forward.*`, nested forward keeps the original attributes, response closed
after forward); `getRequestId`/`getProtocolRequestId`/`getServletConnection`.
**TCK targets:** `HttpServletRequest40Tests` mapping family, `spec.welcomefiles`,
`spec.errorpage`, `spec.requestdispatcher`, `spec.srlistener`,
`spec.i18n.encoding`, `compat.LeadingSlash`.

### Phase 5 — Streaming, async and non-blocking I/O
Real response streaming (commit on buffer overflow / `flushBuffer`, honour
`setBufferSize`) — requires a streaming response API from chappe (check
chappe first; open a chappe issue if missing). `AsyncContext` that releases
the container thread (no blocking until completion), timeout routed to the
error-page mechanism instead of a bare 503; `ReadListener`/`WriteListener`
and `isReady()`; HTTP Upgrade (`HttpUpgradeHandler`, `WebConnection`) over
chappe's raw connection; response trailers (`setTrailerFields`);
`ServletInputStream`/`ServletOutputStream` ByteBuffer methods (6.1) overridden
efficiently. **TCK targets:** `spec.async`, `ReadListener`/`WriteListener`
classes, `HttpUpgradeHandlerTests`.

### Phase 6 — Security
`login-config` (BASIC, FORM, CLIENT-CERT; DIGEST optional), web.xml
`security-constraint` + annotation merge, `ServletRegistration.Dynamic.setServletSecurity`,
transport guarantee, `deny-uncovered-http-methods`, role mapping +
`@DeclareRoles`/`@RunAs`, `login()`/`logout()`/`authenticate()` semantics
(logout invalidates the authenticated session). Client-cert needs chappe TLS
peer certificates (check chappe API). **TCK targets:** `spec.security.*`
(21/59 today).

### Phase 7 — Finishing
Multipart: `@MultipartConfig` required, `location`/`fileSizeThreshold`/
`maxFileSize`/`maxRequestSize`, disk spill, form fields as parameters.
CDI: request/session context activation per request/session in
`foy-cdi-vauban`. Signature tests (`sigtest-maven-plugin`, `-Psigtest`).
Docs: `docs/en` pages (`tck.adoc`, `reference.adoc`, `internals.adoc`)
updated to the final status; `TCK.md` final report; certification decision.

## Working rules for every phase
- TDD: failing test first (unit or foy-core end-to-end with
  `TestServerLauncher`), then code. TCK classes are the acceptance gate, not
  the only test.
- After each phase: full TCK run, new `TCK.md` §0 entry, no regression on
  any previously passing class (diff the per-class tallies).
- English everywhere; "Java Modules", never "JPMS"; Conventional Commits,
  GPG-signed, `Signed-off-by`, `Co-Authored-By` trailer.
- Virtual threads for anything scheduled (session reaper, async timeouts).
- Zero runtime dependencies beyond the Jakarta APIs; build-time tools
  (processor, maven plugin) may depend on what they need, named in the docs.

## Phase 1 follow-ups (triaged by the Phase 1 final review, 2026-10-07)

- **Phase 2:** default `@WebServlet`/`@WebFilter` name must be the FQCN (§8.1.1; today the simple name, which breaks the §8.2.3 by-name merge for FQCN-named web.xml entries); `ComponentFactory.Reflective` copies its name set and wraps `LinkageError`/`ExceptionInInitializerError`; `ClassCastException` in instantiate skips the component instead of aborting; honour dynamic `setAsyncSupported` and filter `asyncSupported`; same instance under two names; test the CDI discovery path.
- **Phase 3:** tri-state `async-supported` merge; `NumberFormatException` on malformed `load-on-startup`; tests for error-page / context-param copy and `FilterMappingDecl` invariants; unmapped dynamic vs static filter asymmetry.
- **Phase 4:** `ServletContext.setSessionTimeout()` from an SCI/listener must reach the session manager; `fireContextDestroyed` must isolate throwing listeners; make the temp-dir leak test independent of the shared `java.io.tmpdir`.
- **Phase 7:** form body size cap; parameter parsing locks the request encoding (§3.12); retry after a failed body read.
- **Cosmetic:** `tck-tally.sh` argument check; harness `start()` closes the deployment if the server fails to start.

## Phase 2 exit / follow-ups (2026-10-08)

Phase 2 shipped `foy-processor` (build-time, `java.compiler` only), the four-tier `WebComponentRegistry` and the `FoyWebExtension` build compatible extension. Tracked here, no external issue or PR opened.

**Phase 2b (separate plan):**
- `foy-maven-plugin:generate` for external jars (classes that were never run through `foy-processor`; today they fall to the Class-File tier).
- A `vidocq` PR adding `foy-processor` and `foy-cdi-vauban` to `vidocq-runtime-core-codegen`.

**Known vauban gaps (filed as Vidocq/vauban#130, #131, #132; recorded in vauban `BUG.md` as BUG-20261008-02..04):**
- `Class[]` parameters of a synthetic bean are dropped, so `CdiWebComponents` carries one comma-joined `String` (https://codefloe.com/Vidocq/vauban/issues/130, BUG-20261008-02).
- `@Registration` runs before `@Enhancement` takes effect, so the scope added by `FoyWebExtension` is not seen by registration (https://codefloe.com/Vidocq/vauban/issues/131, BUG-20261008-03).
- Stereotype-only classes are not indexed by vauban (https://codefloe.com/Vidocq/vauban/issues/132, BUG-20261008-04).

**Residual Phase 2 minors:**
- (Fixed in Phase 3) The async flag is now recomputed on forward, include, async and error dispatch.
- (Fixed in Phase 3, Task 3.0) Dynamic `addServlet(name, "java.lang.Object")` now fails with a descriptive error.

## Phase 3 exit / follow-ups (2026-10-09)

Phase 3 shipped `web-fragment.xml` parsing, the §8.2.2 `FragmentOrderer`, the §8.2.3 `FragmentMerger`, native discovery of fragments and `ServletContainerInitializer`s (`ApplicationSources`, `Builder.discoverPluggability`, `Builder.applicationRoot`), `META-INF/resources` through `ClassPathResourceProvider`, `@HandlesTypes` with a class-file scan fallback, and the remaining `web-app_6_1` elements. Tracked here, no external issue or PR opened.

**Full TCK:** 1587/1714 (Phase 2: 928), zero per-class regression, 63 classes improved, every `tiers=Stats` line `reflection=0`. Per family: `api.*` 821/859, `pluggability.*` 639/646 (was 5), `spec.*` 127/207 (was 102), `compat.*` 0/2.

**Residual `pluggability.*` failures (7):** `filterrequestdispatcher` (2, filter invoked twice), `httpservletresponse`, `httpservletresponse30`, `httpservletresponsewrapper30`, `sessioncookieconfig` (1 each) and `fragment.FragmentTests` (1). Except the last, they are twins of `api.*` failures and fall to Phase 4 (request/response, default servlet) or Phase 6 (security).

**Needed by Phase 4:**
- A default servlet: welcome files, static content from `META-INF/resources` over HTTP (the resources are only reachable through `ServletContext.getResource*` today).
- Fix the filter double invocation when a filter is mapped by URL pattern and by servlet name (BUG-20261008-02).

**Phase 6:** enforcement of `security-constraint`, `login-config`, `security-role`, `deny-uncovered-http-methods` and `run-as` (all parsed in Phase 3).

**Deferred minors:**
- The async flag is computed twice on some dispatch paths (duplicate computation to factor out).
- The `Secure` cookie flag is not set for secure requests by the session cookie.
- `WEB-INF/lib` nested-jar URLs (fragments inside jars of a WAR) belong to Phase 3b.

## Phase 4 exit / follow-ups (2026-10-09)

Phase 4 shipped response commit/redirect/charset/cookie `Expires` semantics; error pages (message, root cause, `error.query_string`, committed-response guard); `HttpServletMapping` with `DEFAULT`/`CONTEXT_ROOT`; dispatcher semantics (parameter merge, named dispatch of unmapped servlets, nested forward keeping the originals, `forward.*`/`include.*` scoping, response closed after a forward); servlet-name filter mappings (§6.2.4, BUG-20261008-02 fixed); the container default servlet `foy.default` (static content, MIME, conditional GET, ranges, HEAD/OPTIONS/405, `WEB-INF`/`META-INF` refused for client requests only, provider metadata); request path canonicalisation (§3.5.2); welcome files; sessions (`changeSessionId`, virtual-thread expiry reaper, access on every request, tracking modes enforced on input, URL rewriting, `Secure` cookie, `setSessionTimeout` from an initializer); legacy DTD 2.2/2.3 descriptors (XXE-safe); the TCK harness serving every deployment of a container from one host; request ids, `ServletConnection`, `getRealPath`, the virtual server name, context-path validation and the root context `""`. Tracked here, no external issue or PR opened.

**Full TCK:** 1649/1714 (Phase 3: 1587), zero per-class regression, 25 classes improved, every `tiers=Stats` line `reflection=0`. Per family: `api.*` 840/859, `pluggability.*` 646/646, `spec.*` 161/207, `compat.*` 2/2.

**Remaining 65 failures, by phase:**
- Phase 6 (security), 37: `secform` 17, `secbasic` 8, `denyUncovered` 4, `metadatacomplete` 4, `annotations` 2, `clientcert` 1, `clientcertanno` 1.
- Phase 5 (streaming, async and non-blocking I/O), 10: `ReadListener` 3, `WriteListener` 1, `HttpUpgradeHandler` 1, response trailers 3 (`HttpServletResponse40Tests`), `flushBuffer` 2 (`servletResponseTests`); plus HTTP/2 server push, 7 (`ServerPushTests`).
- Phase 7 (multipart), 8: `PartTests` 4, `Part1Tests` 4.
- JSP (accepted gap), 3: `ServletContext40Tests` (`addJsp`, two TLD listener tests).

**Open bugs:** BUG-20261009-01 (forward/include drop a non-HTTP application wrapper), -03 (error dispatch paths shown to the default servlet only), -04 (decoded paths leak into dispatch URIs, low), -09 (connection id and HTTP/2 stream id, chappe follow-up).

**Chappe follow-up:** add a per-connection id (JVM-wide counter assigned on accept) and the HTTP/2 stream id to `io.vidocq.chappe.api.Request`; Foy then maps them to `getConnectionId()` and `getProtocolRequestId()` (BUG-20261009-09).

**Deferred minors worth tracking:**
- `getRequestDispatcher` and the cross-context lookup test `startsWith(contextPath)` without a segment boundary (`/app` matches `/application/x`; pre-existing); dead `"/".equals` guards; the root-context cookie path is untested.
- `isCommitted()` drains the writer (side effect); `setContentLength(0)` commits only on the first write.
- The `UnavailableException` path still walks `getCause()`; the commit-guard test does not assert the log line.
- Empty `ServletMappingImpl` allocated per call (use a constant); duplicated `async.mapping` `setAttribute`.
- `close()` getWriter edge for exotic wrappers; dispatch query decoding hard-coded to UTF-8.
- Path parameters other than `jsessionid` are not stripped before `isServable`; `metadata`/`openStream` may pick different roots when the first copy is unreadable.
- `startAsync(req, res)` with an app wrapper overriding `getRequestURI`: the override is ignored by the zero-argument dispatch.
- `sessionIdChanged`/`sessionCreated` listeners run under locks (a listener blocking forever stalls undeploy; one calling `close()` deadlocks); cookie-wins with an invalid cookie is untested.
- Descriptor parsing: no `&#65;` test; `isPre25` duplicates version parsing; the pre-2.3 lenient pattern differs slightly from Tomcat.
- Welcome files: an empty directory is not redirected; `;` is left raw in the `Location`.
- The default servlet answers several ranges with the whole body (no `multipart/byteranges`); the response is still buffered whole (Phase 5).

## Phase 5 exit / follow-ups (2026-10-10)

Phase 5 shipped, in chappe (branch `pr/ybl/servlet-streaming-upgrade`, 0.4.0-SNAPSHOT): HTTP/1.1 response trailers (chunked trailer section, RFC 9110 §6.5.1 names refused); per-read flush of live known-length bodies; a generic HTTP/1.1 `ConnectionUpgrade` API (`UpgradeHandler`, `UpgradedConnection`); `Body.release()` on every exit (CHAPPE-009); request body fixes CHAPPE-010 to CHAPPE-013 (each chunk returned as it arrives, truncated chunked and fixed-length bodies fail the read, no request parsed from a failed body read). In Foy: the TCK push opt-out (`servlet.tck.support.http2Push=false`, `newPushBuilder()` returns `null`); streaming responses through a bounded `ResponsePipe` with commit on buffer overflow, `flushBuffer` and a complete `Content-Length`; the servlet pipeline on a `foy-request-<n>` virtual thread with chappe's `RequestContext.CURRENT` re-bound; response trailers; the async lifecycle (timeout → `onTimeout` → error dispatch 500 → `onComplete`, `onStartAsync`, `onError`, one place where a cycle ends, the output fenced per cycle — BUG-20261010-01 PARTIAL); `ReadListener` and `WriteListener`; HTTP Upgrade (`HttpUpgradeHandler`, `WebConnection`, `destroy()` on close, error or undeploy). The Phase 4 deferred minor "the response is still buffered whole" is resolved. Tracked here, no external issue or PR opened.

**Full TCK:** 1659 passing / 1714 run, 7 skipped (Phase 4: 1649), 48 errors, zero per-class regression, `join -v` empty both sides, 6 classes improved (10 tests), every `tiers=Stats` line `reflection=0`. Per family (passing / run, skipped): `api.*` 848/859, 0; `pluggability.*` 646/646, 0; `spec.*` 163/207, 7; `compat.*` 2/2, 0. Family mode adds `GetServletRegistrationsTest` (`api.*` 849/860). `foy-tck/tck-tally.sh` gained a skipped column (`<class> <run> <bad> <skipped>`); a skip is never counted as a pass. The HTTP/2 push figure is 7 skipped (not 6 as the Phase 5 plan expected): the TCK's static switch skips every test that needs push, and the class's passing count stays 1.

**Merge order:** the chappe PR (`pr/ybl/servlet-streaming-upgrade`) must merge, and its 0.4.0-SNAPSHOT be published, before the Foy PR: Foy compiles against the new chappe API (`ConnectionUpgrade`, trailers, `Body.release()`).

**Remaining failures, by phase:**
- Phase 6 (security), 37: `secform` 17, `secbasic` 8, `denyUncovered` 4, `metadatacomplete` 4, `annotations` 2, `clientcert` 1, `clientcertanno` 1.
- Phase 7 (multipart), 8: `PartTests` 4, `Part1Tests` 4.
- JSP, 3 (`ServletContext40Tests`: `addJsp` and two TLD listener tests): the Peano (Jakarta Expression Language 6.0) and Ibarra (Jakarta Pages 4.0) bricks were created; these tests move to a Foy JSP-engine SPI that Ibarra plugs into, instead of an accepted gap.
- HTTP/2 server push, 7 skipped: accepted gap (deprecated in Servlet 6.1; chappe has no h2c Upgrade nor PUSH_PROMISE writer).

**Open bugs:** Foy BUG-20261010-01 (PARTIAL: an application-managed thread can still write in a later open cycle), BUG-20261010-02 (kept non-blocking bytes sent blocking at the end of a cycle, bounded only by chappe's write timeout), and from Phase 4 BUG-20261009-01, -03, -04, -09. Chappe CHAPPE-014 (OPEN: a connection closed after an interrupted body read skips the lingering close and the TLS `close_notify`, so an active uploader may see a TCP RST) and CHAPPE-008 (ordinary header values truncated to one byte; trailers fixed).

**Documented limits (usage, internals, migration pages):** the trailer supplier runs on chappe's thread after `service()` (no request-scoped state); `setContentLength` after `setTrailerFields` (HTTP/1.1) and a `Connection: close` response drop the trailers silently; `reset()` keeps the supplier; no `ReadListener.onError` on async timeout; a `WriteListener` is not resumed after an ASYNC dispatch; a handler that never reads keeps an upgraded connection until a write fails or undeploy (no idle timeout after upgrade); servlet code runs on a Foy virtual thread, so `ThreadLocal`s set by chappe filters are not visible.

**Deferred minors worth tracking:**
- Chappe: `available()` over-reporting streams (gzip) delay the partial flush; trailer values may start or end with SP/HTAB; duplicated `isToken`/`isFieldValue` in `ConnectionUpgrade` and `HttpResponseWriter`; double `SslHandler` close under TLS; `UpgradedConnectionImpl.available()` can block behind a blocked read; `OutputStreamBody.release` has no sticky flag; HTTP/2 `sendResponse` does not re-check `isCancelled` after its loop; a WINDOW_UPDATE overflow RST uses `close()` not `cancel()`; the upgrade-path drains skip the `failed()`/`isOpen` guard; TLS `SslHandler` read in the pump concurrent with a streamed write (to check); a 0-byte `FixedLengthInputStream` read now throws.
- Streaming: HEAD without a length commits with `-1` framing; chappe's await interrupt does not interrupt the pipeline; the extra virtual thread per request is not benchmarked (a `BENCH.md` entry is required before any performance claim); `ResponsePipe.hasCapacity` is dead in main.
- Async: a dead `outcome != TIMEOUT` branch in `runAsyncCycles`; `start()`-thread failure vs servlet exception reporting; the `finish()`/abort CAS window throws `IOException`; `ac.start()` from an `onTimeout`/`onError` listener is not retired, and `start()` on a container-completed cycle is not refused; negative sleeps in `AsyncContextImplTest`.
- Non-blocking I/O: `read()` allocates for `byte[1]`; `CallbackSerializer.isRunning` is test-only; one drain virtual thread per capacity event and one per non-blocking upgraded write; `releaseBody` Javadoc misplaced; no `ReadBuffers` unit test nor TLS variant of `InterruptedBodyReadTest`.
- Upgrade: `close()` drops an in-flight non-blocking write; `destroy()` closes the connection twice when a close happens during `init`; the `RegistryComponentFactory` fallback is untested; `ServletUpgradeEndToEndTest` is long (its shared-static-state flake was fixed at the exit with a per-test probe).
- Trailers: rename the `trailersOverHttp2_` unit test; the HTTP/2 `setContentLength` branch is untested; parametrise the FORBIDDEN-name test.
