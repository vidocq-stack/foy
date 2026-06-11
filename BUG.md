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
