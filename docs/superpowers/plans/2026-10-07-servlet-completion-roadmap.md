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
**Phase 2b:** `foy-maven-plugin:generate` for external jars; add
`foy-processor` + `foy-cdi-vauban` to the vidocq runtime codegen bundle
(cross-repo PR in `vidocq`).

### Phase 3 — Deployment descriptors and pluggability
Full `web.xml` schema coverage (welcome-file-list, mime-mapping,
security-constraint, login-config, security-role, multipart-config,
load-on-startup, metadata-complete, session-config/cookie-config/tracking-mode,
request/response-character-encoding, deny-uncovered-http-methods,
default-context-path), **web fragments** (`WEB-INF/lib/*.jar!/META-INF/web-fragment.xml`),
`<absolute-ordering>` / `<ordering>` (§8.2.2, including `<others/>` and cycle
detection), `@HandlesTypes` resolution against the generated class index with
a Class-File jar scan fallback, `META-INF/resources` static resources.
**TCK target:** `pluggability.*` 5 → ~640 (≈ +37 points, overall ≈ 91 %).

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
