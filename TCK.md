# TCK Report — Foy : Jakarta Servlet 6.1

First full-suite measurement run. Unlike cassini/champollion/vauban, this is a
**baseline measurement**, not a conformance claim: Foy M1 was extracted from the
Vidocq runtime to serve Cassini-style stacks, and several spec chapters are not
implemented yet. This report quantifies exactly which ones.

## 0. Phase 3 exit: descriptors and pluggability (2026-10-09)

`pr/ybl/servlet-completion-phase3`, `./run-official-tck-servlet6.1.sh --all`:
1714 run, **1587 pass**, 127 errors, 13 min 52 s (Phase 2: 928). Per family:
`api.*` 821/859 (unchanged), `pluggability.*` 639/646 (was 5), `spec.*` 127/207
(was 102), `compat.*` 0/2. The per-class tally (`foy-tck/tck-tally.sh`) was diffed
against the previous `foy-tck/tck-baseline.txt`: zero class regression, 63 classes
improved, no new class. `foy-tck/tck-baseline.txt` is refreshed to this tally.

`--all` runs `**/*Tests.class` only, so a `*Test` class seen in family runs
(for example `GetServletRegistrationsTest`) is not counted.

All 205 `tiers=Stats` lines show `reflection=0`. The harness now deploys through
the product descriptor and fragment merge, with the `WEB-INF/lib` fragments.

Residual `pluggability.*` failures (7, in 6 classes) are the twins of failures
that also exist in `api.*` (except the last): `filterrequestdispatcher` (2, filter invoked twice,
BUG-20261008-02), `httpservletresponse`, `httpservletresponse30`,
`httpservletresponsewrapper30`, `sessioncookieconfig` (1 each) and
`fragment.FragmentTests` (1). They belong to the request/response phase (default
servlet, welcome files) and the security phase.

## 0.1 Phase 2 exit: build-time code generation (2026-10-08)

`pr/ybl/servlet-completion-phase2`, `./run-official-tck-servlet6.1.sh --all`:
1714 run, **928 pass**, 786 errors, 11 min 19 s (Phase 1: 921). Per family:
`api.*` 821/859, `pluggability.*` 5/646, `spec.*` 102/207 (was 95), `compat.*` 0/2.
The per-class tally (`foy-tck/tck-tally.sh`) was diffed against the previous
`foy-tck/tck-baseline.txt`: zero class regression; three classes improved
(`spec.annotationservlet.webservlet.WebServletTests`, `webservletapi.WebServletApiTests`,
`webservletdd.WebServletddTests`: 7 errors fixed). `foy-tck/tck-baseline.txt` is
refreshed to this tally.

`WebComponentRegistry` tier statistics, one line per deployed archive (207 of
them): `serviceLoader=0, generatedClass=0, classFile=1..3, reflection=0`. The TCK
archives are assembled at test time and never go through `foy-processor`, so
everything resolves through the Class-File tier (decoded from the class bytes),
and **no component fell back to reflection**.

## 0a. Product bootstrap (Phase 1) (2026-10-07)

`pr/ybl/servlet-completion-phase1`, `./run-official-tck-servlet6.1.sh --all`:
1714 run, **921 pass**, 793 errors, 11 min 12 s. Per family: `api.*` 821/859,
`pluggability.*` 5/646, `spec.*` 95/207, `compat.*` 0/2. The per-class tally
(`foy-tck/tck-tally.sh`) is identical to `foy-tck/tck-baseline.txt`: no
regression, no improvement.

The lifecycle is now executed by foy-core's `WebAppDeployer`; the harness only
adapts Arquillian archives. The score is unchanged because Phase 1 moved the
lifecycle (init/destroy, `web.xml` merge per Servlet 6.1 §8.2.3,
`metadata-complete`, `load-on-startup`) into the product without altering what
the suite exercises: the figures now measure the product boot path, not only
engine + harness.

## 0b. Re-measurement on 0.4.0-SNAPSHOT (2026-10-07)

`main` @ `65b1197`, chappe/vauban 0.4.0-SNAPSHOT, `./run-official-tck-servlet6.1.sh --all`:
1714 run, **921 pass**, 793 errors, 11 min 12 s — identical to 2026-06-12.
Per family: `api.*` 821/859, `pluggability.*` 5/646, `spec.*` 95/207,
`compat.*` 0/2.

**Caveat discovered by the 2026-10-07 audit:** the TCK harness
(`ServletTestHarness`, `VidocqDeployableContainer`) performs the deployment
lifecycle itself (init/destroy, web.xml, SCI, dynamic registrations). The
product boot path does not yet. Scores above measure engine + harness;
Phase 1 of `docs/superpowers/plans/` moves that lifecycle into foy-core.

## 0c. Re-run after the trailer/idle-timeout fixes (2026-06-12)

Same suite, re-run with the merged fix chain (chappe trailers + 
`ForwardingRequest` + CHAPPE-005 idle timeout, foy `getTrailerFields()`,
`foy-tck` version unfrozen — it had been validating stale 0.1.0 jars):

| Metric | Baseline | **Re-run** |
|---|---:|---:|
| Tests run | 1714 | 1714 |
| Passed | 920 | **921** |
| `api.*` | 95.5 % | **95.6 %** |
| Wall clock | ~50 min | **11 min** |

`HttpServletRequest40Tests.TrailerTest` (the test that froze the very first
run — BUG-20260611-01, fixed across four stacked causes, see `BUG.md`) now
**passes**; the 9 remaining errors in that class are all the
`httpServletMapping*` family (`getHttpServletMapping`, a separate chantier).
The 0.1.0→0.2.0 jar refresh changed nothing else — the baseline structure
below remains valid. The harness 5 s idle timeout cut the wall clock to
11 min; 2 tests still hit the 120 s JUnit timeout
(`HttpUpgradeHandlerTests.upgradeTest` — HTTP Upgrade not implemented, §3.6).

## 1. Result (baseline, 2026-06-11)

| Metric | Value |
|---|---|
| TCK | `jakarta.tck:servlet-tck-runtime:6.1.0` (official, non-public artifacts) |
| JDK | Eclipse Temurin 25 |
| Harness | `foy-tck` (Arquillian, in-reactor behind the `tck` Maven profile), transport chappe, CDI vauban |
| Command | `./run-official-tck-servlet6.1.sh --all` |
| Tests run | **1714** |
| Passed | **920** |
| Failures / Errors | 0 / 794 |
| **Raw score** | **53.7 %** |

```
[ERROR] Tests run: 1714, Failures: 0, Errors: 794, Skipped: 0
```

## 2. Per-family breakdown — the raw score is misleading

| Family | Run | Pass | Score | Reading |
|---|---:|---:|---:|---|
| `api.*` (core engine) | 859 | 820 | **95.5 %** | The Servlet engine itself is solid |
| `pluggability.*` | 646 | 5 | 0.8 % | **One missing feature** (web fragments), see §3.1 |
| `spec.*` (behavioral) | 148 | 74 | 50.0 % | Async, error pages, server push… see §3 |
| `spec.security.*` | 59 | 21 | 35.6 % | Form/basic auth and client-cert incomplete |
| `compat.*` | 2 | 0 | 0 % | Leading-slash dispatch compat |

The 95.5 % on `api.*` (the largest family) shows the M1 core is much better
than the raw 53.7 % suggests: **81 % of all errors (641/794) come from a single
missing feature** — web-fragment scanning.

## 3. Root causes identified

### 3.1 Web fragments not scanned — 641 errors (81 % of all errors)

**Resolved in Phase 3** (`pluggability.*` 5 → 639/646, overall 1587/1714); the analysis below is the 2026-06 diagnosis, kept for history. The 7 residual errors are tracked in §0.

Every `pluggability.*` war packages its servlets in `WEB-INF/lib/*.jar` with a
`META-INF/web-fragment.xml` (spec §8.2). Foy deploys those wars with **zero
servlets registered** at the time (`[VidocqTCK] deploy archive=servlet_plu_servlet_web.war
… servlets=[]`), so every request 404s. Implementing fragment scanning +
ordering (`<absolute-ordering>`, `<ordering>`) would mechanically bring the
overall score to ~91 %. The 10 `pluggability.aordering*` and `fragment` classes
test the ordering semantics specifically.

### 3.2 HTTP/1.1 trailers — `HttpServletRequest40Tests` 10/11

`request.getTrailerFields()` / chunked request trailers: Foy/chappe never
answers a chunked request carrying trailers — the raw-socket TCK client blocked
**forever** in `Socket.read()` (no `SO_TIMEOUT` client-side; see §5). Likely a
chappe-side transport gap (trailer parsing after the final chunk).

### 3.3 Multipart — `PartTests`/`Part1Tests` 8/14

`jakarta.servlet.http.Part` handling fails on half the cases (upload limits,
`@MultipartConfig` thresholds…).

### 3.4 Async I/O — `ReadListener`/`WriteListener` (4/4) and `spec.async` (22/39)

Non-blocking I/O listeners (`ServletInputStream.setReadListener`…) and a
substantial part of the `AsyncContext` behavioral suite (dispatch cycles,
timeouts, event ordering).

### 3.5 Security — 38/59

`secform` (17/25), `secbasic` (8/14), `clientcert*` (2/2), `denyUncovered`,
`metadatacomplete`: FORM/BASIC auth flows and TLS client-cert mapping are
partial in the chappe adapter + `SecurityProvider` SPI.

### 3.6 Scattered behavioral gaps

`spec.errorpage` (4/4), `spec.serverpush` (7/8 — HTTP/2 server push through
chappe), `spec.welcomefiles` (2/2), `spec.requestdispatcher` (13/20),
`spec.srlistener` (5/13), `spec.i18n.encoding` (2/3), `compat.LeadingSlash`
(2/2), `HttpUpgradeHandler` (1/1), plus singles visible in the per-class log.

## 4. What this means for the roadmap

By expected score gain:

1. **Web fragments (§8.2)** — +~37 points in one chantier (→ ~91 %).
2. **Async I/O + AsyncContext behaviors** — ~26 errors.
3. **Security flows (FORM/BASIC/client-cert)** — ~38 errors.
4. **Trailers (chappe)** + multipart + error pages + welcome files — ~25 errors.

## 5. Methodology note — hang safety net

Some TCK clients read raw sockets without `SO_TIMEOUT`
(`HttpServletRequest40Tests.TrailerTest` line 252) and hang forever when the
implementation never answers: the first measurement attempt froze after 83
classes. `foy-tck/src/test/resources/junit-platform.properties` now sets a
global JUnit 120 s timeout in `SEPARATE_THREAD` mode (a thread stuck in
`Socket.read()` ignores interruption — the watchdog must abandon it). 8 tests
hit the timeout in this run; they are counted as errors above.

## 6. Reproduce

```sh
./run-official-tck-servlet6.1.sh --all   # ~30 min, needs the non-public TCK artifacts in the local M2
```

Per-class tallies parse out of the surefire output
(`Tests run: …, -- in servlet.tck.…` lines).
