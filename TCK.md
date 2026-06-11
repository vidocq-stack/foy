# TCK Report — Foy : Jakarta Servlet 6.1

First full-suite measurement run. Unlike cassini/champollion/vauban, this is a
**baseline measurement**, not a conformance claim: Foy M1 was extracted from the
Vidocq runtime to serve Cassini-style stacks, and several spec chapters are not
implemented yet. This report quantifies exactly which ones.

## 1. Result (baseline, 2026-06-11)

| Metric | Value |
|---|---|
| TCK | `jakarta.tck:servlet-tck-runtime:6.1.0` (official, non-public artifacts) |
| JDK | Eclipse Temurin 25 |
| Harness | `foy-tck` (Arquillian, out-of-reactor POM Model 4.0.0), transport chappe, CDI vauban |
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

Every `pluggability.*` war packages its servlets in `WEB-INF/lib/*.jar` with a
`META-INF/web-fragment.xml` (spec §8.2). Foy deploys those wars with **zero
servlets registered** (`[VidocqTCK] deploy archive=servlet_plu_servlet_web.war
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
