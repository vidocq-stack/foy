# Phase 5 — Streaming, Async and Non-Blocking I/O Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Foy streams responses as they are committed, runs `AsyncContext` with the spec's timeout/error/listener lifecycle, supports `ReadListener`/`WriteListener`, response trailers and HTTP Upgrade (`HttpUpgradeHandler`), lifting the TCK from 1650 to 1660/1714 and turning the 6 HTTP/2 push errors into documented skips.

**Architecture:** two small chappe additions first (HTTP/1.1 response trailers + per-read flush of streaming bodies; a generic HTTP/1.1 connection upgrade API). In Foy, the bridge runs the servlet pipeline on a child virtual thread and parks chappe's thread on a "response head" future that completes either at commit (a streaming `Response` whose body is a bounded pipe fed by the servlet's output stream) or at the end of the request (the current whole-buffer `Response`, kept as the fast path). Non-blocking I/O is layered on top: a per-request pump virtual thread turns chappe's blocking request stream into `ReadListener` callbacks; `WriteListener` readiness follows the pipe's free capacity. Upgrade returns a chappe `ConnectionUpgrade` carrying the application's status and headers, then hands the raw connection to `HttpUpgradeHandler.init(WebConnection)`.

**Tech Stack:** Java 25 (virtual threads, `ScopedValue`), Jakarta Servlet 6.1, chappe 0.4.0-SNAPSHOT (local `../chappe` reactor), foy-core / foy-chappe, JUnit 5, end-to-end tests over real chappe (`TestServerLauncher` pattern, raw `Socket` clients where byte framing matters), official Servlet TCK via Arquillian.

**Spec:** `docs/superpowers/plans/2026-10-07-servlet-completion-roadmap.md` §"Phase 5" and §"Phase 4 exit / follow-ups"; Jakarta Servlet 6.1 §2.3.3.3–2.3.3.5, §3.7 (non-blocking I/O), §5.1 (buffering), §5.3 (trailers), §5.7 (closure), and the 6.1 Javadoc of `ServletResponse`, `HttpServletResponse#setTrailerFields`, `AsyncContext`, `ServletInputStream`, `ServletOutputStream`, `HttpServletRequest#upgrade`. Evidence: research of 2026-10-10 (summarised below).

## Evidence (Phase 4 exit, 1650/1714)

Phase 5 failures (16) and what they need:

| Tests | Assertion | Root cause (file:line at `2d26f7f`) | Needs |
|---|---|---|---|
| `servletResponseTests.testFlushBuffer`, `testFlushBufferHttp` | first line must reach the client > 5 s before the second (`flushBuffer()` then `sleep(10000)`) | body buffered whole, sent once (`ChappeServletBridge.toChappeResponse` `:632-642`; `flushBuffer` `HttpServletResponseImpl:426` does no I/O) | Foy streaming |
| `HttpServletResponse40Tests.TrailerTestWithHTTP10`, `TrailerTestResponseCommitted` | `setTrailerFields` throws ISE on HTTP/1.0 and after commit | `setTrailerFields` not overridden (API default no-op) | Foy |
| `HttpServletResponse40Tests.TrailerTest` | after `Current trailer field:`, exactly `myTrailer:foo\r\n0\r\nmyTrailer: foo\r\n\r\n` | chappe HTTP/1.1 writer ends chunked bodies with a constant `0\r\n\r\n`, never reads `Response.trailers()` (`HttpResponseWriter.java:62,301-316`); the servlet sets `Transfer-Encoding: chunked` itself, which makes chappe skip framing (`:183`) | chappe C1 + Foy |
| `ReadListenerTests.nioInputTest` | `=onDataAvailable =Hello =onDataAvailable =World =onAllDataRead` (two chunks 1 s apart) | `setReadListener` throws UOE, `isReady()` always true (`ServletInputStreamImpl:68-75`) | Foy pump |
| `ReadListener1Tests.nioInputTest1`, `nioInputTest2` | `setReadListener(null)` → NPE; in an ASYNC dispatch without `startAsync` → ISE | same | Foy |
| `WriteListenerTests.nioOutputTest` | `onWritePossible` called after `setWriteListener` in async | `setWriteListener` throws UOE (`ServletOutputStreamImpl:55`) | Foy |
| `HttpUpgradeHandlerTests.upgradeTest` | 101 with app headers, then `init` banner, `Hello`/`World` echoed through a `ReadListener` on `WebConnection` | `upgrade()` throws UOE (`HttpServletRequestImpl:741`); chappe only upgrades to WebSocket (`HttpConnection.java:201-217`) | chappe C2 + Foy |
| `ServerPushTests` ×6 | JDK `HttpClient` HTTP/2 over `http://` (h2c Upgrade) + PUSH_PROMISE | chappe has neither h2c Upgrade nor a PUSH_PROMISE writer; push is `@Deprecated` in 6.1 | accepted gap: TCK switch `servlet.tck.support.http2Push=false` |

Not exercised by a failing TCK test (Foy's own tests): commit on buffer overflow and `setBufferSize` enforcement, client disconnect while streaming, async timeout routed to the error page with status 500 (today a bare 503, `ChappeServletBridge:438-441`), `onComplete` at the end of the async cycle (today fired on `dispatch`, `AsyncContextImpl:158`), `onStartAsync` on re-`startAsync`, `read(ByteBuffer)`/`write(ByteBuffer)`, trailers over HTTP/2, upgrade refused on HTTP/2.

## Global Constraints

- Runtime modules keep zero dependency beyond Jakarta APIs + chappe/vauban; chappe keeps zero dependency beyond the JDK.
- Static codegen doctrine: no new `getAnnotation(` / `getDeclaredConstructor(` / `Class.forName(` in foy-core main outside the `NoProductReflectionTest` allow-list; `HttpUpgradeHandler` instances come from the context's component factory (`VidocqServletContext.componentFactory().newInstance(Class)`).
- Java Modules: say "Java Modules", never "JPMS"; every new exported chappe type goes through `module-info.java` (`io.vidocq.chappe.api` is already exported).
- Virtual threads for every thread Foy or chappe starts (pipeline thread, read pump, listener callbacks, async timeout): `Thread.ofVirtual().name(...)` or `Executors.newVirtualThreadPerTaskExecutor()`; no platform thread pool.
- Chappe changes live in the chappe repository (`/Users/yblazart/projects/perso/vidocq/chappe`) on branch `pr/ybl/servlet-streaming-upgrade`, with chappe's own tests (`chappe-tests`), then `./mvnw -ntp install -DskipTests` in chappe so Foy's `0.4.0-SNAPSHOT` dependency picks them up. Foy never copies chappe code.
- English everywhere; Conventional Commits; `git commit -S -s`; trailers `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi`.
- Every reproducible bug found on the way goes to the `BUG.md` of the repository it belongs to (Foy or chappe).
- TCK gate (Foy tasks that change request/response/async behaviour): `./run-official-tck-servlet6.1.sh --family api` and `--family spec`, per-class `LC_ALL=C join` against `foy-tck/tck-baseline.txt` → zero regression, `join -v` empty both sides, `reflection=0` on every `tiers=Stats` line. Phase exit: full `--all` run ≥ 1650 passing, target 1660.

## Review Focus

1. **A client that disconnects while the servlet is still writing a committed response** — the servlet's next `write`/`flush` throws `IOException` within a bounded time, the pipeline thread ends, `requestDestroyed` fires, no thread stays parked (Task 5.4 test `clientDisconnectFailsTheNextWrite`).
2. **An exception thrown after the response was committed** — no second status line, the connection is closed after the partial body (truncated response), the exception is logged once (Task 5.4 test `exceptionAfterCommitAbortsTheConnection`).
3. **Async timeout with an error page mapped to 500** — `onTimeout` fires on every listener, the error page renders with status 500 and `jakarta.servlet.error.status_code=500`, then `onComplete` fires once (Task 5.6 test `timeoutDispatchesTheErrorPage`).
4. **`ReadListener` on a body that arrives in two chunks separated by a pause** — two `onDataAvailable` calls, `isReady()` false between them, `onAllDataRead` exactly once, callbacks never concurrent (Task 5.7 test `twoChunksYieldTwoCallbacks`).
5. **Response trailers on a response that carries `Content-Length`, on HTTP/1.0 and after commit** — `setTrailerFields` throws `IllegalStateException`; on HTTP/2 and on HTTP/1.1 chunked the trailer section reaches the client (Task 5.5 tests).

---

### Task 5.1: TCK push opt-out and `newPushBuilder` contract

**Files:**
- Modify: `foy-tck/pom.xml` (surefire `systemPropertyVariables` of the `tck` profile, ~`:185`)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java` (explicit `newPushBuilder()` override)
- Modify: `foy-tck/tck-tally.sh` only if skipped tests are not already counted (check first)
- Modify: `TCK.md`, `docs/en/modules/ROOT/pages/tck.adoc` (accepted gaps)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/bridge/HttpServletRequestImplTest.java` (grep for the existing request test class; create if absent)

**Interfaces:**
- Produces: `HttpServletRequestImpl.newPushBuilder()` returns `null` with Javadoc ("server push is not supported: chappe emits no PUSH_PROMISE; push is deprecated in Servlet 6.1 in favour of 103 Early Hints").

- [ ] **Step 1: Failing test** `newPushBuilderIsNull` — explicit assertion on a request built the way the existing tests build one (it passes today through the API default; the test pins the contract once the override exists — write it, run it green, then add the override and keep it green; this is the one task where the test documents rather than drives).
- [ ] **Step 2:** add `<servlet.tck.support.http2Push>false</servlet.tck.support.http2Push>` next to `webServerHost`. The TCK's `ServerPushTests` reads it in a static initialiser and calls `Assumptions.assumeTrue` → the 6 errors become skipped.
- [ ] **Step 3:** run `./run-official-tck-servlet6.1.sh -Dtest=ServerPushTests` (check the script's syntax in its header for a single class) and confirm `Tests run: 8, Failures: 0, Errors: 0, Skipped: 6`. Run `bash foy-tck/tck-tally.sh <log>` on that log and check how skipped tests are reported; if the tally counts a skip as a failure, change the tally to count only failures and errors (document the change in the script header).
- [ ] **Step 4:** `TCK.md` and `tck.adoc`: HTTP/2 server push listed as an accepted gap with the reason (chappe has no h2c Upgrade nor PUSH_PROMISE writer, push deprecated in 6.1) and the property used.
- [ ] **Step 5: Commit** — `build(tck): skip HTTP/2 server push tests through the TCK switch`.

---

### Task 5.2 (chappe): HTTP/1.1 response trailers and prompt streaming of known-length bodies

**Repository:** `/Users/yblazart/projects/perso/vidocq/chappe` — create branch `pr/ybl/servlet-streaming-upgrade` from `main` (`6948b36`). Read `chappe/CLAUDE.md` first.

**Files:**
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/HttpResponseWriter.java` (`write` `:119-212`, `writeBodyChunked` `:301-316`, `writeBody` `:321-361`)
- Test: `chappe-tests/src/test/java/io/vidocq/chappe/tests/ResponseTrailersHttp11Test.java` (new, raw `Socket`, same style as `ChunkedTrailersTest`), extend `StreamingBodyTest.java`
- Modify: `chappe/BUG.md` or `ROADMAP.md`/README feature list as chappe documents features (check where HTTP/2 trailers are documented and mirror it)

**Interfaces:**
- Consumes: `Response.trailers()` (`chappe-api/.../Response.java:48`, default empty, "called by the transport after `body()` has been fully consumed").
- Produces (behaviour, no API change):
  - Chunked HTTP/1.1 body: after the last data chunk, chappe calls `response.trailers()` once; when non-empty it writes `0\r\n`, then one `name: value\r\n` line per value, then `\r\n`; when empty it keeps `0\r\n\r\n`. Trailer names/values containing CR, LF or NUL are skipped (never written).
  - A response with `contentLength() < 0` and a non-keep-alive connection (HTTP/1.0 or `Connection: close`) is unchanged (no chunking, no trailers).
  - Known-length streaming body (`contentLength() >= 0` and the body is not a byte-array/file body — check the concrete `Body` classes): the writer flushes after each `read()` that returned data, so a partially written body reaches the client while the producer is still running. Byte-array and file bodies keep the coalesced single flush (no throughput change for the common case).

- [ ] **Step 1: Failing tests** (`ResponseTrailersHttp11Test`): `trailersFollowTheLastChunk` — handler returns a `Response` with `Body.of(InputStream, -1)` and `trailers()` = `myTrailer: foo`; raw socket reads one framed response; assert the bytes end with `0\r\nmyTrailer: foo\r\n\r\n`. `emptyTrailersKeepTheTerminator`. `trailersAreComputedAfterTheBody` — `trailers()` reads a value set by the body stream at EOF. `trailerWithCrLfIsDropped`. In `StreamingBodyTest`: `knownLengthStreamingBodyIsFlushedPerRead` — body of declared length 10 whose stream returns 5 bytes, then blocks on a latch; the client must receive the first 5 bytes before the latch is released (timeout 2 s).
- [ ] **Step 2: Run** `cd ../chappe && ./mvnw -ntp -pl chappe-tests -am test -Dtest='ResponseTrailersHttp11Test,StreamingBodyTest' -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.
- [ ] **Step 3: Implement.** Thread the `Response` into `writeBodyChunked(Response, Body, ByteBuffer, WritableByteChannel)`; replace the constant terminator by `writeLastChunk(Headers trailers, ...)`. In `writeBody`, flush after each read for streaming bodies. Keep `Http2Connection` untouched (HTTP/2 trailers already work, `:513-525`).
- [ ] **Step 4: Run** `./mvnw -ntp install` in chappe (full reactor incl. `chappe-conformance`) → green. Record nothing in chappe `BENCH`/`BENCHMARKS.md` unless a benchmark is run.
- [ ] **Step 5: Commit** (chappe repo) — `feat(http): write HTTP/1.1 response trailers and flush streaming bodies per read`.

---

### Task 5.3 (chappe): generic HTTP/1.1 connection upgrade

**Repository:** chappe, same branch as Task 5.2.

**Files:**
- Create: `chappe-api/src/main/java/io/vidocq/chappe/api/ConnectionUpgrade.java`, `UpgradeHandler.java`, `UpgradedConnection.java`
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/HttpConnection.java` (next to the WebSocket branch `:201-217`)
- Create: `chappe-http/src/main/java/io/vidocq/chappe/http/UpgradedConnectionImpl.java` (package-private if possible)
- Modify: `chappe-http/src/main/java/io/vidocq/chappe/http/h2/Http2Connection.java` (refuse a `ConnectionUpgrade`)
- Test: `chappe-tests/src/test/java/io/vidocq/chappe/tests/ConnectionUpgradeTest.java`
- Modify: chappe `CLAUDE.md` "Extension SPI" list (one line), README feature list if it lists upgrade/WebSocket

**Interfaces:**
- Produces:

```java
package io.vidocq.chappe.api;

/** Takes over a connection after the transport has written the upgrade response. */
@FunctionalInterface
public interface UpgradeHandler {
    /**
     * Called on the connection's thread once the status line and headers are on the wire. The
     * connection stays open after this method returns, until {@link UpgradedConnection#close()}
     * is called or the peer closes it; an exception closes it.
     */
    void onUpgrade(UpgradedConnection connection) throws Exception;
}

/** The raw byte streams of an upgraded HTTP/1.1 connection. */
public interface UpgradedConnection extends java.io.Closeable {
    /** Bytes sent by the peer after the request head, including any already buffered by the transport. */
    java.io.InputStream input();
    /** Unbuffered-by-contract output: {@code flush()} puts the bytes on the wire. */
    java.io.OutputStream output();
    /** The request that asked for the upgrade. */
    Request request();
    /** Closes the connection; idempotent. */
    @Override void close() throws java.io.IOException;
}

/**
 * Response telling the HTTP/1.1 transport to write {@code status} and {@code headers} (no
 * Content-Length, no Transfer-Encoding, no body) and then hand the connection to {@code handler}.
 * HTTP/2 answers 505 (RFC 9113 section 8.6 forbids the Upgrade mechanism).
 */
public final class ConnectionUpgrade implements Response {
    public ConnectionUpgrade(StatusCode status, Headers headers, UpgradeHandler handler) { ... }
    public UpgradeHandler handler() { ... }
    @Override public StatusCode status() { ... }
    @Override public Headers headers() { ... }
    @Override public Body body() { return Body.empty(); } // or the existing empty-body factory
}
```

  (Use chappe's real `StatusCode`/`Headers`/`Body` factory names — read `Response.java` and `WebSocketUpgrade.java` first; if `StatusCode` cannot carry 101, add the constant.)
- `HttpConnection`: `if (response instanceof ConnectionUpgrade up)` → drain the request body if any, write `HTTP/1.1 <status> <reason>\r\n` + the response headers (no `Date`/`Server` requirement; reuse the writer's header serialisation if it can be called without body framing) + `\r\n`, flush, build `UpgradedConnectionImpl(readChannel, writeChannel, readBuffer leftovers, closeable, request)`, call `handler.onUpgrade(conn)`, then park the connection thread until `conn.close()` or the input reaches EOF and the handler closed — the simplest correct rule: park on a `CountDownLatch` released by `close()`; the input stream returning -1 does not close by itself (the application decides). Any exception from `onUpgrade` → close. The connection idle timeout does not apply after the upgrade (document).

- [ ] **Step 1: Failing tests** (`ConnectionUpgradeTest`, raw socket): `upgradeWritesStatusAndHeadersThenHandsOverTheStreams` — handler echoes each line read from `input()` back on `output()` until `"bye"`, then `close()`; client sends `GET /u HTTP/1.1\r\nHost: h\r\nUpgrade: echo\r\nConnection: Upgrade\r\n\r\nhello\n` in ONE write (so `hello` sits in the transport's read buffer), asserts `HTTP/1.1 101` + `Upgrade: echo` header, then reads `hello`, sends `bye\n`, expects EOF. `connectionStaysOpenAfterOnUpgradeReturns` — `onUpgrade` stores the connection and returns; a second thread writes after 200 ms; client receives it. `exceptionInOnUpgradeClosesTheConnection`. `http2RefusesUpgrade` — via the existing HTTP/2 test helper (`Http2Test` pattern) → 505.
- [ ] **Step 2: Run** `./mvnw -ntp -pl chappe-tests -am test -Dtest=ConnectionUpgradeTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL (compilation).
- [ ] **Step 3: Implement** (types above, `HttpConnection` branch, `Http2Connection` 505).
- [ ] **Step 4: Run** `./mvnw -ntp install` in chappe → green (incl. conformance). Then in Foy: `./mvnw -ntp -pl foy-core,foy-chappe install` → still green against the new SNAPSHOT.
- [ ] **Step 5: Commit** (chappe repo) — `feat(api): generic HTTP/1.1 connection upgrade`. Do not push; the controller pushes and opens the chappe PR at phase end.

---

### Task 5.4: Streaming response — commit hands chappe a live body

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/ResponsePipe.java` (bounded byte pipe: writer side used by the servlet output, `InputStream` side read by chappe)
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/ResponseHead.java` (the once-only handoff to chappe's thread: `CompletableFuture<Response>`)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/ServletOutputStreamImpl.java` (bounded buffer of `bufferSize`, overflow → commit, after commit writes go to the pipe)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletResponseImpl.java` (`setBufferSize` enforced, `flushBuffer` commits for real, `isCommitted` without draining side effect, `reset`/`resetBuffer` after commit ISE as today, close/complete ends the pipe)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/ChappeServletBridge.java` (`handle` runs the pipeline on a child virtual thread; every `return toChappeResponse(res)`/`error(...)`/`notFound()` becomes "complete the head if not committed, else end or abort the stream")
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/bridge/ResponsePipeTest.java`, `foy-core/src/test/java/io/vidocq/foy/internal/ServletStreamingEndToEndTest.java`; keep every existing test green

**Interfaces:**
- Produces:

```java
/** Bounded single-producer byte pipe between a servlet's output and chappe's body reader. */
final class ResponsePipe {
    ResponsePipe(int capacityBytes);
    /** Blocks while full; IOException("client disconnected") once the reader side is closed. */
    void write(byte[] b, int off, int len) throws IOException;
    /** Non-blocking readiness used by WriteListener (Task 5.8): true when at least one byte fits. */
    boolean hasCapacity();
    /** Registers a one-shot callback fired when capacity frees up (Task 5.8). */
    void onCapacity(Runnable r);
    /** Normal end of body: the reader sees EOF after the buffered bytes. Idempotent. */
    void finish();
    /** Abnormal end: the reader's next read throws IOException, which makes chappe drop the connection. */
    void abort(Throwable cause);
    /** The chappe-facing side; close() by chappe marks the pipe broken (client gone). */
    InputStream reader();
}
```

  - `HttpServletResponseImpl` gains package-private `void bindCommitTarget(Consumer<HttpServletResponseImpl> onCommit)` (called by the bridge) and `void finishBody()` / `void abortBody(Throwable)`; `commit()` is the single place that freezes status/headers/cookies (incl. the session cookie — move `maybeAttachSessionCookie` so it runs before the first commit) and calls `onCommit`.
  - Bridge: `handle(Request)` creates `ResponseHead head`, starts `Thread.ofVirtual().name("foy-request-", n)` running the current pipeline with `RequestContext.CURRENT` (chappe's `ScopedValue`) re-bound to the chappe value (`ScopedValue.where(RequestContext.CURRENT, value).run(...)` — check chappe's `RequestContext` API for the exact accessor), then returns `head.await()`. On commit: `head.complete(streamingResponse(res, pipe))` with body `Body.of(pipe.reader(), declaredContentLengthOr(-1))`. At pipeline end: if not committed → `head.complete(toChappeResponse(res))` (unchanged fast path); else `finishBody()`. Exception after commit → log once, `abortBody(e)`. `endSessionAccess` moves to the pipeline thread's `finally`.
  - The container strips an application `Transfer-Encoding` header (container-managed framing) and passes the declared `Content-Length` as the body length, never as a header chappe would duplicate (research §1.2 pitfalls a/b).
  - Buffer size: default 8192, `setBufferSize(n)` before any write sets the threshold; writing past it commits (status + headers on the wire). `getBufferSize()` returns the threshold. Pipe capacity: one buffer size (min 8192).
- Consumes: chappe Task 5.2 (per-read flush for known-length streaming bodies).

- [ ] **Step 1: Failing tests.** `ResponsePipeTest`: `readerSeesBytesThenEof`, `writerBlocksWhenFullAndResumes`, `writeAfterReaderCloseThrows`, `abortMakesTheReaderThrow`, `onCapacityFiresOnce`. `ServletStreamingEndToEndTest` (raw `Socket` for timing): `flushBufferSendsTheFirstChunkWhileTheServletRuns` (mirror of the TCK: write line, `flushBuffer()`, sleep 1500 ms, write line; client timestamps both lines, delta ≥ 1000 ms), `bufferOverflowCommits` (`setBufferSize(16)`, write 32 bytes, then `isCommitted()` true and `setHeader` ignored; client receives the 32 bytes), `setBufferSizeAfterWriteThrows`, `smallResponseKeepsContentLength` (no flush → `Content-Length` header, not chunked), `declaredContentLengthIsStreamedWithoutChunking`, `applicationTransferEncodingIsIgnored`, `clientDisconnectFailsTheNextWrite` (client reads the first chunk and closes; servlet loops `write`+`flush` every 50 ms; expect an `IOException` within 5 s and `requestDestroyed` fired — use a `ServletRequestListener` + latch), `exceptionAfterCommitAbortsTheConnection` (servlet flushes then throws; client gets the partial body then EOF, no second status line), `asyncWriteAfterCommitStreams` (async `start` thread flushes twice with a pause).
- [ ] **Step 2: Run** `./mvnw -ntp -pl foy-core test -Dtest='ResponsePipeTest,ServletStreamingEndToEndTest'` → FAIL.
- [ ] **Step 3: Implement** `ResponsePipe` (a `ReentrantLock` + two `Condition`s over a ring buffer; no `PipedInputStream` — it ties to thread liveness), `ResponseHead`, response/stream changes, bridge child thread. Every `return` path of `ChappeServletBridge.handle(Request, List)` goes through one method `respond(HttpServletResponseImpl res, Supplier<Response> uncommitted)`; grep `bodyBytes()` callers (`:353`, `:439`) and replace "empty body" checks with `isCommitted()`/a written-bytes counter.
- [ ] **Step 4: Run** `./mvnw -ntp -pl foy-core,foy-chappe,foy-cdi-vauban install` → green; `./mvnw -ntp -Ptck -pl foy-tck test` → green; TCK `--family api` + `--family spec` → `servletResponseTests.testFlushBuffer*` pass, zero per-class regression, `reflection=0`.
- [ ] **Step 5: Commit** — `feat(core): stream committed responses through chappe`.

---

### Task 5.5: Response trailers

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletResponseImpl.java` (`setTrailerFields`, `getTrailerFields`)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/ChappeServletBridge.java` (the Foy `Response` given to chappe overrides `trailers()` lazily)
- Create (if the builder cannot carry a lazy supplier): `foy-core/src/main/java/io/vidocq/foy/internal/bridge/FoyResponse.java` — `record FoyResponse(StatusCode status, Headers headers, Body body, Supplier<Map<String,String>> trailerSupplier) implements Response` with `trailers()` evaluating the supplier once
- Test: extend `foy-core/src/test/java/io/vidocq/foy/internal/ServletTrailerEndToEndTest.java` (exists — read it first; it covers request trailers)

**Interfaces:**
- Produces:
  - `setTrailerFields(Supplier<Map<String,String>>)`: ISE when committed; when the request protocol is `HTTP/1.0`; when HTTP/1.1 and a `Content-Length` was declared (not chunked). Otherwise stores the supplier. `getTrailerFields()` returns it (`null` if none).
  - A response with a supplier is sent with body length -1 (chunked on HTTP/1.1 keep-alive) even when it was never committed; its `trailers()` calls the supplier once after the body, drops names forbidden by RFC 9110 §6.5.1 (`Transfer-Encoding`, `Content-Length`, `Host`, `Content-Type`, `Content-Encoding`, `Content-Range`, `Trailer`, `Authorization`, `Set-Cookie`, `Cache-Control`, `Expect`, `Max-Forwards`, `Pragma`, `Range`, `TE`, `Age`, `Expires`, `Date`, `Location`, `Retry-After`, `Vary`, `Warning`, `Proxy-Authenticate`, `WWW-Authenticate`), and validates values like headers (Phase 4 `checkHeaderText`).
- Consumes: Task 5.2 (chappe writes HTTP/1.1 trailers), Task 5.4 (single commit path).

- [ ] **Step 1: Failing tests:** `trailersOnHttp11Chunked` (raw socket, exact TCK servlet: sets `Transfer-Encoding: chunked` itself, supplier `myTrailer=foo`, writes `Current trailer field:` + entry; assert the bytes after the marker are `myTrailer:foo\r\n0\r\nmyTrailer: foo\r\n\r\n`), `setTrailerFieldsOnHttp10Throws`, `setTrailerFieldsAfterCommitThrows`, `setTrailerFieldsWithContentLengthThrows`, `forbiddenTrailerNamesAreDropped`, `trailersOverHttp2` (JDK `HttpClient` with prior knowledge is not possible — use chappe's HTTP/2 client from `io.vidocq.chappe.api.client` if it exposes trailers; otherwise test `FoyResponse.trailers()` at unit level and say so).
- [ ] **Step 2: Run** `./mvnw -ntp -pl foy-core test -Dtest=ServletTrailerEndToEndTest` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -ntp -pl foy-core,foy-chappe install`; TCK `--family api` → the 3 `HttpServletResponse40Tests.Trailer*` pass; `--family spec`; zero regression, `reflection=0`.
- [ ] **Step 5: Commit** — `feat(core): response trailer fields`.

---

### Task 5.6: Async lifecycle — timeout to the error page, listener events at the right time

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/async/AsyncContextImpl.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/ChappeServletBridge.java` (`awaitAsyncIfStarted` `:385-443`)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java` (`startAsync` fires `onStartAsync` on the previous cycle's listeners)
- Test: extend `foy-core/src/test/java/io/vidocq/foy/internal/ServletAsyncEndToEndTest.java`; `foy-core/src/test/java/io/vidocq/foy/internal/async/AsyncContextImplTest.java` (create if absent)

**Interfaces:**
- Produces:
  - Timeout clock starts when the dispatch that called `startAsync` returns (today: when `awaitCompletion` starts, which is the same point — keep it, but make it re-arm on each new cycle); `timeout <= 0` = none.
  - On timeout: `onTimeout` on every listener of the cycle; if after that neither `complete()` nor `dispatch()` was called, the container performs an **error dispatch with status 500** through the existing error-page machinery (`maybeHandleError`, with `jakarta.servlet.error.status_code=500` and no exception), and if no error page handles it, answers a plain 500; then completes. The bare 503 (`:438-441`) and the `asyncTimeoutYields503` test go away (replace by `timeoutWithoutErrorPageYields500` and `timeoutDispatchesTheErrorPage`).
  - `onComplete` fires once at the end of the whole async cycle (after `complete()`, after the last ASYNC dispatch returned without a new `startAsync`, after a timeout/error completion) — never at `dispatch()` time.
  - `startAsync()` on a request whose previous cycle had listeners → `onStartAsync` on each, and the listeners are **not** carried over (they must re-register, §2.3.3.3).
  - Listener exceptions are logged (WARNING) instead of swallowed silently; `onError` fires when the async `start` runnable throws or when a write fails because the client disconnected (pipe `IOException` from Task 5.4), followed by the same error dispatch rules as a timeout (status 500 unless the response is committed, then abort).
- Consumes: Task 5.4 (`isCommitted()` without side effect, abort path).

- [ ] **Step 1: Failing tests:** `timeoutDispatchesTheErrorPage` (error page `500 → /err` servlet echoing the status attribute; servlet `startAsync`, timeout 50 ms, listener records events; expect body from `/err`, status 500, events `[onTimeout, onComplete]`), `timeoutWithoutErrorPageYields500`, `listenerCompletingInOnTimeoutPreventsTheErrorDispatch`, `onCompleteFiresAfterTheAsyncDispatchNotBefore` (dispatch to a servlet that records whether `onComplete` already ran → false), `onStartAsyncOnSecondCycle`, `startRunnableExceptionFiresOnError`.
- [ ] **Step 2: Run** `./mvnw -ntp -pl foy-core test -Dtest='ServletAsyncEndToEndTest,AsyncContextImplTest'` → FAIL.
- [ ] **Step 3: Implement.** Keep the bridge's blocking wait (the pipeline thread is a virtual thread; "releasing the container thread" is satisfied because chappe's thread is free once the head is sent, and the servlet's `service()` has returned); move event firing to the cycle end.
- [ ] **Step 4: Run** `./mvnw -ntp -pl foy-core,foy-chappe install`; TCK `--family api` + `--family spec` (watch every `spec.async*` and `api.jakarta_servlet.asynccontext*` class) → zero regression, `reflection=0`.
- [ ] **Step 5: Commit** — `fix(core): async timeout error dispatch and listener event order`.

---

### Task 5.7: Non-blocking input — `ReadListener`

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/ServletInputStreamImpl.java`
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/ReadPump.java` (virtual thread doing blocking reads of chappe's stream into a small buffer and scheduling callbacks)
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/CallbackSerializer.java` (one callback at a time per request, run under the context class loader as TCCL; reused by Task 5.8 and 5.9)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java` (`getInputStream` knows whether async started / upgraded)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/bridge/ServletInputStreamImplTest.java`, `foy-core/src/test/java/io/vidocq/foy/internal/ServletNonBlockingIoEndToEndTest.java`

**Interfaces:**
- Produces:

```java
/** Runs listener callbacks for one request one at a time, with the application's class loader. */
final class CallbackSerializer {
    @FunctionalInterface interface ThrowingRunnable { void run() throws Exception; }
    CallbackSerializer(ClassLoader applicationLoader);
    /** Queues r; runs on a virtual thread after any running callback; exceptions go to onError. */
    void submit(ThrowingRunnable r, java.util.function.Consumer<Throwable> onError);
}
```

  - `setReadListener(null)` → NPE; called when the request is neither async-started nor upgraded → ISE; called twice → ISE.
  - Once set: the stream switches to non-blocking mode. `ReadPump` reads up to 8 KiB from chappe's stream (blocking, on its own virtual thread) into a buffer; `isReady()` returns true while buffered bytes remain (or EOF is buffered and not yet reported), false otherwise — and a false `isReady()` arms the next `onDataAvailable`. `read(...)` when `isReady()` last returned false → ISE. `onDataAvailable` is submitted when data arrives and the listener is armed (the first call happens as soon as data is available after `setReadListener`); `onAllDataRead` once at EOF after the buffer is drained; read errors → `onError`. Callbacks go through `CallbackSerializer` and never run concurrently with the dispatch that set the listener (start them only after that dispatch returns — the bridge signals it).
  - `read(ByteBuffer)` overridden (6.1 contract: 0 when no space, blocking mode blocks until ≥1 byte, -1 at EOF, NPE on null, non-blocking without `isReady()` → ISE).
- Consumes: Task 5.6 (async cycle end: an async request does not complete while a read listener is active unless `complete()` is called).

- [ ] **Step 1: Failing tests:** unit — `setReadListenerNullThrowsNpe`, `setReadListenerWithoutAsyncThrowsIse`, `setReadListenerTwiceThrowsIse`, `readByteBufferBlockingAndEof`, `readWhenNotReadyThrowsIse`. E2E (raw `Socket`, chunked upload): `twoChunksYieldTwoCallbacks` (send `5\r\nHello\r\n`, sleep 1000 ms, `5\r\nWorld\r\n0\r\n\r\n`; servlet: `startAsync`, listener prints `=onDataAvailable`, loops `while (isReady() && (n = read(buf)) != -1)`, prints data, `onAllDataRead` prints `=onAllDataRead` and `complete()`; assert ordered `=onDataAvailable =Hello =onDataAvailable =World =onAllDataRead`), `callbacksNeverOverlap` (listener sleeps 100 ms per call and asserts an atomic in-callback flag), `readListenerInAsyncDispatchWithoutStartAsyncThrowsIse`.
- [ ] **Step 2: Run** `./mvnw -ntp -pl foy-core test -Dtest='ServletInputStreamImplTest,ServletNonBlockingIoEndToEndTest'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -ntp -pl foy-core,foy-chappe install`; TCK `--family api` + `--family spec` → `ReadListenerTests`, `ReadListener1Tests` pass, zero regression, `reflection=0`.
- [ ] **Step 5: Commit** — `feat(core): non-blocking request input with ReadListener`.

---

### Task 5.8: Non-blocking output — `WriteListener`

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/ServletOutputStreamImpl.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletResponseImpl.java` (non-blocking `close()`)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/bridge/ServletOutputStreamImplTest.java`, extend `ServletNonBlockingIoEndToEndTest`

**Interfaces:**
- Produces:
  - `setWriteListener(null)` → NPE; not async-started and not upgraded → ISE; twice → ISE.
  - Once set: `onWritePossible` is submitted through `CallbackSerializer` after the dispatch that set it returns. `isReady()` = the response buffer has room before commit, or `ResponsePipe.hasCapacity()` after commit; a false `isReady()` arms `ResponsePipe.onCapacity(...)` to submit the next `onWritePossible`. A write (any `write`/`flush`/`print` on the writer) while `isReady()` last returned false → ISE. In non-blocking mode a write never blocks: bytes that do not fit are kept by the stream and the pipe is fed as capacity frees up (bounded by one extra buffer; beyond that `isReady()` stays false). Non-blocking `close()` returns immediately and finishes the body once the pending bytes are drained. Write failures → `onError`.
  - `write(ByteBuffer)` overridden (blocking mode writes all; non-blocking without `isReady()` → ISE leaving the buffer untouched; NPE on null).
- Consumes: Task 5.4 (`ResponsePipe.hasCapacity/onCapacity`), Task 5.7 (`CallbackSerializer`).

- [ ] **Step 1: Failing tests:** unit — `setWriteListenerNullThrowsNpe`, `setWriteListenerWithoutAsyncThrowsIse`, `writeByteBufferWritesAll`, `writeWhenNotReadyThrowsIse`. E2E — `onWritePossibleIsCalled` (TCK shape: `startAsync`, `setWriteListener`, `onWritePossible` writes `=onWritePossible` and `complete()`), `largeNonBlockingWriteResumesOnCapacity` (listener writes 1 MiB in a `while (isReady())` loop with a slow client reading 64 KiB every 50 ms; all bytes arrive in order, `onWritePossible` called more than once).
- [ ] **Step 2: Run** `./mvnw -ntp -pl foy-core test -Dtest='ServletOutputStreamImplTest,ServletNonBlockingIoEndToEndTest'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -ntp -pl foy-core,foy-chappe install`; TCK `--family api` + `--family spec` → `WriteListenerTests` passes, zero regression, `reflection=0`.
- [ ] **Step 5: Commit** — `feat(core): non-blocking response output with WriteListener`.

---

### Task 5.9: HTTP Upgrade — `HttpUpgradeHandler` and `WebConnection`

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java` (`upgrade(Class)` `:741`)
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/WebConnectionImpl.java`
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/UpgradedInputStream.java`, `UpgradedOutputStream.java` (or reuse `ServletInputStreamImpl`/`ServletOutputStreamImpl` constructors over raw streams if that stays clean)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/ChappeServletBridge.java` (after the REQUEST chain returns with an upgrade requested: fire `requestDestroyed`, complete the head with a `ConnectionUpgrade`)
- Modify: `foy-core/src/main/java/module-info.java` only if a new package is created (avoid it)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/ServletUpgradeEndToEndTest.java`

**Interfaces:**
- Consumes: Task 5.3 (`ConnectionUpgrade`, `UpgradeHandler`, `UpgradedConnection`), Task 5.7/5.8 (`ReadPump`, `CallbackSerializer`, write readiness), Task 5.4 (`ResponseHead`).
- Produces:
  - `upgrade(Class<T>)`: on HTTP/2 or HTTP/1.0 → `IOException("HTTP upgrade is not supported over <protocol>")`; otherwise instantiates `T` through `VidocqServletContext.componentFactory().newInstance(clazz)` (failure → `ServletException`), marks the request upgraded, returns the instance. Calling it twice → ISE.
  - After `service()` and the filter chain return, the bridge sends `ConnectionUpgrade(status set by the application, the application's headers, handler)`; any body written by the application is discarded (TCK writes `End of Test` after `upgrade`). `requestDestroyed` fires before the upgrade response; the async/error machinery does not run for an upgraded request.
  - `UpgradeHandler.onUpgrade(conn)` → `handler.init(new WebConnectionImpl(conn, ...))` under the context class loader, then returns (chappe keeps the connection open until `WebConnection.close()`).
  - `WebConnection.getInputStream()`/`getOutputStream()`: `ServletInputStream`/`ServletOutputStream` over `conn.input()`/`conn.output()`, `setReadListener`/`setWriteListener` allowed (the request is upgraded), same readiness rules as Tasks 5.7/5.8; the output writes straight to the connection (flush = on the wire). `WebConnection.close()` → `handler.destroy()` once, then `conn.close()`. Peer EOF → `onAllDataRead`, the application decides when to close; a read error → `onError` then close + `destroy()`.
  - Context undeploy closes open upgraded connections (`destroy()` called) — keep a per-context set of `WebConnectionImpl`.

- [ ] **Step 1: Failing tests** (raw `Socket`, mirror of the TCK): `upgradeEchoesThroughAReadListener` — servlet: if `Upgrade` header present → `setStatus(101)`, `setHeader("Upgrade","YES")`, `setHeader("Connection","Upgrade")`, `upgrade(EchoHandler.class)`, then `getWriter().println("End of Test")`; `EchoHandler.init`: `ReadListener` printing `=onDataAvailable` + data lines, `onAllDataRead` → close; output first prints `===============TCKHttpUpgradeHandler.init`; client sends the TCK request head (`POST`, `Upgrade: YES`, `Connection: Upgrade`, no body length), then `Hello` (flush), `World` (flush); assert `101`, the init banner, then `Hello` and `World` in order; `End of Test` never appears. `destroyCalledOnClose`, `upgradeOverHttp10Throws`, `upgradeHandlerInstantiationFailureIsServletException`, `requestDestroyedFiresBeforeInit`.
- [ ] **Step 2: Run** `./mvnw -ntp -pl foy-core test -Dtest=ServletUpgradeEndToEndTest` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -ntp -pl foy-core,foy-chappe,foy-cdi-vauban install`; TCK `--family api` → `HttpUpgradeHandlerTests` passes; `--family spec`; zero regression, `reflection=0`.
- [ ] **Step 5: Commit** — `feat(core): HTTP upgrade with HttpUpgradeHandler and WebConnection`.

---

### Task 5.10: Phase exit — documentation, baseline, follow-ups

**Files:**
- Modify: `TCK.md` (§0 entry: date, figures per family, classes improved, push skipped), `foy-tck/tck-baseline.txt` (refresh from the full run)
- Modify: `docs/superpowers/plans/2026-10-07-servlet-completion-roadmap.md` ("Phase 5 exit / follow-ups" section: shipped, figures, remaining failures by phase, open bugs, deferred minors; fix the push figure 7 → 6)
- Modify: `docs/en/modules/ROOT/pages/{usage,concepts,internals,reference,tck,migration}.adoc` — streaming and buffer semantics, async lifecycle, non-blocking I/O, trailers, upgrade, push not supported; migration: behaviour changes for embedders (responses above the buffer size are now streamed and chunked when no `Content-Length` is set; async timeout now 500 through the error page instead of 503; `newPushBuilder()` returns `null`; servlet code now runs on a Foy virtual thread, not chappe's connection thread — `RequestContext.CURRENT` is re-bound, other `ThreadLocal`s set by chappe filters are not visible)
- Modify: `README.md` feature list if it lists Servlet features
- Modify: `BUG.md` (any open item found on the way; close Phase 5 items with commits)

- [ ] **Step 1:** `./mvnw -ntp install` (whole reactor) and `./mvnw -ntp -Ptck -pl foy-tck test` → green.
- [ ] **Step 2:** `./run-official-tck-servlet6.1.sh --all > /tmp/foy-tck-phase5.log 2>&1` (≈11 min, run in background and poll), then the family runs that `--all` does not cover (`GetServletRegistrationsTest` appears only in family mode — run `--family pluggability`). Tally, per-class join against the baseline: zero regression, `join -v` empty both sides, `reflection=0`. Target 1660/1714 passing, 6 skipped.
- [ ] **Step 3:** refresh `foy-tck/tck-baseline.txt` from the run (same format as today), docs and roadmap as listed.
- [ ] **Step 4: Commit** — `build(tck): refresh the baseline to the Phase 5 exit tally` and `docs: document streaming, async, non-blocking I/O, trailers and upgrade (Phase 5 exit)`.
