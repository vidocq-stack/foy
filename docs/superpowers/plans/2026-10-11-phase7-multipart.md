# Phase 7 — Multipart and Request Body Limits Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Foy implements Servlet 6.1 file upload (section 3.2) end to end. That covers a streaming RFC 7578 parser, the `@MultipartConfig` / `<multipart-config>` / `setMultipartConfig` configuration of the executing servlet, `maxFileSize` / `maxRequestSize` / `fileSizeThreshold` / `location` with disk spill into the context temporary directory, the `getParts`/`getPart` exception contract, and form fields exposed as request parameters. It also closes the Phase 1 leftovers on request bodies: a cap on the urlencoded form body, the encoding lock once a body is parsed (section 3.12), a consistent state after a failed body read, and a Foy-side cap on bodies without a `Content-Length`. The Servlet TCK signature test is wired behind `-Psigtest`. The TCK goes from 1692 to **1700 passing / 1714 run** (+8, `PartTests` and `Part1Tests`).

**Architecture:**
- `HttpServletRequestImpl` gets **one body owner**: the application (`getInputStream`/`getReader`) or the container (form parameters, parts). The container reads through the same tracked `ServletInputStreamImpl`, so trailer readiness and chappe's end-of-request hand-back see the read. Once the container owns the body, the application gets an empty stream (section 3.1.1).
- Every body Foy reads that declares no `Content-Length` is wrapped in a `BoundedInputStream`. Chappe already bounds `Content-Length` bodies (`ServerConfig.maxRequestSize`).
- The effective `MultipartConfigElement` goes from `WebAppModel.ServletDecl` (and the dynamic registration or its class annotation) into the servlet's registration. `VidocqServletContext.multipartConfig(servletName)` freezes it at `markInitialized()`. The request resolves it for the servlet executing on the calling thread (`enterServlet`, which already survives wrappers and async threads).
- `MultipartParser` is a byte-level streaming parser (Knuth-Morris-Pratt delimiter search, CRLF-anchored). It writes each part into a `PartSink`: memory up to the threshold, then a 0600 temporary file created by a per-request `PartStorage` under the resolved location. The bridge closes the `PartStorage` once the request (async cycle included) is over, which deletes every container-owned file.

**Tech Stack:** Java 25, Jakarta Servlet 6.1, foy-api / foy-core / foy-chappe / foy-tck, chappe 0.4.0-SNAPSHOT (unchanged in this phase), JUnit 5 (unit tests with a fake chappe `Request`, end-to-end tests over real chappe through `TestServerLauncher`, raw sockets where keep-alive matters), official Servlet TCK via Arquillian, the TCK's own `servlet.tck.signature.ServletSigTest`.

**Spec:** `docs/superpowers/plans/2026-10-07-servlet-completion-roadmap.md`, sections "Phase 7 — Finishing", "Phase 1 follow-ups" (Phase 7 line) and "Phase 6 exit / follow-ups" (multipart 8), amended by the maintainer decisions below. Research notes: `phase7-research.md` of 2026-10-11, summarised in "Evidence". Normative sources:
- Jakarta Servlet 6.1: 3.1.1 (when parameters are available), 3.2 (file upload), 3.12 (request data encoding), 4.8.1 (temporary working directories), 8.1.5 (`@MultipartConfig`).
- Javadoc of `HttpServletRequest#getParts/getPart`, `Part` (`write`, `delete`, `getSubmittedFileName`), `MultipartConfigElement`, `ServletRegistration.Dynamic#setMultipartConfig`.
- RFC 7578 (multipart/form-data), RFC 2046 section 5.1 (delimiters, bchars), RFC 8187 (`filename*`).

Check each section number against the 6.1 PDF before quoting it in Javadoc.

## Maintainer decisions (binding)

- **Scope:** multipart (section 3.2, `MultipartConfigElement`, `Part`, `@MultipartConfig`, `<multipart-config>`, `ServletRegistration.Dynamic.setMultipartConfig`) and the Phase 1 leftovers:
  - the cap on the urlencoded form body;
  - the request encoding locked once parameters are parsed (section 3.12);
  - a consistent request state after a failed body read;
  - a Foy-side cap on chunked request bodies;
  - wiring the TCK signature test profile.
- **Not in scope:** the CDI async and error-page gaps (BUG-20261010-07, -08) and the docs-only certification decision.
- **`getParts`/`getPart` contract:**
  - With no multipart-config on the target servlet, a multipart request throws `IllegalStateException`.
  - A non-multipart request throws `ServletException`.
- **Container limits:** prudent, configurable through `FoyChappeBoot` and `DeployOptions`:
  - at most 50 parts per request;
  - at most 8 KiB of headers per part;
  - the multipart body bounded by the request-body cap;
  - plus the multipart-config `maxFileSize`/`maxRequestSize`, which throw `IllegalStateException` (Javadoc).
- **Temporary storage:**
  - The per-context `ServletContext.TEMPDIR` lives under `java.io.tmpdir`. It is already created by `WebAppDeployer.createTempDir`.
  - A relative `location` resolves against TEMPDIR.
  - Parts above `fileSizeThreshold` spill to 0600 files (POSIX).
  - The files are deleted at the end of the request, or at the end of the async cycle.
  - `Part.write(name)` resolves a relative name against `location`. A relative name may never use `..` to leave `location`.
  - `Part.delete()` removes the temporary file.
- **Parser:**
  - streaming;
  - boundary only after a CRLF;
  - RFC 7578 headers, UTF-8 aware, `filename`/`filename*`;
  - `name=` never matched inside `filename=`;
  - a truncated body is an error;
  - repeated headers are kept;
  - `getSubmittedFileName()` is returned raw (documented: callers sanitise).
- **Body consumption:**
  - `getParts` goes through the tracked body, mutually exclusive with `getInputStream`/`getReader`/`ReadListener`.
  - Drain and hand-back with chappe stay correct.
- **Form fields:** multipart fields become request parameters, after the query string (section 3.1).
- **Existing tests:** `getPartsReturnsEmptyWhenNotMultipart` asserts non-conformant behaviour. It is rewritten.
- **Branch and exit:**
  - Branch `pr/ybl/servlet-completion-phase7` from `main`.
  - Exit: 1700 / 1714, zero per-class regression, `reflection=0`, sigtest result recorded.

## Rulings this plan takes (on the decisions' open points)

1. **Order of the `getParts` checks:**
   1. The media type comes first (`ServletException`).
   2. Then the executing servlet's configuration (`IllegalStateException`).
   3. Then the body already taken by `getInputStream()`/`getReader()` (`IllegalStateException`).
   4. Then the parse itself.

   The Javadoc lists no order. The four TCK tests always have a configuration, so either order passes. Foy follows Jetty, not Tomcat (which checks the configuration first): a request that is not multipart has no parts whatever the configuration, and a checked `ServletException` is what a servlet handling mixed content types expects to catch.
2. **Parse failures:**
   - A limit (`maxFileSize`, `maxRequestSize`, part count, part header size) throws `MultipartLimitException extends IllegalStateException`.
   - A malformed or truncated body throws `MalformedMultipartException extends IOException`.
   - An I/O error stays an `IOException`.
   - The first failure is cached and rethrown, as the same instance, by every later `getParts`/`getPart`. The body is never read twice.
3. **Parameters:**
   - Parameters are computed once. The query string is settled before any body read.
   - A body failure (form cap, multipart limit, I/O) propagates from the **first** `getParameter*` call, as `IllegalStateException` or `UncheckedIOException`. Later calls see the query-string parameters only.
   - A multipart body feeds parameters when one of these holds:
     - the request is a POST and the executing servlet has a configuration;
     - the parts were already parsed through `getParts`. The fields are then merged into the parameters whatever the method, as Tomcat does.
   - Without a configuration, a multipart body gives no parameter and no exception, and only `getParts` throws. No "parse without configuration" switch (Tomcat's `allowCasualMultipartParsing`) is added.
4. **Field charset, most specific first:**
   1. the part's own `Content-Type` charset;
   2. else the request encoding (`setCharacterEncoding`, the request `Content-Type` charset, `<request-character-encoding>`);
   3. else the `_charset_` field (RFC 7578 section 4.6);
   4. else ISO-8859-1 (section 3.12 default, the same as the urlencoded path).
5. **Encoding lock:** set when the **body** is parsed (form or parts), not when only the query string is. The query string is always decoded as UTF-8, so a lock there changes nothing that was decoded. It would only make a later `setCharacterEncoding` after a GET `getParameter` a no-op, a needless TCK risk.
6. **Container limits (`RequestLimits`, all on `FoyChappeBoot.Builder` and `DeployOptions`):**

   | Setting | Default | `-1` | Exceeded |
   |---|---|---|---|
   | `maxChunkedRequestBodySize` | 10 MiB, chappe's `Content-Length` default | unlimited | `RequestBodyTooLargeException` (an `IOException`) from the read |
   | `maxFormBodySize` | 2 MiB, Tomcat's `maxPostSize` | unlimited | `IllegalStateException` from `getParameter*`, checked against `Content-Length` before reading |
   | `maxMultipartParts` | 50, Tomcat's `maxPartCount` after CVE-2023-24998 | unlimited | `MultipartLimitException` |
   | `maxMultipartPartHeaderSize` | 8 KiB per part | not allowed (must be ≥ 1) | `MultipartLimitException` |

   - `maxChunkedRequestBodySize` applies only to bodies **without** `Content-Length`: HTTP/1.1 chunked, and HTTP/2 without `content-length`.
   - `maxRequestSize` is also checked against `Content-Length` before reading.
   - There is no separate aggregate in-memory cap. The in-memory total is bounded by the body size: chappe's `maxRequestSize` with `Content-Length`, `maxChunkedRequestBodySize` without. This is documented.
7. **Storage:**
   - The location resolves this way:
     - an empty `location` uses TEMPDIR;
     - a relative one resolves under TEMPDIR;
     - an absolute one is used as is.
   - It is created on the first spill (`Files.createDirectories`).
   - Without a TEMPDIR (its creation failed at deploy) and with an empty or relative location, the first spill throws `IOException`.
   - The threshold rule: the content goes to disk as soon as its size exceeds `fileSizeThreshold`. With the default threshold of 0, every non-empty part goes to disk. Empty parts stay in memory.
8. **`Part.write`:**
   - An absolute path is used as provided (Javadoc: "Absolute paths are used as provided").
   - A relative name is normalised under the location and refused (`IOException`) when it leaves it.
   - A spilled part is **moved**: the moved file belongs to the application, and neither `delete()` nor the end of the request removes it. A part already written is copied.
   - `delete()` drops the in-memory content and the container's temporary file only.
9. **Parser leniency:**
   - A part without a `form-data` disposition or without a `name` is read, counted against the limits, and dropped (Tomcat parity).
   - A body with zero parts (`--B--`) yields an empty list.
   - Transport padding after a delimiter is accepted.
   - Quoted-string escapes are `\"` and `\\` only: browsers send backslashes of Windows paths raw (WHATWG).
   - `filename*` (RFC 8187, UTF-8 or ISO-8859-1) wins over `filename` when it decodes.
   - Folded headers, bare CR/LF and control characters in part headers are malformed.
10. **Cleanup:** `HttpServletRequestImpl.releaseParts()` runs in `ChappeServletBridge.runPipeline`'s `finally`. That runs once the request is over, asynchronous processing included, on every exit path. A failed parse deletes its own files at once.
11. **Signature test:**
    - The `sigtest` profile of `foy-tck` combines with `tck-official`. It runs the TCK's own `servlet.tck.signature.ServletSigTest` with the `jakarta.servlet.sig_6.1` file shipped in `servlet-tck-runtime-6.1.0.jar` against the `jakarta.servlet-api` 6.1.0 jar Foy runs on.
    - `jakarta.tck:sigtest-maven-plugin:2.5` is a test-scoped build dependency, as in `cassini-tck`.
    - A failure is recorded, not hidden. It does not block the exit.

## Evidence (Phase 6 exit, 1692/1714)

`foy-tck/tck-baseline.txt` at main 8f5a768: 1714 run, 1692 passing, 7 skipped, 15 bad. Lines 104-105:
- `servlet.tck.api.jakarta_servlet_http.part.PartTests 7 4 0`
- `servlet.tck.api.jakarta_servlet_http.part1.Part1Tests 7 4 0`

The TCK servlets:
- `part.TestServlet` is `@WebServlet("/TestServlet") @MultipartConfig(maxFileSize=200)`.
- `part1.TestServletWrapper` has its own `@MultipartConfig` and wraps the request in a plain `HttpServletRequestWrapper`.

Clients use raw sockets:
- `Connection: close`, `Content-Length`, boundary `AaB03x`;
- every part is `Content-Type: text/plain`;
- files `test.txt` (36 bytes) and `test2.txt` (37 bytes), and a field `xyz=1234567abcdefg` (14 bytes).

| Test (both classes) | Expected | Fails today because | Fixed by |
|---|---|---|---|
| `getPartTest1` | `Content-Type:  text/plain; boundary=AaB03x` → `getPart` throws `ServletException` | `ensurePartsParsed` returns an empty list | Task 7.6 |
| `getPartsTest` | text/plain → `getParts` throws `ServletException` | same | Task 7.6 |
| `getPartTest` | `getParameter("xyz")` is `1234567abcdefg`, then the part's metadata | multipart fields are not parameters | Task 7.7 |
| `getPartsTest1` | same parameter, then 3 parts in order (36/37/14 bytes) | same | Task 7.7 |

These pass today and must stay green: `getHeaderTest`, `getHeadersTest`, `getInputStreamTest`. The TCK servlet calls `getParameter("testname")` before any part method, so after Task 7.7 the multipart parse is triggered by the parameter path, under the servlet's scope.

**Code facts on main 8f5a768** (the plan's anchors):

`HttpServletRequestImpl`:
- `parameters()` (~295-310) reads `chappe.body()` directly with `readAllBytes()`.
- `ensurePartsParsed()` (~885-896) reads `chappe.body()` directly, bypassing `trackedBody`.
- `roleRefServletName()` (~187) resolves the executing servlet.

`MultipartParser`:
- buffers the whole body;
- matches `--boundary` anywhere;
- decodes headers as US-ASCII;
- matches `name=` inside `filename=`.

`PartImpl`:
- is memory only;
- its `write` resolves against the working directory;
- its `delete` is a no-op;
- it keeps one value per header.

Deployment and runtime:
- `WebAppDeployer`:
  - sets `ServletContext.TEMPDIR` (`createTempDir`, ~234);
  - never calls `setMultipartConfig` (`registerStatic` ~303, `materializeDynamic` ~343).
- `DynamicServletRegistration.setMultipartConfig` (~135) does no validation.
- `ChappeServletBridge.runPipeline` (~160-175): its `finally` runs per request after async completion. `endNonBlockingIo`/`endSessionAccess` live there.
- Chappe:
  - refuses a `Content-Length` body above `maxRequestSize` (`HttpConnection` ~533);
  - drains any unread body after the response, with no bound for chunked bodies (`HttpBodyReader.drain`).

## Global Constraints

**Branch and dependencies:**
- Branch `pr/ybl/servlet-completion-phase7` from `main`. **No chappe change** in this phase. Foy builds against the published chappe/vauban `0.4.0-SNAPSHOT`.
- Runtime modules keep zero dependency beyond the Jakarta APIs plus chappe/vauban. Everything needed is in the JDK: `Files.createTempFile`, `PosixFilePermissions`, `CharsetDecoder`, `ReentrantLock`.
- `foy-tck` may add the test-scoped `jakarta.tck:sigtest-maven-plugin:2.5` and `maven-dependency-plugin` (build tooling, named in `foy-tck/README.md` and `reference.adoc`).

**Code rules:**
- Static codegen doctrine: no new `getAnnotation(` / `getDeclaredConstructor(` / `Class.forName(` in foy-core main (`NoProductReflectionTest`). `@MultipartConfig` comes from `WebComponentDescriptor.multipartConfig()`, never from runtime reflection.
- Java Modules: say "Java Modules", never the abbreviation. Every new class lives in an already exported package (`io.vidocq.foy.internal.http`); `module-info.java` does not change.
- Concurrency:
  - virtual threads for any thread Foy starts (this phase starts none);
  - `ReentrantLock`, not `synchronized`, for new locking (`PartStorage`, `PartImpl`).
- English everywhere: code, Javadoc, comments, tests, docs, commits.

**New files:** every new Java file starts with the repository license header, verbatim:

```java
/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
```

The code blocks below omit it; add it on top of each new file.

**Commits and tracking:**
- Conventional Commits, `git commit -S -s`, explicit paths (`git add <paths>`, never `-A`).
- Trailers on every commit:
  - `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
  - `Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi`
- Every reproducible bug found on the way goes to Foy's `BUG.md` (id, date, symptom, minimal repro, cause hypothesis, status).
- Ids are numbered per creation day. The last id on main is BUG-20261011-10, so the next one written on 2026-10-11 is BUG-20261011-11; on a later day D, BUG-D-01 (or after the last entry of day D).
- Any performance figure goes to `BENCH.md` first. The plan makes no performance claim.

**Provenance:** write original code. Do not consult or copy the Tomcat or Commons FileUpload sources under `../provenance-audit/corpus`: they exist for the similarity audit.

**Private Maven repository** (other sessions overwrite Foy SNAPSHOTs in `~/.m2`). In every shell of this phase, before any `./mvnw` or TCK script (Maven 3.9 appends `MAVEN_ARGS` to every invocation, the TCK script's included):

```bash
export FOY_M2="<session scratchpad>/m2"
export MAVEN_ARGS="-Dmaven.repo.local=$FOY_M2 -Dmaven.repo.local.tail=$HOME/.m2/repository"
```

**Gates:**
- **Build, every task.**
  - Run `./mvnw -ntp install -Dmaven.test.failure.ignore=true`, then:
    ```bash
    grep -lE '(failures|errors)="[1-9]' */target/surefire-reports/TEST-*.xml
    ```
  - It must print only `foy-cdi-vauban/target/surefire-reports/TEST-io.vidocq.foy.cdi.vauban.FoyWebExtensionTest.xml`, the known pre-existing failure BUG-20261011-02.
  - In that file, only `requestScopedThroughFoyChappeBoot` may fail:
    ```bash
    grep -B1 -E '<(failure|error)' <that file> | grep -o 'testcase name="[^"]*"'
    ```
  - The flaky `CallbackSerializerTest.holdWaitsForTheRunningCallbackAndDefersTheQueuedOnes` (BUG-20261011-06) may be re-run once on its own.
- **Request handling** (Tasks 7.1, 7.2, 7.3, 7.6, 7.7): also `./mvnw -ntp -Ptck -pl foy-tck test`, which must be green.
- **TCK checks:**
  - Tasks 7.6 and 7.7 run:
    ```bash
    ./run-official-tck-servlet6.1.sh --no-install -Dtest='PartTests,Part1Tests'
    ```
  - Tasks 7.2 and 7.7 also run `--family api`, tallied with `foy-tck/tck-tally.sh` and joined against `foy-tck/tck-baseline.txt`:
    ```bash
    LC_ALL=C join foy-tck/tck-baseline.txt <tally> \
      | awk '$5 != $2 || $6 > $3 || $7 > $4 || $5-$6-$7 < $2-$3-$4 {print "REGRESSION", $0}'
    ```
    It must print nothing except the improved `PartTests`/`Part1Tests` lines (an improvement prints nothing either), and every `tiers=Stats` line must read `reflection=0`.
  - The exit task runs `--all`.

## Review Focus

1. **A hostile or simply huge upload** (an endless chunked body, thousands of tiny parts, a megabyte of part headers, `maxFileSize = -1` with a big file). Memory stays bounded (8 KiB windows, then disk). The request fails with `MultipartLimitException` or `RequestBodyTooLargeException`, never an `OutOfMemoryError`, and never an unbounded file. Tests:
   - Task 7.1: `chunkedBodyAboveTheCapFailsTheRead`;
   - Task 7.5: `maxRequestSizeIsEnforcedWhileStreaming`, `maxFileSizeIsEnforcedWhileStreaming`, `tooManyPartsAreRefused`, `oversizedPartHeadersAreRefused`.
2. **Temporary files left behind or readable by others** (a normal request, an async request, a failed parse). No container file survives the request, and each one is `rw-------` on POSIX with a random name. Tests:
   - Task 7.4: `spilledFilesAreOwnerOnlyAndRandomlyNamed`;
   - Task 7.5: `aFailedParseDeletesItsTemporaryFiles`;
   - Task 7.6: `partsSpillToTheContextTempDirAndAreDeletedAfterTheRequest`, `partsOfAnAsyncRequestLiveUntilItCompletes`.
3. **Path traversal** through `Part.write("../x")` or a client file name `../../etc/passwd`. `write` refuses relative names that leave the location. The container never builds a path from the client's name, and returns it raw. Tests:
   - Task 7.4: `writeRefusesNamesEscapingTheLocation`, `writeTargetsStayInsideTheLocation`;
   - Task 7.5: `submittedFileNameIsReturnedRaw`.
4. **Parser edge cases a real client produces:**
   - the boundary text inside a file but not after a CRLF;
   - a delimiter split across network reads;
   - a partial delimiter followed by the real one;
   - `filename=` before `name=`;
   - semicolons and quotes inside quoted values;
   - UTF-8 file names;
   - a body cut short.

   The parts come out byte-exact, and a truncated body is an error, never a silent short part. Task 7.5 tests:
   - `boundaryTextInsideContentIsNotADelimiter`;
   - `aDelimiterSplitAcrossReadsIsFound`;
   - `aPartialDelimiterBeforeTheRealOneStaysContent`;
   - `nameIsNotMatchedInsideFilename`;
   - `quotedValuesKeepSemicolonsAndEscapedQuotes`;
   - `utf8HeadersAreDecoded`;
   - `aTruncatedBodyIsMalformed`.
5. **Body consumption order** (`getParameter` then `getParts`, `getParts` then `getInputStream`, `getInputStream` then `getParts`, a failed read then a retry), and the **next request on a keep-alive connection** after a partially read multipart body. One reader wins, the others see the documented result, nothing is read twice, and the connection keeps working. Tests:
   - Task 7.2: `aFailedBodyReadPropagatesOnceAndIsNeverRetried`;
   - Task 7.6: `getPartsAfterGetInputStreamThrowsIllegalStateException`, `aFailedParseIsRethrownWithoutReadingTheBodyAgain`, `theConnectionServesTheNextRequestAfterParts`;
   - Task 7.7: `getParameterAndGetPartsShareOneParse`.

---

### Task 7.1: Request limits and the Foy-side cap on bodies without `Content-Length`

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/http/RequestLimits.java`
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/http/BoundedInputStream.java`
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/http/RequestBodyTooLargeException.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/boot/DeployOptions.java` (record, all `with*` methods)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppDeployer.java` (`newContext`, ~251)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/container/VidocqServletContext.java` (fields ~75, accessors)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java` (`getInputStream` ~473, `getReader` ~485, new `limits()`/`bodySource()`)
- Modify: `foy-chappe/src/main/java/io/vidocq/foy/chappe/FoyChappeBoot.java` (Builder fields ~130, setters after `rotateSessionIdOnLogin` ~229, `build()` ~337)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/http/RequestLimitsTest.java`
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/http/BoundedInputStreamTest.java`
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/ChunkedBodyLimitEndToEndTest.java`
- Test: `foy-chappe/src/test/java/io/vidocq/foy/chappe/FoyChappeBootTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `public record RequestLimits(long maxChunkedRequestBodySize, long maxFormBodySize, int maxMultipartParts, int maxMultipartPartHeaderSize)`, with:
    - `RequestLimits.DEFAULTS` = (10 MiB, 2 MiB, 50, 8192);
    - `withMaxChunkedRequestBodySize(long)`, `withMaxFormBodySize(long)`, `withMaxMultipartParts(int)`, `withMaxMultipartPartHeaderSize(int)`;
    - `IllegalArgumentException` below `-1` (sizes), on `0` or below `-1` (parts), below `1` (header size).
  - `public final class BoundedInputStream extends FilterInputStream`: `BoundedInputStream(InputStream in, long limit)`. It throws `RequestBodyTooLargeException` once more than `limit` bytes were read or skipped.
  - `public final class RequestBodyTooLargeException extends IOException`: `long limit()`.
  - `VidocqServletContext`: `void setRequestLimits(RequestLimits)` and `RequestLimits requestLimits()` (default `DEFAULTS`).
  - `DeployOptions`: an 11th component `RequestLimits requestLimits` (`null` gives `DEFAULTS`), and `DeployOptions withRequestLimits(RequestLimits)`.
  - `FoyChappeBoot.Builder`: `maxChunkedRequestBodySize(long)`, `maxFormBodySize(long)`, `maxMultipartParts(int)`, `maxMultipartPartHeaderSize(int)`. Each validates at the call site.
  - `HttpServletRequestImpl`: `private RequestLimits limits()` and `private InputStream bodySource()`. Tasks 7.2 and 7.6 call them.

- [ ] **Step 1: Write the failing unit tests.**

`foy-core/src/test/java/io/vidocq/foy/internal/http/RequestLimitsTest.java`:

```java
package io.vidocq.foy.internal.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequestLimitsTest {

    @Test
    void defaultsAreTheDocumentedOnes() {
        assertEquals(10L * 1024 * 1024, RequestLimits.DEFAULTS.maxChunkedRequestBodySize());
        assertEquals(2L * 1024 * 1024, RequestLimits.DEFAULTS.maxFormBodySize());
        assertEquals(50, RequestLimits.DEFAULTS.maxMultipartParts());
        assertEquals(8 * 1024, RequestLimits.DEFAULTS.maxMultipartPartHeaderSize());
    }

    @Test
    void minusOneLiftsACeilingAndAnythingLowerIsRefused() {
        var unlimited = RequestLimits.DEFAULTS.withMaxChunkedRequestBodySize(-1).withMaxFormBodySize(-1)
                .withMaxMultipartParts(-1);
        assertEquals(-1, unlimited.maxChunkedRequestBodySize());
        assertEquals(-1, unlimited.maxFormBodySize());
        assertEquals(-1, unlimited.maxMultipartParts());
        assertEquals(0, RequestLimits.DEFAULTS.withMaxFormBodySize(0).maxFormBodySize(), "0 accepts only an empty body");
        assertThrows(IllegalArgumentException.class, () -> RequestLimits.DEFAULTS.withMaxChunkedRequestBodySize(-2));
        assertThrows(IllegalArgumentException.class, () -> RequestLimits.DEFAULTS.withMaxFormBodySize(-2));
        assertThrows(IllegalArgumentException.class, () -> RequestLimits.DEFAULTS.withMaxMultipartParts(0));
        assertThrows(IllegalArgumentException.class, () -> RequestLimits.DEFAULTS.withMaxMultipartPartHeaderSize(0));
    }
}
```

`foy-core/src/test/java/io/vidocq/foy/internal/http/BoundedInputStreamTest.java`:

```java
package io.vidocq.foy.internal.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class BoundedInputStreamTest {

    private static InputStream bytes(int n) { return new ByteArrayInputStream(new byte[n]); }

    @Test
    void aBodyUpToTheLimitIsReadWhole() throws IOException {
        assertEquals(10, new BoundedInputStream(bytes(10), 10).readAllBytes().length);
    }

    @Test
    void oneByteMoreFailsTheRead() {
        var e = assertThrows(RequestBodyTooLargeException.class,
                () -> new BoundedInputStream(bytes(11), 10).readAllBytes());
        assertEquals(10, e.limit());
    }

    @Test
    void singleByteReadsAreCounted() throws IOException {
        var in = new BoundedInputStream(bytes(3), 2);
        in.read();
        in.read();
        assertThrows(RequestBodyTooLargeException.class, in::read);
    }

    @Test
    void skippedBytesAreCounted() {
        var in = new BoundedInputStream(bytes(20), 10);
        assertThrows(RequestBodyTooLargeException.class, () -> in.skip(15));
    }

    @Test
    void aZeroLimitAcceptsOnlyAnEmptyBody() throws IOException {
        assertEquals(0, new BoundedInputStream(bytes(0), 0).readAllBytes().length);
        assertThrows(RequestBodyTooLargeException.class, () -> new BoundedInputStream(bytes(1), 0).read());
    }
}
```

- [ ] **Step 2: Write the failing end-to-end test.**

`foy-core/src/test/java/io/vidocq/foy/internal/ChunkedBodyLimitEndToEndTest.java`:

```java
package io.vidocq.foy.internal;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.dispatcher.FilterRegistry;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
import io.vidocq.foy.internal.http.RequestLimits;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Chappe bounds Content-Length bodies only; Foy bounds the others (maxChunkedRequestBodySize). */
class ChunkedBodyLimitEndToEndTest {

    private Server server;
    private int port;

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

    private void start(long cap) {
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                String outcome;
                try {
                    outcome = Integer.toString(req.getInputStream().readAllBytes().length);
                } catch (IOException e) {
                    outcome = e.getClass().getSimpleName();
                }
                resp.getWriter().write(outcome);
            }
        };
        var ctx = new VidocqServletContext("/");
        ctx.setRequestLimits(RequestLimits.DEFAULTS.withMaxChunkedRequestBodySize(cap));
        var bridge = new ChappeServletBridge(
                new ServletDispatcher(List.of(new ServletDispatcher.Mapping(UrlPatternMatcher.of("/body"), s, "B"))),
                new FilterRegistry(List.of()), TestSecurity.permitAll(ctx), null, "/");
        var r = TestServerLauncher.start(bridge);
        server = r.server;
        port = r.port;
    }

    private String post(HttpRequest.BodyPublisher body) throws Exception {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/body"))
                        .timeout(Duration.ofSeconds(5)).POST(body).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void chunkedBodyAboveTheCapFailsTheRead() throws Exception {
        start(1024);
        assertEquals("RequestBodyTooLargeException",
                post(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[4096]))));
    }

    @Test
    void chunkedBodyWithinTheCapIsRead() throws Exception {
        start(1024);
        assertEquals("1000",
                post(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[1000]))));
    }

    @Test
    void aSizedBodyIsLeftToChappesLimit() throws Exception {
        start(1024);
        assertEquals("4096", post(HttpRequest.BodyPublishers.ofByteArray(new byte[4096])));
    }
}
```

(`BodyPublishers.ofInputStream` has an unknown length, so the HTTP/1.1 client sends it chunked.)

- [ ] **Step 3: Write the failing `FoyChappeBoot` tests.** Append them to `FoyChappeBootTest`, after `anInvalidConfidentialPortIsRejectedAtTheCallSite`, and add `import io.vidocq.foy.internal.http.RequestLimits;`:

```java
    @Test
    void requestLimitsAreConfigurableAndReachTheContext() throws Exception {
        try (var mounted = plainApp(null).contextPath("/").build().orElseThrow()) {
            assertEquals(RequestLimits.DEFAULTS, mounted.deployment().servletContext().requestLimits());
        }
        try (var mounted = plainApp(null).contextPath("/").maxChunkedRequestBodySize(1024).maxFormBodySize(512)
                .maxMultipartParts(5).maxMultipartPartHeaderSize(256).build().orElseThrow()) {
            assertEquals(new RequestLimits(1024, 512, 5, 256), mounted.deployment().servletContext().requestLimits());
        }
    }

    @Test
    void invalidRequestLimitsAreRejectedAtTheCallSite() {
        var b = plainApp(null);
        assertThrows(IllegalArgumentException.class, () -> b.maxChunkedRequestBodySize(-2));
        assertThrows(IllegalArgumentException.class, () -> b.maxFormBodySize(-2));
        assertThrows(IllegalArgumentException.class, () -> b.maxMultipartParts(0));
        assertThrows(IllegalArgumentException.class, () -> b.maxMultipartPartHeaderSize(0));
        b.maxChunkedRequestBodySize(-1).maxFormBodySize(-1).maxMultipartParts(-1);
    }
```

- [ ] **Step 4: Run them to see them fail.**
  - Run: `./mvnw -ntp -pl foy-core test -Dtest='RequestLimitsTest,BoundedInputStreamTest,ChunkedBodyLimitEndToEndTest' -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: compilation FAILS (`RequestLimits`, `BoundedInputStream`, `setRequestLimits` do not exist).

- [ ] **Step 5: Create `RequestLimits`.**

```java
package io.vidocq.foy.internal.http;

/**
 * Container ceilings on what a request may make Foy read or store, chosen by the embedder
 * ({@code FoyChappeBoot.Builder}, {@code DeployOptions}). {@code -1} lifts a size ceiling.
 *
 * @param maxChunkedRequestBodySize  bytes Foy reads from a body that declares no Content-Length
 *                                   (HTTP/1.1 chunked, HTTP/2 without content-length) before the read
 *                                   fails with {@link RequestBodyTooLargeException}; a body with a
 *                                   Content-Length is bounded by chappe ({@code ServerConfig.maxRequestSize})
 * @param maxFormBodySize            bytes of an {@code application/x-www-form-urlencoded} body parsed into
 *                                   parameters; beyond, {@code getParameter} throws {@link IllegalStateException}
 * @param maxMultipartParts          parts in one {@code multipart/form-data} body; beyond, the parse fails
 *                                   with {@code MultipartLimitException}
 * @param maxMultipartPartHeaderSize bytes of the header section of one part (CRLFs included); beyond,
 *                                   the parse fails with {@code MultipartLimitException}
 */
public record RequestLimits(long maxChunkedRequestBodySize, long maxFormBodySize, int maxMultipartParts,
                            int maxMultipartPartHeaderSize) {

    /** 10 MiB (chappe's Content-Length default), 2 MiB, 50 parts, 8 KiB of headers per part. */
    public static final RequestLimits DEFAULTS =
            new RequestLimits(10L * 1024 * 1024, 2L * 1024 * 1024, 50, 8 * 1024);

    public RequestLimits {
        checkSize("maxChunkedRequestBodySize", maxChunkedRequestBodySize);
        checkSize("maxFormBodySize", maxFormBodySize);
        if (maxMultipartParts != -1 && maxMultipartParts < 1) {
            throw new IllegalArgumentException("maxMultipartParts must be -1 or at least 1: " + maxMultipartParts);
        }
        if (maxMultipartPartHeaderSize < 1) {
            throw new IllegalArgumentException("maxMultipartPartHeaderSize must be at least 1: "
                    + maxMultipartPartHeaderSize);
        }
    }

    private static void checkSize(String name, long bytes) {
        if (bytes < -1) throw new IllegalArgumentException(name + " must be -1 or a byte count: " + bytes);
    }

    public RequestLimits withMaxChunkedRequestBodySize(long bytes) {
        return new RequestLimits(bytes, maxFormBodySize, maxMultipartParts, maxMultipartPartHeaderSize);
    }

    public RequestLimits withMaxFormBodySize(long bytes) {
        return new RequestLimits(maxChunkedRequestBodySize, bytes, maxMultipartParts, maxMultipartPartHeaderSize);
    }

    public RequestLimits withMaxMultipartParts(int parts) {
        return new RequestLimits(maxChunkedRequestBodySize, maxFormBodySize, parts, maxMultipartPartHeaderSize);
    }

    public RequestLimits withMaxMultipartPartHeaderSize(int bytes) {
        return new RequestLimits(maxChunkedRequestBodySize, maxFormBodySize, maxMultipartParts, bytes);
    }
}
```

`MultipartLimitException` is created in Task 7.4, so the Javadoc names it with `{@code}`; Task 7.4 Step 4 turns both into `{@link MultipartLimitException}`.

- [ ] **Step 6: Create `RequestBodyTooLargeException` and `BoundedInputStream`.**

```java
package io.vidocq.foy.internal.http;

import java.io.IOException;

/** A request body read beyond the ceiling Foy enforces ({@link RequestLimits#maxChunkedRequestBodySize()}). */
public final class RequestBodyTooLargeException extends IOException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final long limit;

    public RequestBodyTooLargeException(long limit) {
        super("the request body exceeds " + limit + " bytes");
        this.limit = limit;
    }

    /** The ceiling, in bytes. */
    public long limit() { return limit; }
}
```

```java
package io.vidocq.foy.internal.http;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Reads at most {@code limit} bytes of a request body: reading or skipping one byte more throws
 * {@link RequestBodyTooLargeException}. Not thread-safe (one reader at a time, as any body stream).
 */
public final class BoundedInputStream extends FilterInputStream {

    private final long limit;
    private long count;

    public BoundedInputStream(InputStream in, long limit) {
        super(Objects.requireNonNull(in, "in"));
        if (limit < 0) throw new IllegalArgumentException("limit must not be negative: " + limit);
        this.limit = limit;
    }

    @Override
    public int read() throws IOException {
        int b = in.read();
        if (b >= 0) counted(1);
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = in.read(b, off, len);
        if (n > 0) counted(n);
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = in.skip(n);
        if (skipped > 0) counted(skipped);
        return skipped;
    }

    @Override public boolean markSupported() { return false; }
    @Override public void mark(int readLimit) { /* not supported */ }
    @Override public void reset() throws IOException { throw new IOException("mark/reset not supported"); }

    private void counted(long n) throws RequestBodyTooLargeException {
        count += n;
        if (count > limit) throw new RequestBodyTooLargeException(limit);
    }
}
```

- [ ] **Step 7: Wire the limits into the context and `DeployOptions`.**

In `VidocqServletContext`, next to `securitySettings` (~75), add the field and two accessors:

```java
    private volatile io.vidocq.foy.internal.http.RequestLimits requestLimits =
            io.vidocq.foy.internal.http.RequestLimits.DEFAULTS;

    /** The container ceilings of this context's requests (body, form, multipart). */
    public void setRequestLimits(io.vidocq.foy.internal.http.RequestLimits limits) {
        this.requestLimits = java.util.Objects.requireNonNull(limits, "limits");
    }

    public io.vidocq.foy.internal.http.RequestLimits requestLimits() { return requestLimits; }
```

In `DeployOptions`:
1. Add `import io.vidocq.foy.internal.http.RequestLimits;`.
2. Add the Javadoc line ` * @param requestLimits        container ceilings on request bodies; {@code null} gives {@link RequestLimits#DEFAULTS}` after the `tempDirRoot` one.
3. Replace everything from `public record DeployOptions(` to the closing brace of the 8-argument constructor with:

```java
public record DeployOptions(SecuritySettings security,
                            VidocqServletContext.ResourceProvider resourceProvider,
                            String servletContextName,
                            Set<String> reservedServletNames,
                            Set<String> reservedFilterNames,
                            Set<String> reservedUrlPatterns,
                            ComponentFactory componentFactory,
                            HandlesTypesResolver handlesTypes,
                            String virtualServerName,
                            Path tempDirRoot,
                            RequestLimits requestLimits) {

    public DeployOptions {
        if (security == null) security = SecuritySettings.DEFAULTS;
        if (requestLimits == null) requestLimits = RequestLimits.DEFAULTS;
        reservedServletNames = Set.copyOf(reservedServletNames);
        reservedFilterNames = Set.copyOf(reservedFilterNames);
        reservedUrlPatterns = Set.copyOf(reservedUrlPatterns);
        Objects.requireNonNull(componentFactory, "componentFactory");
        Objects.requireNonNull(handlesTypes, "handlesTypes");
    }

    /** Without virtual server name, temp-dir root and request limits (their defaults apply). */
    public DeployOptions(SecuritySettings security,
                         VidocqServletContext.ResourceProvider resourceProvider,
                         String servletContextName,
                         Set<String> reservedServletNames,
                         Set<String> reservedFilterNames,
                         Set<String> reservedUrlPatterns,
                         ComponentFactory componentFactory,
                         HandlesTypesResolver handlesTypes) {
        this(security, resourceProvider, servletContextName, reservedServletNames, reservedFilterNames,
                reservedUrlPatterns, componentFactory, handlesTypes, null, null, null);
    }
```

4. Keep the three `defaults(...)` methods unchanged.
5. Replace everything from `public DeployOptions withComponentFactory` to the end of the file with:

```java
    public DeployOptions withComponentFactory(ComponentFactory f) {
        return new DeployOptions(security, resourceProvider, servletContextName, reservedServletNames,
                reservedFilterNames, reservedUrlPatterns, f, handlesTypes, virtualServerName, tempDirRoot, requestLimits);
    }

    public DeployOptions withHandlesTypes(HandlesTypesResolver r) {
        return new DeployOptions(security, resourceProvider, servletContextName, reservedServletNames,
                reservedFilterNames, reservedUrlPatterns, componentFactory, r, virtualServerName, tempDirRoot,
                requestLimits);
    }

    public DeployOptions withSecurity(SecuritySettings s) {
        return new DeployOptions(s, resourceProvider, servletContextName, reservedServletNames,
                reservedFilterNames, reservedUrlPatterns, componentFactory, handlesTypes, virtualServerName,
                tempDirRoot, requestLimits);
    }

    public DeployOptions withIdentityStore(IdentityStore store) {
        return withSecurity(security.withIdentityStore(store));
    }

    public DeployOptions withResourceProvider(VidocqServletContext.ResourceProvider p) {
        return new DeployOptions(security, p, servletContextName, reservedServletNames, reservedFilterNames,
                reservedUrlPatterns, componentFactory, handlesTypes, virtualServerName, tempDirRoot, requestLimits);
    }

    public DeployOptions withServletContextName(String n) {
        return new DeployOptions(security, resourceProvider, n, reservedServletNames, reservedFilterNames,
                reservedUrlPatterns, componentFactory, handlesTypes, virtualServerName, tempDirRoot, requestLimits);
    }

    public DeployOptions withReserved(Set<String> servletNames, Set<String> filterNames, Set<String> urlPatterns) {
        return new DeployOptions(security, resourceProvider, servletContextName, servletNames, filterNames,
                urlPatterns, componentFactory, handlesTypes, virtualServerName, tempDirRoot, requestLimits);
    }

    public DeployOptions withVirtualServerName(String name) {
        return new DeployOptions(security, resourceProvider, servletContextName, reservedServletNames,
                reservedFilterNames, reservedUrlPatterns, componentFactory, handlesTypes, name, tempDirRoot,
                requestLimits);
    }

    public DeployOptions withTempDirRoot(Path root) {
        return new DeployOptions(security, resourceProvider, servletContextName, reservedServletNames,
                reservedFilterNames, reservedUrlPatterns, componentFactory, handlesTypes, virtualServerName, root,
                requestLimits);
    }

    public DeployOptions withRequestLimits(RequestLimits limits) {
        return new DeployOptions(security, resourceProvider, servletContextName, reservedServletNames,
                reservedFilterNames, reservedUrlPatterns, componentFactory, handlesTypes, virtualServerName,
                tempDirRoot, limits);
    }
}
```

In `WebAppDeployer.newContext`, after `ctx.setSecuritySettings(options.security());`, add:

```java
        ctx.setRequestLimits(options.requestLimits());
```

- [ ] **Step 8: Bound the bodies Foy hands out.** In `HttpServletRequestImpl`:
1. Add `import io.vidocq.foy.internal.http.BoundedInputStream;` and `import io.vidocq.foy.internal.http.RequestLimits;`.
2. Add the two methods below just above `getInputStream()`.
3. In `getInputStream()` and in `getReader()`, replace `: chappe.body().asInputStream();` with `: bodySource();`.

```java
    /** The ceilings the embedder configured for this context. */
    private RequestLimits limits() {
        return servletContext instanceof VidocqServletContext v ? v.requestLimits() : RequestLimits.DEFAULTS;
    }

    /**
     * Chappe's body stream. A body that declares no Content-Length (HTTP/1.1 chunked, HTTP/2 without
     * content-length) is bounded by {@link RequestLimits#maxChunkedRequestBodySize()}: chappe bounds
     * only Content-Length bodies ({@code ServerConfig.maxRequestSize}).
     */
    private java.io.InputStream bodySource() {
        java.io.InputStream raw = chappe.body().asInputStream();
        long cap = limits().maxChunkedRequestBodySize();
        if (cap < 0 || chappe.headers().firstOrNull("Content-Length") != null) return raw;
        return new BoundedInputStream(raw, cap);
    }
```

- [ ] **Step 9: Add the `FoyChappeBoot` settings.**
1. Add `import io.vidocq.foy.internal.http.RequestLimits;`.
2. Add the field `private RequestLimits requestLimits = RequestLimits.DEFAULTS;` after `rotateSessionIdOnLogin`.
3. After the `rotateSessionIdOnLogin(boolean)` setter, add the four setters below.

```java
        /**
         * Bytes Foy reads from a request body that declares no Content-Length (HTTP/1.1 chunked,
         * HTTP/2 without content-length) before the read fails with an {@code IOException}: 10 MiB by
         * default, {@code -1} for no ceiling. A body with a Content-Length is bounded by chappe
         * ({@code Server.Builder.maxRequestSize}, 10 MiB by default), never by this setting.
         *
         * @throws IllegalArgumentException when {@code bytes} is below {@code -1}
         */
        public Builder maxChunkedRequestBodySize(long bytes) {
            this.requestLimits = requestLimits.withMaxChunkedRequestBodySize(bytes);
            return this;
        }

        /**
         * Bytes of an {@code application/x-www-form-urlencoded} body parsed into request parameters:
         * 2 MiB by default, {@code -1} for no ceiling. A larger form makes {@code getParameter*}
         * throw {@code IllegalStateException} once; the query-string parameters stay available.
         *
         * @throws IllegalArgumentException when {@code bytes} is below {@code -1}
         */
        public Builder maxFormBodySize(long bytes) {
            this.requestLimits = requestLimits.withMaxFormBodySize(bytes);
            return this;
        }

        /**
         * Parts in one {@code multipart/form-data} body: 50 by default (CVE-2023-24998), {@code -1}
         * for no ceiling. Beyond, {@code getParts()} throws {@code IllegalStateException}.
         *
         * @throws IllegalArgumentException when {@code parts} is {@code 0} or below {@code -1}
         */
        public Builder maxMultipartParts(int parts) {
            this.requestLimits = requestLimits.withMaxMultipartParts(parts);
            return this;
        }

        /**
         * Bytes of the header section of one multipart part: 8 KiB by default. Beyond,
         * {@code getParts()} throws {@code IllegalStateException}.
         *
         * @throws IllegalArgumentException when {@code bytes} is below {@code 1}
         */
        public Builder maxMultipartPartHeaderSize(int bytes) {
            this.requestLimits = requestLimits.withMaxMultipartPartHeaderSize(bytes);
            return this;
        }
```

In `build()`, extend the `DeployOptions` chain: `.withVirtualServerName(virtualServerName).withSecurity(security).withRequestLimits(requestLimits)`.

- [ ] **Step 10: Run the tests to see them pass.**
  - Run: `./mvnw -ntp -pl foy-core,foy-chappe -am test -Dtest='RequestLimitsTest,BoundedInputStreamTest,ChunkedBodyLimitEndToEndTest,FoyChappeBootTest' -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: PASS.

- [ ] **Step 11: Run the gates.** The build gate, then `./mvnw -ntp -Ptck -pl foy-tck test`.

- [ ] **Step 12: Commit.**

```bash
git add foy-core/src/main/java/io/vidocq/foy/internal/http/RequestLimits.java \
  foy-core/src/main/java/io/vidocq/foy/internal/http/BoundedInputStream.java \
  foy-core/src/main/java/io/vidocq/foy/internal/http/RequestBodyTooLargeException.java \
  foy-core/src/main/java/io/vidocq/foy/internal/boot/DeployOptions.java \
  foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppDeployer.java \
  foy-core/src/main/java/io/vidocq/foy/internal/container/VidocqServletContext.java \
  foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java \
  foy-chappe/src/main/java/io/vidocq/foy/chappe/FoyChappeBoot.java \
  foy-core/src/test/java/io/vidocq/foy/internal/http/RequestLimitsTest.java \
  foy-core/src/test/java/io/vidocq/foy/internal/http/BoundedInputStreamTest.java \
  foy-core/src/test/java/io/vidocq/foy/internal/ChunkedBodyLimitEndToEndTest.java \
  foy-chappe/src/test/java/io/vidocq/foy/chappe/FoyChappeBootTest.java
git commit -S -s -m "feat(core): bound request bodies without Content-Length and add request limits" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" \
  -m "Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi"
```

---

### Task 7.2: Form body leftovers — cap, encoding lock, consistent state after a failed read

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java`:
  - parameters block ~285-330;
  - `getInputStream`/`getReader` ~473-504.
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/bridge/RequestBodyStateTest.java` (create)

**Interfaces:**
- Consumes (Task 7.1): `RequestLimits.maxFormBodySize()`, `limits()`, `bodySource()`, `VidocqServletContext.setRequestLimits`.
- Produces, in `HttpServletRequestImpl`:
  - `private boolean bodyConsumedByContainer`, which replaces `bodyConsumedByParameters`;
  - `private ServletInputStreamImpl containerBody()`: marks the body as the container's, locks the encoding, and returns a tracked stream over `bodySource()`;
  - `private String readFormBody()`.

  Task 7.6 reuses `containerBody()`.
- Produces (test): the package-private `RequestBodyStateTest.FakeRequest(HttpMethod method, String query, Headers headers, Body body) implements Request`. Task 7.7 reuses it.

- [ ] **Step 1: Write the failing tests.**

`foy-core/src/test/java/io/vidocq/foy/internal/bridge/RequestBodyStateTest.java`:

```java
package io.vidocq.foy.internal.bridge;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.http.RequestLimits;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Map;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;

/** Servlet 6.1 sections 3.1.1 and 3.12 on the form body: cap, encoding lock, one read only. */
class RequestBodyStateTest {

    record FakeRequest(HttpMethod method, String query, Headers headers, Body body) implements Request {
        @Override public URI uri() { return URI.create("http://localhost/f"); }
        @Override public String path() { return "/f"; }
        @Override public HttpVersion version() { return HttpVersion.HTTP_1_1; }
        @Override public Map<String, String> pathParams() { return Map.of(); }
        @Override public Map<String, String> queryParams() { return Map.of(); }
    }

    private final VidocqServletContext ctx = new VidocqServletContext("/");

    private HttpServletRequestImpl form(String query, byte[] body, boolean sized) {
        Headers.Builder headers = Headers.builder().add("Host", "localhost")
                .add("Content-Type", "application/x-www-form-urlencoded");
        headers = sized ? headers.add("Content-Length", Integer.toString(body.length))
                : headers.add("Transfer-Encoding", "chunked");
        return new HttpServletRequestImpl(new FakeRequest(HttpMethod.POST, query, headers.build(),
                Body.of(new ByteArrayInputStream(body))), ctx, "/", "/f", null);
    }

    @Test
    void aFormBodyAboveTheCapIsRefusedBeforeItIsRead() throws Exception {
        ctx.setRequestLimits(RequestLimits.DEFAULTS.withMaxFormBodySize(8));
        var req = form("q=1", "a=0123456789".getBytes(US_ASCII), true);
        var e = assertThrows(IllegalStateException.class, () -> req.getParameter("a"));
        assertTrue(e.getMessage().contains("maxFormBodySize"), e.getMessage());
        assertEquals("1", req.getParameter("q"), "the query string is still there");
        assertNull(req.getParameter("a"), "no retry: the refused body stays refused");
        assertEquals(-1, req.getInputStream().read(), "the body belongs to the container");
    }

    @Test
    void aChunkedFormBodyAboveTheCapIsRefused() {
        ctx.setRequestLimits(RequestLimits.DEFAULTS.withMaxFormBodySize(8));
        var req = form(null, "a=0123456789".getBytes(US_ASCII), false);
        assertThrows(IllegalStateException.class, () -> req.getParameter("a"));
        assertNull(req.getParameter("a"));
    }

    @Test
    void aFormBodyAtTheCapIsAccepted() {
        ctx.setRequestLimits(RequestLimits.DEFAULTS.withMaxFormBodySize(12));
        assertEquals("0123456789", form(null, "a=0123456789".getBytes(US_ASCII), true).getParameter("a"));
    }

    @Test
    void parsingTheFormBodyLocksTheEncoding() throws Exception {
        var req = form(null, "a=%C3%A9".getBytes(US_ASCII), true);
        assertEquals("\u00c3\u00a9", req.getParameter("a"), "ISO-8859-1 by default (section 3.12)");
        req.setCharacterEncoding("UTF-8");
        assertNull(req.getCharacterEncoding(), "ignored once the body was parsed");
        assertEquals("\u00c3\u00a9", req.getParameter("a"));
    }

    @Test
    void anEncodingSetBeforeParsingApplies() throws Exception {
        var req = form(null, "a=%C3%A9".getBytes(US_ASCII), true);
        req.setCharacterEncoding("UTF-8");
        assertEquals("\u00e9", req.getParameter("a"));
    }

    @Test
    void queryOnlyParsingLeavesTheEncodingOpen() throws Exception {
        var req = new HttpServletRequestImpl(new FakeRequest(HttpMethod.GET, "a=1",
                Headers.of("Host", "localhost"), Body.empty()), ctx, "/", "/f", null);
        assertEquals("1", req.getParameter("a"));
        req.setCharacterEncoding("UTF-8");
        assertEquals("UTF-8", req.getCharacterEncoding());
    }

    @Test
    void aFailedBodyReadPropagatesOnceAndIsNeverRetried() throws Exception {
        InputStream failing = new InputStream() {
            private int served;
            @Override public int read() throws IOException {
                if (served < 3) { served++; return 'a'; }
                throw new IOException("connection reset");
            }
        };
        var headers = Headers.builder().add("Host", "localhost")
                .add("Content-Type", "application/x-www-form-urlencoded").add("Content-Length", "100").build();
        var req = new HttpServletRequestImpl(new FakeRequest(HttpMethod.POST, "q=1", headers, Body.of(failing)),
                ctx, "/", "/f", null);
        assertThrows(UncheckedIOException.class, () -> req.getParameter("a"));
        assertEquals("1", req.getParameter("q"));
        assertNull(req.getParameter("a"));
        assertEquals(-1, req.getInputStream().read(), "the half-read body is not offered again");
    }
}
```

- [ ] **Step 2: Run them to see them fail.**
  - Run: `./mvnw -ntp -pl foy-core test -Dtest=RequestBodyStateTest`
  - Expected: FAIL in four tests:
    - the two cap tests, because no `IllegalStateException` is thrown;
    - `parsingTheFormBodyLocksTheEncoding`, because the encoding becomes `UTF-8`;
    - `aFailedBodyReadPropagatesOnceAndIsNeverRetried`, because the second `getParameter("q")` rethrows.

- [ ] **Step 3: Rewrite the parameter block.** In `HttpServletRequestImpl`:
1. Replace the two fields `parsedParams` / `bodyConsumedByParameters` and the method `parameters()` with the code below.
2. Rename `bodyConsumedByParameters` to `bodyConsumedByContainer` in `getInputStream()` and `getReader()`.
3. Add `containerBody()` and `readFormBody()` after `parameters()`.

```java
    private Map<String, List<String>> parsedParams;
    /**
     * True once the container consumed the body itself (form parameters, and multipart parts):
     * getInputStream()/getReader() then return an empty stream (section 3.1.1).
     */
    private boolean bodyConsumedByContainer;

    /**
     * The request parameters: the query string first, then the body (section 3.1.1). The map is
     * settled before the body is read, so a refused or failed body read propagates once and leaves
     * the query-string parameters; the body is never read a second time.
     */
    private Map<String, List<String>> parameters() {
        if (parsedParams != null) return parsedParams;
        var out = new java.util.LinkedHashMap<String, List<String>>();
        decodeInto(out, chappe.query(), StandardCharsets.UTF_8);
        parsedParams = out;
        if (inputStream != null || reader != null || bodyConsumedByContainer) return out;
        if (isFormPost()) decodeInto(out, readFormBody(), formBodyCharset());
        return out;
    }

    /**
     * Hands the body to the container (form parameters, parts) through the tracked stream, so trailer
     * readiness and the end-of-request hand-back see the read. From now on the application's
     * getInputStream()/getReader() get an empty stream (section 3.1.1) and the request encoding no
     * longer changes (section 3.12).
     */
    private ServletInputStreamImpl containerBody() {
        bodyConsumedByContainer = true;
        encodingLocked = true;
        trackedBody = new ServletInputStreamImpl(bodySource(), nonBlockingHost);
        return trackedBody;
    }

    /**
     * The urlencoded body as ISO-8859-1 text (the percent-decoding applies the request charset),
     * bounded by {@link RequestLimits#maxFormBodySize()}: a declared Content-Length above it is refused
     * without reading, a chunked body once it passes it.
     *
     * @throws IllegalStateException when the body exceeds maxFormBodySize
     * @throws java.io.UncheckedIOException when the body cannot be read
     */
    private String readFormBody() {
        long cap = limits().maxFormBodySize();
        long declared = getContentLengthLong();
        if (cap >= 0 && declared > cap) {
            bodyConsumedByContainer = true;
            throw new IllegalStateException("the form body (" + declared + " bytes) exceeds maxFormBodySize ("
                    + cap + " bytes)");
        }
        byte[] raw;
        try {
            java.io.InputStream in = containerBody();
            raw = cap < 0 ? in.readAllBytes() : in.readNBytes((int) Math.min(cap + 1, Integer.MAX_VALUE - 8L));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("cannot read the form body", e);
        }
        if (cap >= 0 && raw.length > cap) {
            throw new IllegalStateException("the form body exceeds maxFormBodySize (" + cap + " bytes)");
        }
        return new String(raw, StandardCharsets.ISO_8859_1);
    }
```

After the rename, `getInputStream()` reads:

```java
    @Override public ServletInputStream getInputStream() throws IOException {
        if (reader != null) throw new IllegalStateException("getReader() already called");
        if (inputStream == null) {
            java.io.InputStream source = bodyConsumedByContainer
                    ? java.io.InputStream.nullInputStream()
                    : bodySource();
            trackedBody = new ServletInputStreamImpl(source, nonBlockingHost);
            inputStream = trackedBody;
            encodingLocked = true;
        }
        return inputStream;
    }
```

`getReader()` gets the same `bodyConsumedByContainer ? nullInputStream() : bodySource()` source.

- [ ] **Step 4: Run the tests to see them pass.**
  - Run: `./mvnw -ntp -pl foy-core test -Dtest='RequestBodyStateTest,*Parameter*,*Form*' -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: PASS.

- [ ] **Step 5: Run the gates.**
  1. The build gate.
  2. `./mvnw -ntp -Ptck -pl foy-tck test`.
  3. `./run-official-tck-servlet6.1.sh --no-install --family api > /tmp/foy-tck-p7-2-api.log 2>&1`, tallied and joined as in Global Constraints. No `REGRESSION` line, `reflection=0`.

- [ ] **Step 6: Commit.**

```bash
git add foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java \
  foy-core/src/test/java/io/vidocq/foy/internal/bridge/RequestBodyStateTest.java
git commit -S -s -m "fix(core): cap the form body, lock the encoding once parsed, never re-read a failed body" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" \
  -m "Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi"
```

---

### Task 7.3: Multipart configuration from model, annotation and registration to the context

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/container/DynamicServletRegistration.java` (`setMultipartConfig` ~135)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/container/VidocqServletContext.java` (`markInitialized` ~244, new `multipartConfig(String)`)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppDeployer.java` (`registerStatic` ~303, `materializeDynamic` ~357-372)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/boot/MultipartConfigRegistrationTest.java` (create)

**Interfaces:**
- Consumes: `WebAppModel.ServletDecl.multipartConfig()`, `WebComponentDescriptor.multipartConfig()`, `ComponentFactory.descriptor(Class)`.
- Produces:
  - `VidocqServletContext.multipartConfig(String servletName) -> MultipartConfigElement`. It returns `null` for none or a `null` name, and is frozen at `markInitialized()`. Task 7.6 calls it.
  - `DynamicServletRegistration.setMultipartConfig` throws `IllegalArgumentException` on `null`, and `IllegalStateException` once the owning context is initialised.

- [ ] **Step 1: Write the failing tests.**

`foy-core/src/test/java/io/vidocq/foy/internal/boot/MultipartConfigRegistrationTest.java`:

```java
package io.vidocq.foy.internal.boot;

import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.container.VidocqServletContext;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Section 3.2 / 8.1.5: the effective multipart-config of each servlet reaches the context. */
class MultipartConfigRegistrationTest {

    private Deployment deployment;

    @AfterEach
    void tearDown() { if (deployment != null) deployment.close(); }

    public static class Plain extends HttpServlet {}

    @MultipartConfig(location = "annotated", maxFileSize = 10, maxRequestSize = 100, fileSizeThreshold = 1)
    public static class Annotated extends HttpServlet {}

    private static ServletDecl decl(String name, MultipartConfigElement config) {
        return new ServletDecl(name, Plain.class, Plain::new, List.of("/" + name), Map.of(), Integer.MIN_VALUE,
                false, null, config, true);
    }

    private VidocqServletContext deploy(WebAppModel model) {
        deployment = WebAppDeployer.deploy(model, DeployOptions.defaults(getClass().getClassLoader()));
        return deployment.servletContext();
    }

    @Test
    void aDeclaredConfigurationReachesTheContext() {
        var ctx = deploy(WebAppModel.builder("/")
                .servlet(decl("up", new MultipartConfigElement("d", 1, 2, 3)))
                .servlet(decl("plain", null)).build());
        var config = ctx.multipartConfig("up");
        assertEquals("d", config.getLocation());
        assertEquals(1, config.getMaxFileSize());
        assertEquals(2, config.getMaxRequestSize());
        assertEquals(3, config.getFileSizeThreshold());
        assertNull(ctx.multipartConfig("plain"));
        assertNull(ctx.multipartConfig(null));
    }

    @Test
    void aDynamicServletGetsItsAnnotationUnlessSetMultipartConfigOverridesIt() {
        ServletContainerInitializer sci = (classes, c) -> {
            c.addServlet("annotated", Annotated.class).addMapping("/a");
            var overridden = c.addServlet("overridden", Annotated.class);
            overridden.addMapping("/o");
            overridden.setMultipartConfig(new MultipartConfigElement("set", 5, 50, 0));
        };
        var ctx = deploy(WebAppModel.builder("/").initializer(sci).build());
        assertEquals("annotated", ctx.multipartConfig("annotated").getLocation());
        assertEquals(10, ctx.multipartConfig("annotated").getMaxFileSize());
        assertEquals("set", ctx.multipartConfig("overridden").getLocation());
        assertEquals(5, ctx.multipartConfig("overridden").getMaxFileSize());
    }

    @Test
    void setMultipartConfigRefusesNullAndAnInitializedContext() {
        var ctx = deploy(WebAppModel.builder("/").servlet(decl("up", null)).build());
        var registration = (ServletRegistration.Dynamic) ctx.getServletRegistration("up");
        assertThrows(IllegalStateException.class, () -> registration.setMultipartConfig(new MultipartConfigElement("")));
        var fresh = new VidocqServletContext("/").addServlet("x", new Plain());
        assertThrows(IllegalArgumentException.class, () -> fresh.setMultipartConfig(null));
    }
}
```

- [ ] **Step 2: Run them to see them fail.**
  - Run: `./mvnw -ntp -pl foy-core test -Dtest=MultipartConfigRegistrationTest`
  - Expected: compilation FAILS (`multipartConfig(String)` does not exist).

- [ ] **Step 3: Validate `setMultipartConfig`.** In `DynamicServletRegistration`, replace the method with:

```java
    /**
     * {@code ServletRegistration.Dynamic} Javadoc: overrides the servlet's {@code @MultipartConfig};
     * each call overrides the previous one.
     *
     * @throws IllegalArgumentException when {@code multipartConfig} is null
     * @throws IllegalStateException once the context is initialized
     */
    @Override public void setMultipartConfig(MultipartConfigElement multipartConfig) {
        if (multipartConfig == null) throw new IllegalArgumentException("multipartConfig is null");
        if (owner != null && owner.isInitializedInternal()) {
            throw new IllegalStateException("the ServletContext is already initialized");
        }
        this.multipart = multipartConfig;
    }
```

- [ ] **Step 4: Freeze the configurations in the context.** In `VidocqServletContext`:
1. Add the field `private volatile Map<String, jakarta.servlet.MultipartConfigElement> multipartConfigs;` next to `initialized`.
2. Replace `markInitialized()` with the version below.
3. Add `multipartConfig(String)` after it.

```java
    /** End-of-initialization marker (Servlet 6.1 §4.4); after this call,
     *  dynamic configuration methods must throw {@link IllegalStateException}. The multipart
     *  configuration of every servlet is frozen here for the request threads. */
    public void markInitialized() {
        this.initialized = true;
        var frozen = new HashMap<String, jakarta.servlet.MultipartConfigElement>();
        staticServlets.forEach((name, r) -> { if (r.getMultipartConfig() != null) frozen.put(name, r.getMultipartConfig()); });
        dynamicServlets.forEach((name, r) -> { if (r.getMultipartConfig() != null) frozen.put(name, r.getMultipartConfig()); });
        this.multipartConfigs = Map.copyOf(frozen);
    }

    /**
     * The effective multipart configuration of {@code servletName} (section 3.2): the registration's
     * {@code setMultipartConfig}, else the descriptor's {@code <multipart-config>}, else the class's
     * {@code @MultipartConfig}; {@code null} for none or a {@code null} name. Read live from the
     * registrations until {@link #markInitialized()}, frozen afterwards.
     */
    public jakarta.servlet.MultipartConfigElement multipartConfig(String servletName) {
        if (servletName == null) return null;
        var frozen = multipartConfigs;
        if (frozen != null) return frozen.get(servletName);
        DynamicServletRegistration r = dynamicServlets.get(servletName);
        if (r == null) r = staticServlets.get(servletName);
        return r == null ? null : r.getMultipartConfig();
    }
```

- [ ] **Step 5: Carry the configuration in the deployer.**

In `WebAppDeployer.registerStatic`, replace the servlet loop with:

```java
        var multipart = new java.util.HashMap<String, jakarta.servlet.MultipartConfigElement>();
        for (ServletDecl d : model.servlets()) if (d.multipartConfig() != null) multipart.put(d.name(), d.multipartConfig());
        for (ServletUnit s : servlets) {
            var reg = ctx.registerStaticServlet(s.name(), s.type(), s.patterns(), s.initParams(),
                    s.asyncSupported());
            String runAs = model.runAsRoles().get(s.name());
            if (runAs != null) reg.setRunAsRole(runAs);
            // The descriptor's <multipart-config> already won over @MultipartConfig in the model (section 8.2.3).
            var config = multipart.get(s.name());
            if (config != null) reg.setMultipartConfig(config);
        }
```

In `materializeDynamic`, replace the block from `// setServletSecurity wins; otherwise the class's @ServletSecurity applies (§13.4.1).` down to the end of the `if (security == null && !jsp) { ... }` statement with:

```java
            // setServletSecurity wins; otherwise the class's @ServletSecurity applies (§13.4.1).
            // setMultipartConfig wins; otherwise the class's @MultipartConfig applies (section 3.2).
            ServletSecurityElement security = reg.getServletSecurity();
            if (!jsp && (security == null || reg.getMultipartConfig() == null)) {
                io.vidocq.foy.spi.gen.WebComponentDescriptor descriptor;
                try {
                    descriptor = factory.descriptor(instance.getClass());
                } catch (IllegalArgumentException misuse) {
                    // addServlet declares no checked exception: the misuse is reported here and
                    // only this component is left out, like an instantiation failure.
                    LOG.log(System.Logger.Level.WARNING, "cannot register dynamic servlet " + name,
                            new ServletException(misuse.getMessage(), misuse));
                    continue;
                }
                if (security == null) security = descriptor.servletSecurity();
                if (reg.getMultipartConfig() == null && descriptor.multipartConfig() != null) {
                    reg.setMultipartConfig(descriptor.multipartConfig());
                }
            }
```

- [ ] **Step 6: Run the tests to see them pass.**
  - Run: `./mvnw -ntp -pl foy-core test -Dtest='MultipartConfigRegistrationTest,WebAppDeployerEndToEndTest,ServletSecurityRegistrationEndToEndTest'`
  - Expected: PASS.

- [ ] **Step 7: Run the gates.** The build gate, then `./mvnw -ntp -Ptck -pl foy-tck test`.

- [ ] **Step 8: Commit.**

```bash
git add foy-core/src/main/java/io/vidocq/foy/internal/container/DynamicServletRegistration.java \
  foy-core/src/main/java/io/vidocq/foy/internal/container/VidocqServletContext.java \
  foy-core/src/main/java/io/vidocq/foy/internal/boot/WebAppDeployer.java \
  foy-core/src/test/java/io/vidocq/foy/internal/boot/MultipartConfigRegistrationTest.java
git commit -S -s -m "feat(core): carry the effective multipart-config of each servlet to the context" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" \
  -m "Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi"
```

---

### Task 7.4: Part storage — threshold spill, owner-only temporary files, `write`/`delete`

**Files:**
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/http/PartStorage.java`
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/http/PartSink.java`
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/http/MultipartLimitException.java`
- Create: `foy-core/src/main/java/io/vidocq/foy/internal/http/MalformedMultipartException.java`
- Modify (rewrite): `foy-core/src/main/java/io/vidocq/foy/internal/http/PartImpl.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/http/MultipartParser.java` (`buildPart` only, interim until Task 7.5)
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/http/RequestLimits.java` (Javadoc `{@link}`)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/http/PartStorageTest.java` (create)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/http/PartImplTest.java` (create)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/http/MultipartParserTest.java` (two `bytes()` uses)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces (package `io.vidocq.foy.internal.http`):
  - `public final class PartStorage implements AutoCloseable`:
    - `PartStorage(Path location)`, where `location` may be `null`;
    - `static Path resolveLocation(String location, File tempDir)`;
    - `Path location()`;
    - package-private `Path createTempFile() throws IOException`, `void disown(Path)`, `void discard(Path) throws IOException`, `Path writeTarget(String) throws IOException`;
    - `void close()`, which never throws and is idempotent.
  - `final class PartSink implements AutoCloseable`:
    - `PartSink(PartStorage, int threshold, long maxFileSize)`;
    - `void write(byte[], int, int) throws IOException`;
    - `PartImpl finish(String name, String submittedFileName, String contentType, List<PartImpl.Header> headers) throws IOException`;
    - `void close()`.
  - `public final class PartImpl implements Part`:
    - `public record Header(String name, String value)`;
    - package-private factories `inMemory(String, String, String, List<Header>, byte[], PartStorage)` and `onDisk(String, String, String, List<Header>, Path, long, PartStorage)`;
    - `public String text(Charset) throws IOException`;
    - `public Charset charset(Charset fallback)`;
    - package-private `void discard()`.

    `bytes()`, `headersOf(...)` and the old public constructor are **removed**.
  - `public final class MultipartLimitException extends IllegalStateException`: `(String message)`.
  - `public final class MalformedMultipartException extends IOException`: `(String message)`.

- [ ] **Step 1: Write the failing tests.**

`foy-core/src/test/java/io/vidocq/foy/internal/http/PartStorageTest.java`:

```java
package io.vidocq.foy.internal.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

class PartStorageTest {

    @TempDir Path temp;

    static long count(Path dir) throws IOException {
        try (var files = Files.list(dir)) { return files.count(); }
    }

    @Test
    void theLocationResolvesAgainstTheContextTempDir() {
        assertEquals(temp, PartStorage.resolveLocation("", temp.toFile()));
        assertEquals(temp, PartStorage.resolveLocation(null, temp.toFile()));
        assertEquals(temp.resolve("uploads"), PartStorage.resolveLocation("uploads", temp.toFile()));
        Path absolute = temp.resolve("abs").toAbsolutePath();
        assertEquals(absolute, PartStorage.resolveLocation(absolute.toString(), null), "absolute: used as is");
        assertNull(PartStorage.resolveLocation("uploads", null), "relative without a temp dir: none");
        assertNull(PartStorage.resolveLocation("", null));
    }

    @Test
    void spilledFilesAreOwnerOnlyAndRandomlyNamed() throws IOException {
        Path file = new PartStorage(temp).createTempFile();
        assertEquals(temp, file.getParent());
        assertTrue(file.getFileName().toString().matches("foy-part-.*\\.tmp"), file.toString());
        if (temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        }
    }

    @Test
    void theLocationIsCreatedOnTheFirstSpill() throws IOException {
        Path nested = temp.resolve("a/b");
        new PartStorage(nested).createTempFile();
        assertEquals(1, count(nested));
    }

    @Test
    void closeDeletesEveryOwnedFileAndRefusesNewOnes() throws IOException {
        var storage = new PartStorage(temp);
        storage.createTempFile();
        storage.createTempFile();
        storage.close();
        storage.close();
        assertEquals(0, count(temp));
        assertThrows(IOException.class, storage::createTempFile);
        assertEquals(0, count(temp));
    }

    @Test
    void noLocationFailsTheSpill() {
        assertThrows(IOException.class, () -> new PartStorage(null).createTempFile());
    }

    @Test
    void writeTargetsStayInsideTheLocation() throws IOException {
        var storage = new PartStorage(temp);
        assertEquals(temp.resolve("a.txt"), storage.writeTarget("a.txt"));
        assertEquals(temp.resolve("sub/a.txt"), storage.writeTarget("sub/../sub/a.txt"));
        assertThrows(IOException.class, () -> storage.writeTarget("../escape.txt"));
        assertThrows(IOException.class, () -> storage.writeTarget("sub/../../escape.txt"));
        assertThrows(IOException.class, () -> storage.writeTarget(""));
        Path absolute = temp.getParent().resolve("elsewhere.txt").toAbsolutePath();
        assertEquals(absolute, storage.writeTarget(absolute.toString()), "absolute paths are used as provided");
    }
}
```

`foy-core/src/test/java/io/vidocq/foy/internal/http/PartImplTest.java`:

```java
package io.vidocq.foy.internal.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.vidocq.foy.internal.http.PartStorageTest.count;
import static org.junit.jupiter.api.Assertions.*;

class PartImplTest {

    @TempDir Path temp;

    private static final List<PartImpl.Header> HEADERS = List.of(
            new PartImpl.Header("Content-Disposition", "form-data; name=\"f\"; filename=\"a.txt\""),
            new PartImpl.Header("X-Tag", "1"),
            new PartImpl.Header("x-tag", "2"),
            new PartImpl.Header("Content-Type", "text/plain; charset=UTF-8"));

    private static PartImpl part(PartStorage storage, int threshold, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (var sink = new PartSink(storage, threshold, -1)) {
            sink.write(bytes, 0, bytes.length);
            return sink.finish("f", "a.txt", "text/plain; charset=UTF-8", HEADERS);
        }
    }

    private static String read(PartImpl p) throws IOException {
        try (var in = p.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    @Test
    void contentWithinTheThresholdStaysInMemory() throws IOException {
        var p = part(new PartStorage(temp), 16, "hello");
        assertEquals(0, count(temp));
        assertEquals(5, p.getSize());
        assertEquals("hello", read(p));
    }

    @Test
    void contentAboveTheThresholdSpillsToOneFile() throws IOException {
        var p = part(new PartStorage(temp), 4, "0123456789");
        assertEquals(1, count(temp));
        assertEquals(10, p.getSize());
        assertEquals("0123456789", read(p));
    }

    @Test
    void aZeroThresholdSpillsAnyContentButNotAnEmptyPart() throws IOException {
        var storage = new PartStorage(temp);
        part(storage, 0, "x");
        assertEquals(1, count(temp));
        var empty = part(storage, 0, "");
        assertEquals(1, count(temp));
        assertEquals(0, empty.getSize());
    }

    @Test
    void maxFileSizeIsEnforcedAndTheSpilledFileDeleted() throws IOException {
        var sink = new PartSink(new PartStorage(temp), 0, 4);
        sink.write("abc".getBytes(StandardCharsets.US_ASCII), 0, 3);
        assertThrows(MultipartLimitException.class, () -> sink.write("de".getBytes(StandardCharsets.US_ASCII), 0, 2));
        sink.close();
        assertEquals(0, count(temp));
    }

    @Test
    void writeMovesTheSpilledFileUnderTheLocationAndHandsItToTheApplication() throws IOException {
        var storage = new PartStorage(temp);
        var p = part(storage, 0, "hello");
        p.write("kept.txt");
        assertEquals("hello", Files.readString(temp.resolve("kept.txt")));
        assertEquals(1, count(temp), "moved, not copied");
        assertEquals("hello", read(p), "still readable after write");
        p.delete();
        storage.close();
        assertTrue(Files.exists(temp.resolve("kept.txt")), "a written file belongs to the application");
    }

    @Test
    void writeOfAnInMemoryPartCreatesTheFile() throws IOException {
        var p = part(new PartStorage(temp), 64, "small");
        p.write("small.txt");
        assertEquals("small", Files.readString(temp.resolve("small.txt")));
    }

    @Test
    void writeRefusesNamesEscapingTheLocation() throws IOException {
        Path location = Files.createDirectory(temp.resolve("location"));
        var p = part(new PartStorage(location), 0, "evil");
        assertThrows(IOException.class, () -> p.write("../evil.txt"));
        assertFalse(Files.exists(temp.resolve("evil.txt")));
    }

    @Test
    void writeUsesAnAbsolutePathAsProvided(@TempDir Path other) throws IOException {
        var p = part(new PartStorage(temp), 0, "abs");
        p.write(other.resolve("abs.txt").toString());
        assertEquals("abs", Files.readString(other.resolve("abs.txt")));
    }

    @Test
    void deleteRemovesTheTemporaryFile() throws IOException {
        var p = part(new PartStorage(temp), 0, "gone");
        p.delete();
        assertEquals(0, count(temp));
        assertThrows(IOException.class, p::getInputStream);
    }

    @Test
    void headersAreCaseInsensitiveMultiValuedAndKeepTheirFirstSpelling() throws IOException {
        var p = part(new PartStorage(temp), 64, "h");
        assertEquals("1", p.getHeader("x-TAG"));
        assertEquals(List.of("1", "2"), List.copyOf(p.getHeaders("X-Tag")));
        assertEquals(List.of("Content-Disposition", "X-Tag", "Content-Type"), List.copyOf(p.getHeaderNames()));
        assertNull(p.getHeader("Missing"));
        assertTrue(p.getHeaders("Missing").isEmpty());
    }

    @Test
    void theCharsetComesFromThePartContentType() throws IOException {
        var p = part(new PartStorage(temp), 64, "h");
        assertEquals(StandardCharsets.UTF_8, p.charset(StandardCharsets.ISO_8859_1));
        var plain = PartImpl.inMemory("n", null, null, List.of(), new byte[0], new PartStorage(temp));
        assertEquals(StandardCharsets.ISO_8859_1, plain.charset(StandardCharsets.ISO_8859_1));
    }
}
```

- [ ] **Step 2: Run them to see them fail.**
  - Run: `./mvnw -ntp -pl foy-core test -Dtest='PartStorageTest,PartImplTest'`
  - Expected: compilation FAILS (`PartStorage`, `PartSink`, `PartImpl.Header` do not exist).

- [ ] **Step 3: Create the exceptions.**

```java
package io.vidocq.foy.internal.http;

/**
 * A multipart request beyond a limit: the servlet's {@code maxFileSize} or {@code maxRequestSize},
 * or a container ceiling ({@link RequestLimits}). An {@link IllegalStateException}, as
 * {@code HttpServletRequest.getParts()} specifies for the size limits.
 */
public final class MultipartLimitException extends IllegalStateException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public MultipartLimitException(String message) { super(message); }
}
```

```java
package io.vidocq.foy.internal.http;

import java.io.IOException;

/** A multipart body that breaks RFC 7578 / RFC 2046: truncated, a bad delimiter, a bad part header. */
public final class MalformedMultipartException extends IOException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public MalformedMultipartException(String message) { super(message); }
}
```

- [ ] **Step 4: Create `PartStorage`.** Also turn the two `{@code MultipartLimitException}` in the `RequestLimits` Javadoc into `{@link MultipartLimitException}`.

```java
package io.vidocq.foy.internal.http;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The multipart storage of one request: where its parts spill, and which temporary files are still
 * the container's. {@link #close()} runs at the end of the request (asynchronous processing
 * included) and deletes them; a file a part {@code write}s out of the temporary area belongs to the
 * application and is never deleted by the container.
 */
public final class PartStorage implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(PartStorage.class.getName());
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final Path location;
    private final ReentrantLock lock = new ReentrantLock();
    /** Guarded by {@link #lock}. */
    private final Set<Path> owned = new LinkedHashSet<>();
    /** Guarded by {@link #lock}. */
    private boolean closed;

    /** @param location the resolved multipart location ({@link #resolveLocation}); {@code null} when none */
    public PartStorage(Path location) {
        this.location = location;
    }

    /**
     * Section 3.2 / {@code MultipartConfigElement.getLocation()}: an empty location is the context
     * temporary directory ({@code jakarta.servlet.context.tempdir}); a relative one resolves under it;
     * an absolute one is used as is. {@code null} when it needs a temporary directory there is none.
     */
    public static Path resolveLocation(String location, File tempDir) {
        Path temp = tempDir == null ? null : tempDir.toPath();
        if (location == null || location.isEmpty()) return temp;
        Path configured = Path.of(location);
        if (configured.isAbsolute()) return configured.normalize();
        return temp == null ? null : temp.resolve(configured).normalize();
    }

    /** The resolved location, or {@code null}. */
    public Path location() { return location; }

    /**
     * A new, empty, owner-only ({@code rw-------} where POSIX is supported) temporary file with a
     * random name under the location, created with it when needed. The client's file name is never
     * part of it.
     *
     * @throws IOException without a location, or once the request is over
     */
    Path createTempFile() throws IOException {
        if (location == null) {
            throw new IOException("no multipart location: the context has no temporary directory "
                    + "(jakarta.servlet.context.tempdir) and the multipart-config location is empty or relative");
        }
        Files.createDirectories(location);
        Path file = location.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? Files.createTempFile(location, "foy-part-", ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY))
                : Files.createTempFile(location, "foy-part-", ".tmp");
        lock.lock();
        try {
            if (!closed) {
                owned.add(file);
                return file;
            }
        } finally {
            lock.unlock();
        }
        Files.deleteIfExists(file);
        throw new IOException("the request is over: no new multipart temporary file");
    }

    /** {@code file} is no longer the container's: a part moved it to the application. */
    void disown(Path file) {
        lock.lock();
        try {
            owned.remove(file);
        } finally {
            lock.unlock();
        }
    }

    /** Deletes a container file now; a failure leaves it owned, so {@link #close()} retries. */
    void discard(Path file) throws IOException {
        Files.deleteIfExists(file);
        disown(file);
    }

    /**
     * The target of {@code Part.write(fileName)}: an absolute path as provided (Part Javadoc); a
     * relative one normalised under the location, never outside it nor the location itself.
     *
     * @throws IOException for an invalid name, a relative name without a location, or one leaving it
     */
    Path writeTarget(String fileName) throws IOException {
        Objects.requireNonNull(fileName, "fileName");
        Path requested;
        try {
            requested = Path.of(fileName);
        } catch (InvalidPathException e) {
            throw new IOException("invalid file name: " + e.getMessage(), e);
        }
        if (requested.isAbsolute()) return requested;
        if (location == null) throw new IOException("no multipart location to resolve a relative file name against");
        Path base = location.toAbsolutePath().normalize();
        Path target = base.resolve(requested).normalize();
        if (!target.startsWith(base) || target.equals(base)) {
            throw new IOException("the file name leaves the multipart location: " + fileName);
        }
        return target;
    }

    /** Deletes every file still owned and refuses new ones; idempotent, never throws. */
    @Override
    public void close() {
        List<Path> files;
        lock.lock();
        try {
            closed = true;
            files = List.copyOf(owned);
            owned.clear();
        } finally {
            lock.unlock();
        }
        for (Path f : files) {
            try {
                Files.deleteIfExists(f);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot delete the multipart temporary file " + f, e);
            }
        }
    }
}
```

- [ ] **Step 5: Create `PartSink`.**

```java
package io.vidocq.foy.internal.http;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * The content of one part while it is parsed: in memory while it is at most {@code threshold}
 * bytes, then in a container temporary file ({@code MultipartConfigElement.getFileSizeThreshold()}:
 * 0 means any non-empty content goes to disk), never more than {@code maxFileSize} bytes ({@code -1}:
 * no limit). Closing a sink that did not {@link #finish} deletes its temporary file.
 */
final class PartSink implements AutoCloseable {

    private final PartStorage storage;
    private final int threshold;
    private final long maxFileSize;
    private ByteArrayOutputStream memory = new ByteArrayOutputStream();
    private Path file;
    private OutputStream out;
    private long size;
    private boolean finished;

    PartSink(PartStorage storage, int threshold, long maxFileSize) {
        this.storage = storage;
        this.threshold = Math.max(0, threshold);
        this.maxFileSize = maxFileSize;
    }

    /** @throws MultipartLimitException once the content exceeds maxFileSize */
    void write(byte[] bytes, int offset, int length) throws IOException {
        if (length == 0) return;
        size += length;
        if (maxFileSize >= 0 && size > maxFileSize) {
            throw new MultipartLimitException("a part exceeds maxFileSize (" + maxFileSize + " bytes)");
        }
        if (out == null && size > threshold) spill();
        if (out != null) out.write(bytes, offset, length);
        else memory.write(bytes, offset, length);
    }

    private void spill() throws IOException {
        file = storage.createTempFile();
        out = new BufferedOutputStream(Files.newOutputStream(file, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING));
        memory.writeTo(out);
        memory = null;
    }

    PartImpl finish(String name, String submittedFileName, String contentType, List<PartImpl.Header> headers)
            throws IOException {
        if (out != null) {
            out.close();
            out = null;
        }
        finished = true;
        return file == null
                ? PartImpl.inMemory(name, submittedFileName, contentType, headers, memory.toByteArray(), storage)
                : PartImpl.onDisk(name, submittedFileName, contentType, headers, file, size, storage);
    }

    @Override
    public void close() {
        if (out != null) {
            try { out.close(); } catch (IOException ignored) { /* the file is deleted below */ }
            out = null;
        }
        if (!finished && file != null) {
            try { storage.discard(file); } catch (IOException ignored) { /* PartStorage.close() retries */ }
        }
    }
}
```

- [ ] **Step 6: Rewrite `PartImpl`.**

```java
package io.vidocq.foy.internal.http;

import jakarta.servlet.http.Part;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A {@link Part} of a {@code multipart/form-data} request (Servlet 6.1 section 3.2). Its content is in
 * memory up to the {@code fileSizeThreshold}, else in a container temporary file ({@link PartSink});
 * the container deletes its temporary files at the end of the request ({@link PartStorage#close()}).
 *
 * <p>{@link #getSubmittedFileName()} is the client's value, raw: it may hold {@code ../}, a drive
 * letter or a backslash path. The container never builds a path from it; an application must
 * sanitise it first.</p>
 */
public final class PartImpl implements Part {

    /** One part header as received; names compare case-insensitively. */
    public record Header(String name, String value) {
        public Header {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }

    private final String name;
    private final String submittedFileName;
    private final String contentType;
    /** Values by lower-case name; {@link #headerNames} keeps each name's first spelling, in order. */
    private final Map<String, List<String>> headers;
    private final List<String> headerNames;
    private final long size;
    private final PartStorage storage;
    private final ReentrantLock lock = new ReentrantLock();
    /** Guarded by {@link #lock}: the content in memory, or {@code null}. */
    private byte[] memory;
    /** Guarded by {@link #lock}: the file holding the content, or {@code null}. */
    private Path file;
    /** Guarded by {@link #lock}: {@link #file} is a container temporary file. */
    private boolean temporary;
    /** Guarded by {@link #lock}. */
    private boolean deleted;

    private PartImpl(String name, String submittedFileName, String contentType, List<Header> received,
                     byte[] memory, Path file, long size, PartStorage storage) {
        this.name = Objects.requireNonNull(name, "name");
        this.submittedFileName = submittedFileName;
        this.contentType = contentType;
        var values = new LinkedHashMap<String, List<String>>();
        var names = new ArrayList<String>();
        for (Header h : received) {
            String key = h.name().toLowerCase(Locale.ROOT);
            if (!values.containsKey(key)) names.add(h.name());
            values.computeIfAbsent(key, _ -> new ArrayList<>()).add(h.value());
        }
        values.replaceAll((k, v) -> List.copyOf(v));
        this.headers = Map.copyOf(values);
        this.headerNames = List.copyOf(names);
        this.memory = memory;
        this.file = file;
        this.temporary = file != null;
        this.size = size;
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    static PartImpl inMemory(String name, String submittedFileName, String contentType, List<Header> headers,
                             byte[] content, PartStorage storage) {
        return new PartImpl(name, submittedFileName, contentType, headers, content, null, content.length, storage);
    }

    static PartImpl onDisk(String name, String submittedFileName, String contentType, List<Header> headers,
                           Path file, long size, PartStorage storage) {
        return new PartImpl(name, submittedFileName, contentType, headers, null, file, size, storage);
    }

    @Override
    public InputStream getInputStream() throws IOException {
        lock.lock();
        try {
            if (deleted) throw new IOException("the part " + name + " was deleted");
            return memory != null ? new ByteArrayInputStream(memory) : Files.newInputStream(file);
        } finally {
            lock.unlock();
        }
    }

    @Override public String getContentType() { return contentType; }
    @Override public String getName() { return name; }
    @Override public String getSubmittedFileName() { return submittedFileName; }
    @Override public long getSize() { return size; }

    /**
     * Stores the content at {@code fileName}: a relative name resolves under the multipart location
     * and may not leave it; an absolute path is used as provided (Part Javadoc). A temporary file is
     * moved there and becomes the application's; a part already written is copied.
     */
    @Override
    public void write(String fileName) throws IOException {
        Path target = storage.writeTarget(fileName);
        lock.lock();
        try {
            if (deleted) throw new IOException("the part " + name + " was deleted");
            if (memory != null) {
                Files.write(target, memory);
            } else if (temporary) {
                Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                storage.disown(file);
                file = target;
                temporary = false;
            } else if (!file.equals(target)) {
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Deletes the container's storage of this part: its content in memory and its temporary file. A
     * file the application wrote with {@link #write} is the application's and stays.
     */
    @Override
    public void delete() throws IOException {
        lock.lock();
        try {
            if (deleted) return;
            deleted = true;
            memory = null;
            if (temporary) {
                temporary = false;
                storage.discard(file);
            }
        } finally {
            lock.unlock();
        }
    }

    /** {@link #delete()}, ignoring a failure: a failed parse; {@link PartStorage#close()} retries. */
    void discard() {
        try { delete(); } catch (IOException ignored) { /* retried at the end of the request */ }
    }

    @Override
    public String getHeader(String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values == null ? null : values.getFirst();
    }

    @Override
    public Collection<String> getHeaders(String name) {
        return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
    }

    @Override
    public Collection<String> getHeaderNames() { return headerNames; }

    /** The content decoded with {@code charset}: the value of a field exposed as a request parameter. */
    public String text(Charset charset) throws IOException {
        try (InputStream in = getInputStream()) {
            return new String(in.readAllBytes(), charset);
        }
    }

    /** The {@code charset} parameter of this part's Content-Type when the JDK supports it, else {@code fallback}. */
    public Charset charset(Charset fallback) {
        if (contentType == null) return fallback;
        for (String parameter : contentType.split(";")) {
            int eq = parameter.indexOf('=');
            if (eq < 0 || !parameter.substring(0, eq).trim().equalsIgnoreCase("charset")) continue;
            String value = parameter.substring(eq + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            try {
                return Charset.forName(value);
            } catch (RuntimeException unsupported) {
                return fallback;
            }
        }
        return fallback;
    }
}
```

- [ ] **Step 7: Keep the old parser compiling until Task 7.5.** In `MultipartParser`, replace `buildPart` with the version below, and add the constant `private static final PartStorage NO_STORAGE = new PartStorage(null);` to the class.

```java
    private static PartImpl buildPart(String headersRaw, byte[] content) {
        var headers = new ArrayList<PartImpl.Header>();
        String name = null;
        String filename = null;
        String contentType = null;
        for (String line : headersRaw.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String k = line.substring(0, colon).trim();
            String v = line.substring(colon + 1).trim();
            headers.add(new PartImpl.Header(k, v));
            if ("Content-Disposition".equalsIgnoreCase(k)) {
                name = extractParam(v, "name");
                filename = extractParam(v, "filename");
            } else if ("Content-Type".equalsIgnoreCase(k)) {
                contentType = v;
            }
        }
        if (name == null) return null;
        return PartImpl.inMemory(name, filename, contentType, headers, content, NO_STORAGE);
    }
```

Then remove the now-unused `import java.util.LinkedHashMap;`.

In `MultipartParserTest`:
1. Replace `new String(parts.get(0).bytes(), StandardCharsets.UTF_8)` with `content(parts.get(0))`, and `new String(p.bytes(), StandardCharsets.UTF_8)` with `content(p)`.
2. Add this helper:

```java
    private static String content(PartImpl p) {
        try (var in = p.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
```

- [ ] **Step 8: Run the tests to see them pass.**
  - Run: `./mvnw -ntp -pl foy-core test -Dtest='PartStorageTest,PartImplTest,MultipartParserTest,ServletMultipartEndToEndTest'`
  - Expected: PASS.

- [ ] **Step 9: Run the build gate.**

- [ ] **Step 10: Commit.**

```bash
git add foy-core/src/main/java/io/vidocq/foy/internal/http/PartStorage.java \
  foy-core/src/main/java/io/vidocq/foy/internal/http/PartSink.java \
  foy-core/src/main/java/io/vidocq/foy/internal/http/MultipartLimitException.java \
  foy-core/src/main/java/io/vidocq/foy/internal/http/MalformedMultipartException.java \
  foy-core/src/main/java/io/vidocq/foy/internal/http/PartImpl.java \
  foy-core/src/main/java/io/vidocq/foy/internal/http/MultipartParser.java \
  foy-core/src/main/java/io/vidocq/foy/internal/http/RequestLimits.java \
  foy-core/src/test/java/io/vidocq/foy/internal/http/PartStorageTest.java \
  foy-core/src/test/java/io/vidocq/foy/internal/http/PartImplTest.java \
  foy-core/src/test/java/io/vidocq/foy/internal/http/MultipartParserTest.java
git commit -S -s -m "feat(core): spill parts above the threshold to owner-only temporary files" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" \
  -m "Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi"
```

---

### Task 7.5: Streaming, bounded RFC 7578 parser

**Files:**
- Modify (rewrite): `foy-core/src/main/java/io/vidocq/foy/internal/http/MultipartParser.java`
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java` (one interim call in `ensurePartsParsed`)
- Test (rewrite): `foy-core/src/test/java/io/vidocq/foy/internal/http/MultipartParserTest.java`

**Interfaces:**
- Consumes (Task 7.4): `PartStorage`, `PartSink`, `PartImpl.Header`, `PartImpl.discard()`, `MultipartLimitException`, `MalformedMultipartException`. From Task 7.1: `RequestLimits`.
- Produces:
  - `public record MultipartParser.Limits(long maxFileSize, long maxRequestSize, int fileSizeThreshold, int maxParts, int maxPartHeaderSize)`, with `static Limits of(MultipartConfigElement, RequestLimits)`;
  - `public static String MultipartParser.extractBoundary(String contentType)`. It returns `null` unless the media type is `multipart/form-data` with a valid boundary (1 to 70 bchars, no trailing space).
  - `public static List<PartImpl> MultipartParser.parse(InputStream body, String boundary, Limits limits, PartStorage storage) throws IOException`. It throws `MalformedMultipartException` or `MultipartLimitException`. Its files are deleted on failure.

  `parse(byte[], String)` and `parse(InputStream, String)` are removed.

- [ ] **Step 1: Write the failing tests.** Replace `MultipartParserTest` with:

```java
package io.vidocq.foy.internal.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static io.vidocq.foy.internal.http.PartStorageTest.count;
import static org.junit.jupiter.api.Assertions.*;

class MultipartParserTest {

    @TempDir Path temp;

    private static final MultipartParser.Limits OPEN = new MultipartParser.Limits(-1, -1, 1024, -1, 8192);

    private List<PartImpl> parse(String body, String boundary, MultipartParser.Limits limits) throws IOException {
        return parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), boundary, limits);
    }

    private List<PartImpl> parse(InputStream body, String boundary, MultipartParser.Limits limits) throws IOException {
        return MultipartParser.parse(body, boundary, limits, new PartStorage(temp));
    }

    private static String content(PartImpl p) throws IOException {
        try (var in = p.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private static String field(String b, String name, String value) {
        return "--" + b + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
    }

    /** One byte per read: every delimiter and header crosses read boundaries. */
    private static InputStream trickle(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        return new InputStream() {
            private int i;
            @Override public int read() { return i < bytes.length ? bytes[i++] & 0xFF : -1; }
            @Override public int read(byte[] b, int off, int len) {
                if (len == 0) return 0;
                int c = read();
                if (c < 0) return -1;
                b[off] = (byte) c;
                return 1;
            }
        };
    }

    /** {@code head}, then the byte 'a' forever. */
    private static InputStream endless(String head) {
        return new SequenceInputStream(new ByteArrayInputStream(head.getBytes(StandardCharsets.UTF_8)),
                new InputStream() { @Override public int read() { return 'a'; } });
    }

    @Test
    void extractBoundaryAcceptsOnlyAValidMultipartFormDataBoundary() {
        assertEquals("abc", MultipartParser.extractBoundary("multipart/form-data; boundary=abc"));
        assertEquals("abc", MultipartParser.extractBoundary("Multipart/Form-Data; BOUNDARY=abc; charset=UTF-8"));
        assertEquals("a b'()+_,-./:=?", MultipartParser.extractBoundary("multipart/form-data; boundary=\"a b'()+_,-./:=?\""));
        assertNull(MultipartParser.extractBoundary("multipart/form-data"));
        assertNull(MultipartParser.extractBoundary("multipart/form-data; boundary="));
        assertNull(MultipartParser.extractBoundary("multipart/form-data; boundary=" + "x".repeat(71)));
        assertNull(MultipartParser.extractBoundary("multipart/form-data; boundary=\"ends with space \""));
        assertNull(MultipartParser.extractBoundary("multipart/form-data; boundary=a{b"));
        assertNull(MultipartParser.extractBoundary("multipart/form-datax; boundary=abc"));
        assertNull(MultipartParser.extractBoundary("text/plain; boundary=abc"));
        assertNull(MultipartParser.extractBoundary(null));
    }

    @Test
    void parsesFieldsAndFilesInOrder() throws IOException {
        String body = field("B", "a", "1")
                + "--B\r\nContent-Disposition: form-data; name=\"f\"; filename=\"hello.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\nhello world\r\n"
                + "--B--\r\n";
        var parts = parse(body, "B", OPEN);
        assertEquals(List.of("a", "f"), parts.stream().map(PartImpl::getName).toList());
        assertNull(parts.get(0).getSubmittedFileName());
        assertEquals("1", content(parts.get(0)));
        assertEquals("hello.txt", parts.get(1).getSubmittedFileName());
        assertEquals("text/plain", parts.get(1).getContentType());
        assertEquals(11, parts.get(1).getSize());
        assertEquals("hello world", content(parts.get(1)));
    }

    @Test
    void aPreambleAndAnEpilogueAreIgnoredAndPaddingIsAccepted() throws IOException {
        var parts = parse("preamble\r\n--B \t\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n1\r\n--B--\r\nepilogue", "B", OPEN);
        assertEquals(1, parts.size());
        assertEquals("1", content(parts.get(0)));
    }

    @Test
    void anEmptyMultipartBodyHasNoPart() throws IOException {
        assertTrue(parse("--B--\r\n", "B", OPEN).isEmpty());
    }

    @Test
    void boundaryTextInsideContentIsNotADelimiter() throws IOException {
        var parts = parse(field("B", "a", "x--B y --B--") + "--B--\r\n", "B", OPEN);
        assertEquals("x--B y --B--", content(parts.get(0)));
    }

    @Test
    void aPartialDelimiterBeforeTheRealOneStaysContent() throws IOException {
        String value = "keep\r\n--Xy\r\n-\r\n--X";
        var parts = parse(field("XyzW", "a", value) + "--XyzW--\r\n", "XyzW", OPEN);
        assertEquals(value, content(parts.get(0)));
    }

    @Test
    void aDelimiterSplitAcrossReadsIsFound() throws IOException {
        String body = field("Boundary", "a", "first") + field("Boundary", "b", "second") + "--Boundary--\r\n";
        var parts = parse(trickle(body), "Boundary", OPEN);
        assertEquals(List.of("first", "second"), List.of(content(parts.get(0)), content(parts.get(1))));
    }

    @Test
    void binaryContentIsByteExact() throws IOException {
        byte[] head = "--B\r\nContent-Disposition: form-data; name=\"bin\"; filename=\"b\"\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] data = new byte[256];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        byte[] tail = "\r\n--B--\r\n".getBytes(StandardCharsets.US_ASCII);
        var body = new SequenceInputStream(new SequenceInputStream(new ByteArrayInputStream(head),
                new ByteArrayInputStream(data)), new ByteArrayInputStream(tail));
        var part = parse(body, "B", OPEN).getFirst();
        try (var in = part.getInputStream()) { assertArrayEquals(data, in.readAllBytes()); }
    }

    @Test
    void nameIsNotMatchedInsideFilename() throws IOException {
        var part = parse("--B\r\nContent-Disposition: form-data; filename=\"a.txt\"; name=\"b\"\r\n\r\nx\r\n--B--\r\n", "B", OPEN).getFirst();
        assertEquals("b", part.getName());
        assertEquals("a.txt", part.getSubmittedFileName());
    }

    @Test
    void quotedValuesKeepSemicolonsAndEscapedQuotes() throws IOException {
        var part = parse("--B\r\nContent-Disposition: form-data; name=\"a;b\"; filename=\"x\\\"y.txt\"\r\n\r\nv\r\n--B--\r\n", "B", OPEN).getFirst();
        assertEquals("a;b", part.getName());
        assertEquals("x\"y.txt", part.getSubmittedFileName());
    }

    @Test
    void submittedFileNameIsReturnedRaw() throws IOException {
        var parts = parse("--B\r\nContent-Disposition: form-data; name=\"a\"; filename=\"../../etc/passwd\"\r\n\r\nv\r\n"
                + "--B\r\nContent-Disposition: form-data; name=\"b\"; filename=\"C:\\dir\\f.txt\"\r\n\r\nv\r\n--B--\r\n", "B", OPEN);
        assertEquals("../../etc/passwd", parts.get(0).getSubmittedFileName());
        assertEquals("C:\\dir\\f.txt", parts.get(1).getSubmittedFileName(), "backslashes of a Windows path are kept");
    }

    @Test
    void utf8HeadersAreDecoded() throws IOException {
        var part = parse("--B\r\nContent-Disposition: form-data; name=\"\u00e9\"; filename=\"r\u00e9sum\u00e9.pdf\"\r\n\r\nv\r\n--B--\r\n", "B", OPEN).getFirst();
        assertEquals("\u00e9", part.getName());
        assertEquals("r\u00e9sum\u00e9.pdf", part.getSubmittedFileName());
    }

    @Test
    void anExtendedFilenameWins() throws IOException {
        var part = parse("--B\r\nContent-Disposition: form-data; name=\"f\"; filename=\"fallback.txt\"; filename*=UTF-8''%E2%82%AC.txt\r\n\r\nv\r\n--B--\r\n", "B", OPEN).getFirst();
        assertEquals("\u20ac.txt", part.getSubmittedFileName());
    }

    @Test
    void repeatedHeadersAreKept() throws IOException {
        var part = parse("--B\r\nContent-Disposition: form-data; name=\"a\"\r\nX-A: 1\r\nx-a: 2\r\n\r\nv\r\n--B--\r\n", "B", OPEN).getFirst();
        assertEquals(List.of("1", "2"), List.copyOf(part.getHeaders("X-A")));
        assertEquals("1", part.getHeader("x-a"));
    }

    @Test
    void aPartWithoutAFormDataNameIsDropped() throws IOException {
        var parts = parse("--B\r\nContent-Type: text/plain\r\n\r\nno disposition\r\n"
                + "--B\r\nContent-Disposition: attachment; name=\"x\"\r\n\r\nnot form-data\r\n"
                + field("B", "kept", "1") + "--B--\r\n", "B", OPEN);
        assertEquals(List.of("kept"), parts.stream().map(PartImpl::getName).toList());
    }

    @Test
    void aTruncatedBodyIsMalformed() {
        assertThrows(MalformedMultipartException.class, () -> parse(field("B", "a", "1"), "B", OPEN));
        assertThrows(MalformedMultipartException.class, () -> parse("--B\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nhalf", "B", OPEN));
        assertThrows(MalformedMultipartException.class, () -> parse("--B\r\nContent-Disposition: form-da", "B", OPEN));
    }

    @Test
    void aBodyWithoutTheBoundaryIsMalformed() {
        assertThrows(MalformedMultipartException.class, () -> parse("not multipart", "MISSING", OPEN));
    }

    @Test
    void malformedPartHeadersAreRefused() {
        assertThrows(MalformedMultipartException.class,
                () -> parse("--B\r\nContent-Disposition: form-data; name=\"a\"\nX: y\r\n\r\nv\r\n--B--\r\n", "B", OPEN), "bare LF");
        assertThrows(MalformedMultipartException.class,
                () -> parse("--B\r\nContent-Disposition: form-data;\r\n name=\"a\"\r\n\r\nv\r\n--B--\r\n", "B", OPEN), "folded");
        assertThrows(MalformedMultipartException.class,
                () -> parse("--B\r\nContent-Disposition: form-data; name=\"a\u0000\"\r\n\r\nv\r\n--B--\r\n", "B", OPEN), "control character");
        assertThrows(MalformedMultipartException.class,
                () -> parse("--B\r\nBad Header: x\r\n\r\nv\r\n--B--\r\n", "B", OPEN), "not a token");
        assertThrows(MalformedMultipartException.class,
                () -> parse("--Bxx\r\n", "B", OPEN), "garbage after the delimiter");
    }

    @Test
    void tooManyPartsAreRefused() {
        var limits = new MultipartParser.Limits(-1, -1, 1024, 2, 8192);
        String body = field("B", "a", "1") + field("B", "b", "2") + field("B", "c", "3") + "--B--\r\n";
        assertThrows(MultipartLimitException.class, () -> parse(body, "B", limits));
    }

    @Test
    void oversizedPartHeadersAreRefused() {
        var limits = new MultipartParser.Limits(-1, -1, 1024, -1, 64);
        String body = "--B\r\nContent-Disposition: form-data; name=\"a\"\r\nX-Long: " + "y".repeat(100) + "\r\n\r\nv\r\n--B--\r\n";
        assertThrows(MultipartLimitException.class, () -> parse(body, "B", limits));
        assertThrows(MultipartLimitException.class,
                () -> parse(endless("--B\r\nX-Endless: "), "B", limits), "an endless header line stops at the limit");
    }

    @Test
    void maxFileSizeIsEnforcedWhileStreaming() {
        var limits = new MultipartParser.Limits(1024, -1, 0, -1, 8192);
        assertThrows(MultipartLimitException.class,
                () -> parse(endless("--B\r\nContent-Disposition: form-data; name=\"f\"; filename=\"f\"\r\n\r\n"), "B", limits));
    }

    @Test
    void maxRequestSizeIsEnforcedWhileStreaming() {
        var limits = new MultipartParser.Limits(-1, 64 * 1024, 0, -1, 8192);
        assertThrows(MultipartLimitException.class,
                () -> parse(endless("--B\r\nContent-Disposition: form-data; name=\"f\"; filename=\"f\"\r\n\r\n"), "B", limits));
    }

    @Test
    void contentAboveTheThresholdSpillsAndBelowStaysInMemory() throws IOException {
        var limits = new MultipartParser.Limits(-1, -1, 4, -1, 8192);
        parse(field("B", "small", "abc") + field("B", "big", "0123456789") + "--B--\r\n", "B", limits);
        assertEquals(1, count(temp));
    }

    @Test
    void aFailedParseDeletesItsTemporaryFiles() throws IOException {
        var limits = new MultipartParser.Limits(-1, -1, 0, -1, 8192);
        String body = field("B", "complete", "spilled") + "--B\r\nContent-Disposition: form-data; name=\"cut\"\r\n\r\nalso spilled";
        assertThrows(MalformedMultipartException.class, () -> parse(body, "B", limits));
        assertEquals(0, count(temp));
    }
}
```

- [ ] **Step 2: Run them to see them fail.**
  - Run: `./mvnw -ntp -pl foy-core test -Dtest=MultipartParserTest`
  - Expected: compilation FAILS (`MultipartParser.Limits`, the 4-argument `parse` do not exist).

- [ ] **Step 3: Rewrite `MultipartParser`.**

```java
package io.vidocq.foy.internal.http;

import jakarta.servlet.MultipartConfigElement;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Streaming {@code multipart/form-data} parser (RFC 7578, RFC 2046 section 5.1).
 *
 * <p>The body is read once, through an 8 KiB window: a delimiter is {@code CRLF--boundary} (the
 * first one may open the body), found in linear time (Knuth-Morris-Pratt), so a file that holds the
 * boundary text elsewhere is never split. Each part's content goes to a {@link PartSink} (memory up
 * to the threshold, then a temporary file). Part headers are decoded as UTF-8 (ISO-8859-1 when they
 * are not valid UTF-8), kept in order with their repetitions; {@code filename*} (RFC 8187) wins over
 * {@code filename}. A truncated or malformed body is a {@link MalformedMultipartException}; a body
 * beyond a limit a {@link MultipartLimitException}; either way the files already written are deleted.
 * A part without a {@code form-data} disposition or a {@code name} is read, counted and dropped.</p>
 */
public final class MultipartParser {

    /** Read-ahead window, and the largest chunk handed to a part at once. */
    private static final int BUFFER = 8192;

    /**
     * The limits of one parse ({@code -1}: none): the servlet's {@code maxFileSize},
     * {@code maxRequestSize} and {@code fileSizeThreshold}, the container's part count and part
     * header size.
     */
    public record Limits(long maxFileSize, long maxRequestSize, int fileSizeThreshold, int maxParts,
                         int maxPartHeaderSize) {

        public static Limits of(MultipartConfigElement config, RequestLimits container) {
            return new Limits(config.getMaxFileSize(), config.getMaxRequestSize(),
                    Math.max(0, config.getFileSizeThreshold()), container.maxMultipartParts(),
                    container.maxMultipartPartHeaderSize());
        }
    }

    private MultipartParser() {}

    /**
     * The boundary of a {@code multipart/form-data} Content-Type: 1 to 70 RFC 2046 bchars, not ending
     * with a space, quoted or not; {@code null} for another media type or an invalid boundary.
     */
    public static String extractBoundary(String contentType) {
        if (contentType == null) return null;
        var params = new HashMap<String, String>();
        try {
            if (!"multipart/form-data".equals(parseParameters(contentType, params))) return null;
        } catch (MalformedMultipartException e) {
            return null;
        }
        String boundary = params.get("boundary");
        if (boundary == null || boundary.isEmpty() || boundary.length() > 70 || boundary.endsWith(" ")) return null;
        for (int i = 0; i < boundary.length(); i++) {
            if (!isBoundaryChar(boundary.charAt(i))) return null;
        }
        return boundary;
    }

    /**
     * Parses {@code body} up to its close delimiter (the epilogue is left unread).
     *
     * @throws MalformedMultipartException for a truncated or malformed body
     * @throws MultipartLimitException     beyond a limit
     * @throws IOException                 when the body or a temporary file fails
     */
    public static List<PartImpl> parse(InputStream body, String boundary, Limits limits, PartStorage storage)
            throws IOException {
        var in = new CountingReader(body, limits.maxRequestSize());
        var scanner = new DelimiterScanner(("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII));
        var parts = new ArrayList<PartImpl>();
        boolean complete = false;
        try {
            // The preamble is discarded; the first delimiter may open the body, as if a CRLF preceded it.
            if (!scanner.scan(in, null, true)) throw new MalformedMultipartException("no multipart boundary in the body");
            int seen = 0;
            while (!atCloseDelimiter(in)) {
                if (limits.maxParts() >= 0 && seen == limits.maxParts()) {
                    throw new MultipartLimitException("the request has more than " + limits.maxParts() + " parts");
                }
                seen++;
                List<PartImpl.Header> headers = readHeaders(in, limits.maxPartHeaderSize());
                Disposition disposition = Disposition.of(headers);
                if (disposition == null) {
                    if (!scanner.scan(in, null, false)) throw new MalformedMultipartException("truncated multipart body");
                    continue;
                }
                try (var sink = new PartSink(storage, limits.fileSizeThreshold(), limits.maxFileSize())) {
                    if (!scanner.scan(in, sink, false)) throw new MalformedMultipartException("truncated multipart body");
                    parts.add(sink.finish(disposition.name(), disposition.fileName(), header(headers, "Content-Type"),
                            headers));
                }
            }
            complete = true;
            return parts;
        } finally {
            if (!complete) for (PartImpl p : parts) p.discard();
        }
    }

    /** After a delimiter: {@code --} closes the body; else optional padding then CRLF open a part. */
    private static boolean atCloseDelimiter(CountingReader in) throws IOException {
        int c = in.read();
        if (c == '-') {
            if (in.read() == '-') return true;
            throw new MalformedMultipartException("malformed close delimiter");
        }
        while (c == ' ' || c == '\t') c = in.read();
        if (c == '\r') {
            if (in.read() == '\n') return false;
            throw new MalformedMultipartException("malformed delimiter line");
        }
        if (c < 0) throw new MalformedMultipartException("truncated multipart body");
        throw new MalformedMultipartException("malformed delimiter line");
    }

    /** The header section of a part, up to its blank line, at most {@code maxBytes} bytes (CRLFs included). */
    private static List<PartImpl.Header> readHeaders(CountingReader in, int maxBytes) throws IOException {
        var headers = new ArrayList<PartImpl.Header>();
        var line = new ByteArrayOutputStream();
        int total = 0;
        while (true) {
            line.reset();
            int c;
            while ((c = in.read()) != '\r') {
                if (c < 0) throw new MalformedMultipartException("truncated multipart part headers");
                if (c == '\n') throw new MalformedMultipartException("bare LF in a part header");
                if (++total > maxBytes) {
                    throw new MultipartLimitException("the headers of a part exceed " + maxBytes + " bytes");
                }
                line.write(c);
            }
            if (in.read() != '\n') throw new MalformedMultipartException("bare CR in a part header");
            total += 2;
            if (total > maxBytes) throw new MultipartLimitException("the headers of a part exceed " + maxBytes + " bytes");
            if (line.size() == 0) return headers;
            headers.add(parseHeader(line.toByteArray()));
        }
    }

    private static PartImpl.Header parseHeader(byte[] raw) throws MalformedMultipartException {
        if (raw[0] == ' ' || raw[0] == '\t') throw new MalformedMultipartException("obsolete folded part header");
        String text = decodeHeader(raw);
        int colon = text.indexOf(':');
        if (colon <= 0 || !isToken(text, 0, colon)) throw new MalformedMultipartException("malformed part header");
        for (int i = colon + 1; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch < 0x20 && ch != '\t') || ch == 0x7F) {
                throw new MalformedMultipartException("control character in a part header");
            }
        }
        return new PartImpl.Header(text.substring(0, colon), trimOws(text.substring(colon + 1)));
    }

    /** UTF-8 (what browsers send, RFC 7578 section 5.1), else ISO-8859-1. */
    private static String decodeHeader(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            return new String(raw, StandardCharsets.ISO_8859_1);
        }
    }

    private static String header(List<PartImpl.Header> headers, String name) {
        for (var h : headers) if (h.name().equalsIgnoreCase(name)) return h.value();
        return null;
    }

    /** The {@code form-data} name and file name of a part; {@code null} when it has none (the part is dropped). */
    private record Disposition(String name, String fileName) {

        static Disposition of(List<PartImpl.Header> headers) throws MalformedMultipartException {
            String value = header(headers, "Content-Disposition");
            if (value == null) return null;
            var params = new HashMap<String, String>();
            if (!"form-data".equals(parseParameters(value, params))) return null;
            String name = params.get("name");
            if (name == null) return null;
            String extended = params.containsKey("filename*") ? decodeExtendedValue(params.get("filename*")) : null;
            return new Disposition(name, extended != null ? extended : params.get("filename"));
        }
    }

    /**
     * Parses {@code type; name=value; name="quoted"} into {@code params} (lower-case names) and returns
     * the lower-case type. Inside quotes only {@code \"} and {@code \\} are escapes: browsers send the
     * backslashes of a Windows path raw.
     */
    static String parseParameters(String value, Map<String, String> params) throws MalformedMultipartException {
        int length = value.length();
        int semi = value.indexOf(';');
        String type = (semi < 0 ? value : value.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
        int i = semi < 0 ? length : semi + 1;
        while (i < length) {
            i = skipOws(value, i);
            if (i == length) break;
            if (value.charAt(i) == ';') {
                i++;
                continue;
            }
            int eq = value.indexOf('=', i);
            if (eq < 0) throw new MalformedMultipartException("parameter without a value");
            String name = value.substring(i, eq).trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) throw new MalformedMultipartException("parameter without a name");
            i = skipOws(value, eq + 1);
            String parameter;
            if (i < length && value.charAt(i) == '"') {
                var quoted = new StringBuilder();
                i++;
                while (true) {
                    if (i == length) throw new MalformedMultipartException("unterminated quoted string");
                    char c = value.charAt(i++);
                    if (c == '"') break;
                    if (c == '\\' && i < length && (value.charAt(i) == '"' || value.charAt(i) == '\\')) c = value.charAt(i++);
                    quoted.append(c);
                }
                parameter = quoted.toString();
                i = skipOws(value, i);
                if (i < length && value.charAt(i) != ';') {
                    throw new MalformedMultipartException("unexpected text after a quoted parameter");
                }
            } else {
                int end = value.indexOf(';', i);
                if (end < 0) end = length;
                parameter = value.substring(i, end).trim();
                i = end;
            }
            if (params.putIfAbsent(name, parameter) != null) {
                throw new MalformedMultipartException("duplicate parameter " + name);
            }
            if (i < length) i++;
        }
        return type;
    }

    /** RFC 8187 {@code charset'language'pct-encoded} in UTF-8 or ISO-8859-1; {@code null} when it does not decode. */
    private static String decodeExtendedValue(String value) {
        int first = value.indexOf('\'');
        int second = first < 0 ? -1 : value.indexOf('\'', first + 1);
        if (second < 0) return null;
        Charset charset = switch (value.substring(0, first).toUpperCase(Locale.ROOT)) {
            case "UTF-8" -> StandardCharsets.UTF_8;
            case "ISO-8859-1" -> StandardCharsets.ISO_8859_1;
            default -> null;
        };
        if (charset == null) return null;
        var bytes = new ByteArrayOutputStream();
        String encoded = value.substring(second + 1);
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '%') {
                if (i + 2 >= encoded.length()) return null;
                int hi = Character.digit(encoded.charAt(i + 1), 16);
                int lo = Character.digit(encoded.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) return null;
                bytes.write(hi << 4 | lo);
                i += 2;
            } else if (c < 0x21 || c > 0x7E) {
                return null;
            } else {
                bytes.write(c);
            }
        }
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static boolean isBoundaryChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || "'()+_,-./:=? ".indexOf(c) >= 0;
    }

    private static boolean isToken(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!ok) return false;
        }
        return true;
    }

    private static int skipOws(String s, int i) {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return i;
    }

    private static String trimOws(String s) {
        int start = skipOws(s, 0);
        int end = s.length();
        while (end > start && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) end--;
        return s.substring(start, end);
    }

    /** The body, read through a window and counted against maxRequestSize. */
    private static final class CountingReader {
        private final InputStream in;
        private final long max;
        private final byte[] buffer = new byte[BUFFER];
        private int position;
        private int length;
        private long total;

        CountingReader(InputStream in, long max) {
            this.in = in;
            this.max = max;
        }

        int read() throws IOException {
            if (position == length) {
                int n;
                do {
                    n = in.read(buffer, 0, buffer.length);
                } while (n == 0);
                if (n < 0) return -1;
                total += n;
                if (max >= 0 && total > max) {
                    throw new MultipartLimitException("the multipart body exceeds maxRequestSize (" + max + " bytes)");
                }
                position = 0;
                length = n;
            }
            return buffer[position++] & 0xFF;
        }
    }

    /** Finds the delimiter in linear time (Knuth-Morris-Pratt); the bytes before it go to a sink. */
    private static final class DelimiterScanner {
        private final byte[] delimiter;
        /** {@code border[i]}: length of the longest proper border of {@code delimiter[0..i]}. */
        private final int[] border;
        private final byte[] pending = new byte[BUFFER];

        DelimiterScanner(byte[] delimiter) {
            this.delimiter = delimiter;
            this.border = new int[delimiter.length];
            for (int i = 1, k = 0; i < delimiter.length; i++) {
                while (k > 0 && delimiter[i] != delimiter[k]) k = border[k - 1];
                if (delimiter[i] == delimiter[k]) k++;
                border[i] = k;
            }
        }

        /**
         * Consumes the stream up to and including the next delimiter.
         *
         * @param sink   receives the bytes before the delimiter; {@code null} discards them
         * @param primed the stream starts right after a CRLF (the body's first delimiter needs none)
         * @return {@code false} when the stream ends first
         */
        boolean scan(CountingReader in, PartSink sink, boolean primed) throws IOException {
            int matched = primed ? 2 : 0;
            int pendingLength = 0;
            int b;
            while ((b = in.read()) >= 0) {
                while (matched > 0 && delimiter[matched] != (byte) b) {
                    int keep = border[matched - 1];
                    // delimiter[0 .. matched - keep) can no longer begin a delimiter: it was content.
                    for (int i = 0; i < matched - keep; i++) {
                        if (pendingLength == pending.length) pendingLength = flush(sink, pendingLength);
                        pending[pendingLength++] = delimiter[i];
                    }
                    matched = keep;
                }
                if (delimiter[matched] == (byte) b) {
                    if (++matched == delimiter.length) {
                        flush(sink, pendingLength);
                        return true;
                    }
                } else {
                    if (pendingLength == pending.length) pendingLength = flush(sink, pendingLength);
                    pending[pendingLength++] = (byte) b;
                }
            }
            flush(sink, pendingLength);
            return false;
        }

        private int flush(PartSink sink, int pendingLength) throws IOException {
            if (sink != null && pendingLength > 0) sink.write(pending, 0, pendingLength);
            return 0;
        }
    }
}
```

- [ ] **Step 4: Keep the request compiling until Task 7.6.** In `HttpServletRequestImpl.ensurePartsParsed()`, replace

```java
        parsedParts = io.vidocq.foy.internal.http.MultipartParser.parse(
                chappe.body().asInputStream(), boundary);
```

with

```java
        // Interim until Task 7.6: unlimited and in memory, as before the streaming parser.
        parsedParts = io.vidocq.foy.internal.http.MultipartParser.parse(chappe.body().asInputStream(), boundary,
                new io.vidocq.foy.internal.http.MultipartParser.Limits(-1, -1, Integer.MAX_VALUE, -1, 64 * 1024),
                new io.vidocq.foy.internal.http.PartStorage(null));
```

- [ ] **Step 5: Run the tests to see them pass.**
  - Run: `./mvnw -ntp -pl foy-core test -Dtest='MultipartParserTest,PartImplTest,PartStorageTest,ServletMultipartEndToEndTest'`
  - Expected: PASS.

- [ ] **Step 6: Run the build gate.**

- [ ] **Step 7: Commit.**

```bash
git add foy-core/src/main/java/io/vidocq/foy/internal/http/MultipartParser.java \
  foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java \
  foy-core/src/test/java/io/vidocq/foy/internal/http/MultipartParserTest.java
git commit -S -s -m "feat(core): stream multipart bodies through a bounded RFC 7578 parser" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" \
  -m "Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi"
```

---

### Task 7.6: The `getParts`/`getPart` contract, tracked body and end-of-request cleanup

**TCK targeted:**
- `PartTests.getPartTest1`, `PartTests.getPartsTest`;
- `Part1Tests.getPartTest1`, `Part1Tests.getPartsTest`.

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java`:
  - `roleRefServletName` ~187 and its caller `isUserInRole` ~715;
  - `isFormPost` ~323;
  - the multipart block ~873-896.
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/ChappeServletBridge.java` (`runPipeline` `finally` ~166-175)
- Test (rewrite): `foy-core/src/test/java/io/vidocq/foy/internal/ServletMultipartEndToEndTest.java`

**Interfaces:**
- Consumes:
  - Task 7.2: `containerBody()`, `bodyConsumedByContainer`, `limits()`.
  - Task 7.3: `VidocqServletContext.multipartConfig(String)`.
  - Task 7.4: `PartStorage(Path)`, `PartStorage.resolveLocation(String, File)`, `PartStorage.close()`, `MultipartLimitException`, `MalformedMultipartException`.
  - Task 7.5: `MultipartParser.extractBoundary`, `MultipartParser.parse(InputStream, String, Limits, PartStorage)`, `MultipartParser.Limits.of`.
- Produces, in `HttpServletRequestImpl`:
  - `getParts()`/`getPart(String)` declare `throws IOException, ServletException`;
  - `private List<PartImpl> parts() throws IOException, ServletException` (parses once, caches the failure);
  - `private List<PartImpl> parsedParts`, `private Exception partsFailure`;
  - `private boolean isMultipart()`, `private String mediaType()`, `private MultipartConfigElement multipartConfig()`;
  - `private String executingServletName()` (renamed from `roleRefServletName`);
  - package-private `void releaseParts()`.

  Task 7.7 uses `parts()`, `parsedParts`, `partsFailure`, `isMultipart()`, `multipartConfig()`.

- [ ] **Step 1: Write the failing tests.** Replace `ServletMultipartEndToEndTest` with:

```java
package io.vidocq.foy.internal;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.dispatcher.FilterRegistry;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/** Servlet 6.1 section 3.2 over real chappe: contract, storage lifecycle, keep-alive. */
class ServletMultipartEndToEndTest {

    private static final String BOUNDARY = "FOY-TEST-BOUNDARY";

    @TempDir Path temp;
    private Server server;
    private int port;

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

    /** What a test servlet does with the request; its result, or its exception's simple name, is the body. */
    interface Action { String run(HttpServletRequest req) throws Exception; }

    private static HttpServlet servlet(Action action) {
        return new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                String out;
                try {
                    out = action.run(req);
                } catch (Exception e) {
                    out = e.getClass().getSimpleName();
                }
                resp.setContentType("text/plain");
                resp.getWriter().write(out);
            }

            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getWriter().write("second");
            }
        };
    }

    /** Serves {@code servlet} at /upload as "U", with {@code config} (none when null), TEMPDIR = {@code temp}. */
    private void start(HttpServlet servlet, MultipartConfigElement config) {
        var ctx = new VidocqServletContext("/");
        ctx.setAttribute(ServletContext.TEMPDIR, temp.toFile());
        if (config != null) ctx.addServlet("U", servlet).setMultipartConfig(config);
        var bridge = new ChappeServletBridge(
                new ServletDispatcher(List.of(new ServletDispatcher.Mapping(UrlPatternMatcher.of("/upload"), servlet, "U"))),
                new FilterRegistry(List.of()), TestSecurity.permitAll(ctx), null, "/");
        var r = TestServerLauncher.start(bridge);
        server = r.server;
        port = r.port;
    }

    private static String field(String name, String value) {
        return "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
    }

    private static String file(String name, String fileName, String type, String content) {
        return "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName
                + "\"\r\nContent-Type: " + type + "\r\n\r\n" + content + "\r\n";
    }

    private static String end() { return "--" + BOUNDARY + "--\r\n"; }

    private HttpResponse<String> post(String contentType, String body) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/upload"))
                        .header("Content-Type", contentType).timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postMultipart(String body) throws Exception {
        return post("multipart/form-data; boundary=" + BOUNDARY, body);
    }

    private long tempFiles() throws IOException {
        try (var files = Files.list(temp)) { return files.count(); }
    }

    @Test
    void uploadsFileAndFormField() throws Exception {
        start(servlet(req -> {
            Part field = req.getPart("desc");
            Part file = req.getPart("upload");
            return new String(field.getInputStream().readAllBytes(), UTF_8) + "|" + file.getSubmittedFileName()
                    + "(" + file.getContentType() + "):" + new String(file.getInputStream().readAllBytes(), UTF_8);
        }), new MultipartConfigElement(""));
        var resp = postMultipart(field("desc", "release notes")
                + file("upload", "changelog.txt", "text/markdown", "# M2g\r\nmultipart support") + end());
        assertEquals(200, resp.statusCode());
        assertEquals("release notes|changelog.txt(text/markdown):# M2g\r\nmultipart support", resp.body());
    }

    /** getParts() Javadoc: a request that is not multipart/form-data is a ServletException (it used to yield no part). */
    @Test
    void getPartsOnANonMultipartRequestThrowsServletException() throws Exception {
        start(servlet(req -> Integer.toString(req.getParts().size())), new MultipartConfigElement(""));
        assertEquals("ServletException", post("application/json", "{}").body());
        assertEquals("ServletException", post("multipart/form-datax; boundary=" + BOUNDARY, end()).body());
        assertEquals("ServletException", post("  text/plain; boundary=" + BOUNDARY, end()).body());
    }

    @Test
    void getPartWithoutAMultipartConfigThrowsIllegalStateException() throws Exception {
        start(servlet(req -> String.valueOf(req.getPart("a"))), null);
        assertEquals("IllegalStateException", postMultipart(field("a", "1") + end()).body());
    }

    @Test
    void getPartReturnsNullForAMissingName() throws Exception {
        start(servlet(req -> String.valueOf(req.getPart("missing"))), new MultipartConfigElement(""));
        assertEquals("null", postMultipart(field("a", "1") + end()).body());
    }

    @Test
    void getPartsAfterGetInputStreamThrowsIllegalStateException() throws Exception {
        start(servlet(req -> {
            req.getInputStream();
            return Integer.toString(req.getParts().size());
        }), new MultipartConfigElement(""));
        assertEquals("IllegalStateException", postMultipart(field("a", "1") + end()).body());
    }

    @Test
    void aFailedParseIsRethrownWithoutReadingTheBodyAgain() throws Exception {
        start(servlet(req -> {
            IllegalStateException first = null;
            IllegalStateException again = null;
            try { req.getParts(); } catch (IllegalStateException e) { first = e; }
            try { req.getPart("a"); } catch (IllegalStateException e) { again = e; }
            return (first != null && first == again) + ":" + first.getClass().getSimpleName() + ":"
                    + req.getInputStream().read();
        }), new MultipartConfigElement("", 4, -1, 0));
        assertEquals("true:MultipartLimitException:-1", postMultipart(field("a", "too long") + end()).body());
    }

    @Test
    void partsSpillToTheContextTempDirAndAreDeletedAfterTheRequest() throws Exception {
        start(servlet(req -> req.getParts().size() + " parts, " + tempFiles() + " files"), new MultipartConfigElement(""));
        assertEquals("2 parts, 2 files",
                postMultipart(field("a", "1") + file("f", "x.txt", "text/plain", "data") + end()).body());
        assertEquals(0, tempFiles(), "deleted at the end of the request");
    }

    @Test
    void partsOfAnAsyncRequestLiveUntilItCompletes() throws Exception {
        var during = new AtomicLong(-1);
        HttpServlet async = new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
                req.getParts();
                AsyncContext ac = req.startAsync();
                ac.start(() -> {
                    try {
                        during.set(tempFiles());
                        ac.getResponse().getWriter().write(
                                new String(req.getPart("f").getInputStream().readAllBytes(), UTF_8));
                    } catch (Exception e) {
                        during.set(-2);
                    }
                    ac.complete();
                });
            }
        };
        start(async, new MultipartConfigElement(""));
        assertEquals("data", postMultipart(file("f", "x.txt", "text/plain", "data") + end()).body());
        assertEquals(1, during.get(), "the file exists while the async cycle runs");
        assertEquals(0, tempFiles(), "deleted once the async cycle completed");
    }

    @Test
    void aWrappedRequestUsesTheExecutingServletsConfiguration() throws Exception {
        start(servlet(req -> Integer.toString(new HttpServletRequestWrapper(req).getParts().size())),
                new MultipartConfigElement(""));
        assertEquals("1", postMultipart(field("a", "1") + end()).body());
    }

    @Test
    void theConnectionServesTheNextRequestAfterParts() throws Exception {
        start(servlet(req -> new String(req.getPart("a").getInputStream().readAllBytes(), UTF_8)),
                new MultipartConfigElement(""));
        byte[] body = (field("a", "first") + end() + "an epilogue the parser never reads").getBytes(UTF_8);
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = new java.io.BufferedInputStream(socket.getInputStream());
            out.write(("POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Type: multipart/form-data; boundary="
                    + BOUNDARY + "\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(US_ASCII));
            out.write(body);
            out.flush();
            String first = readResponse(in);
            assertTrue(first.startsWith("HTTP/1.1 200") && first.endsWith("|first"), first);
            out.write("GET /upload HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(US_ASCII));
            out.flush();
            String second = readResponse(in);
            assertTrue(second.startsWith("HTTP/1.1 200") && second.endsWith("|second"), second);
        }
    }

    /** "status line|body" of one HTTP/1.1 response (Content-Length or chunked). */
    private static String readResponse(InputStream in) throws IOException {
        var head = new ByteArrayOutputStream();
        while (!head.toString(ISO_8859_1).endsWith("\r\n\r\n")) {
            int c = in.read();
            if (c < 0) throw new EOFException("connection closed after: " + head.toString(ISO_8859_1));
            head.write(c);
        }
        String[] lines = head.toString(ISO_8859_1).split("\r\n");
        int length = -1;
        boolean chunked = false;
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) chunked = true;
        }
        var body = new ByteArrayOutputStream();
        if (chunked) {
            while (true) {
                int n = Integer.parseInt(readLine(in).split(";")[0].trim(), 16);
                if (n == 0) {
                    while (!readLine(in).isEmpty()) { /* trailers */ }
                    break;
                }
                body.write(in.readNBytes(n));
                readLine(in);
            }
        } else if (length > 0) {
            body.write(in.readNBytes(length));
        }
        return lines[0] + "|" + body.toString(UTF_8);
    }

    private static String readLine(InputStream in) throws IOException {
        var line = new StringBuilder();
        int c;
        while ((c = in.read()) != '\n') {
            if (c < 0) throw new EOFException();
            if (c != '\r') line.append((char) c);
        }
        return line.toString();
    }
}
```

- [ ] **Step 2: Run them to see them fail.**
  - Run: `./mvnw -ntp -pl foy-core test -Dtest=ServletMultipartEndToEndTest`
  - Expected: FAIL in:
    - `getPartsOnANonMultipartRequestThrowsServletException` (gets `0`);
    - `getPartWithoutAMultipartConfigThrowsIllegalStateException` (gets the part);
    - `getPartsAfterGetInputStreamThrowsIllegalStateException`;
    - `aFailedParseIsRethrownWithoutReadingTheBodyAgain`;
    - `partsSpillToTheContextTempDirAndAreDeletedAfterTheRequest` (0 files);
    - `partsOfAnAsyncRequestLiveUntilItCompletes`.

- [ ] **Step 3: Implement the contract.** In `HttpServletRequestImpl`:
1. Add the imports below.
2. Rename `roleRefServletName()` to `executingServletName()`, its Javadoc unchanged, and update the call in `isUserInRole`.
3. Replace `isFormPost()` with the version below, which uses `mediaType()`.
4. Replace the whole block from `private java.util.List<io.vidocq.foy.internal.http.PartImpl> parsedParts;` to the end of `ensurePartsParsed()` with the multipart block below.

Imports:

```java
import io.vidocq.foy.internal.http.MalformedMultipartException;
import io.vidocq.foy.internal.http.MultipartLimitException;
import io.vidocq.foy.internal.http.MultipartParser;
import io.vidocq.foy.internal.http.PartImpl;
import io.vidocq.foy.internal.http.PartStorage;
import jakarta.servlet.MultipartConfigElement;
```

`isFormPost()` and `mediaType()`:

```java
    private boolean isFormPost() {
        return "POST".equalsIgnoreCase(getMethod()) && "application/x-www-form-urlencoded".equalsIgnoreCase(mediaType());
    }

    /** The media type of the Content-Type (no parameter, trimmed), or {@code null}. */
    private String mediaType() {
        String ct = getContentType();
        if (ct == null) return null;
        int semi = ct.indexOf(';');
        return (semi < 0 ? ct : ct.substring(0, semi)).trim();
    }
```

The multipart block:

```java
    // ---- Multipart (Servlet 6.1 section 3.2) ----

    private List<PartImpl> parsedParts;
    /** The failure of the one parse attempt, rethrown by every later getParts()/getPart(). */
    private Exception partsFailure;
    private PartStorage partStorage;

    @Override public Collection<Part> getParts() throws IOException, ServletException {
        return new ArrayList<>(parts());
    }

    @Override public Part getPart(String name) throws IOException, ServletException {
        for (PartImpl p : parts()) if (p.getName().equals(name)) return p;
        return null;
    }

    /**
     * Parses the parts once, through the tracked body. Checked in this order: the media type
     * ({@link ServletException}), the executing servlet's multipart configuration, then the body not
     * already taken by getInputStream()/getReader() (both {@link IllegalStateException}). A parse
     * failure (a limit: {@link MultipartLimitException}; malformed or I/O: {@link IOException}) is
     * cached and rethrown as is: the body is never read twice.
     */
    private List<PartImpl> parts() throws IOException, ServletException {
        if (parsedParts != null) return parsedParts;
        if (partsFailure != null) throw rethrowParts(partsFailure);
        if (!isMultipart()) {
            throw new ServletException("not a multipart/form-data request (Content-Type: " + getContentType() + ")");
        }
        MultipartConfigElement config = multipartConfig();
        if (config == null) {
            throw new IllegalStateException("no @MultipartConfig or <multipart-config> for servlet "
                    + executingServletName());
        }
        if (inputStream != null || reader != null) {
            throw new IllegalStateException("the request body was already read through getInputStream() or getReader()");
        }
        try {
            String boundary = MultipartParser.extractBoundary(getContentType());
            if (boundary == null) throw new MalformedMultipartException("missing or invalid multipart boundary");
            long declared = getContentLengthLong();
            if (config.getMaxRequestSize() >= 0 && declared > config.getMaxRequestSize()) {
                throw new MultipartLimitException("the request (" + declared + " bytes) exceeds maxRequestSize ("
                        + config.getMaxRequestSize() + " bytes)");
            }
            partStorage = new PartStorage(PartStorage.resolveLocation(config.getLocation(), contextTempDir()));
            parsedParts = List.copyOf(MultipartParser.parse(containerBody(), boundary,
                    MultipartParser.Limits.of(config, limits()), partStorage));
        } catch (IOException | RuntimeException e) {
            partsFailure = e;
            bodyConsumedByContainer = true;
            throw e;
        }
        return parsedParts;
    }

    private static RuntimeException rethrowParts(Exception failure) throws IOException {
        if (failure instanceof IOException io) throw io;
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw new IllegalStateException(failure);
    }

    private boolean isMultipart() {
        return "multipart/form-data".equalsIgnoreCase(mediaType());
    }

    /** The multipart configuration of the servlet executing on the calling thread (section 3.2), or null. */
    private MultipartConfigElement multipartConfig() {
        return servletContext instanceof VidocqServletContext v ? v.multipartConfig(executingServletName()) : null;
    }

    private java.io.File contextTempDir() {
        return servletContext.getAttribute(ServletContext.TEMPDIR) instanceof java.io.File f ? f : null;
    }

    /**
     * The request is over (asynchronous processing included): the container's temporary part files
     * are deleted; files the application wrote with {@code Part.write} stay. Idempotent.
     */
    void releaseParts() {
        var storage = partStorage;
        if (storage != null) storage.close();
    }
```

- [ ] **Step 4: Delete the temporary files at the end of every request.** In `ChappeServletBridge.runPipeline`, extend the loop in the first `finally` to:

```java
            for (HttpServletRequestImpl r : requests) {
                // Last guard (idempotent): no ReadListener callback any more, the body pump stops.
                r.endNonBlockingIo();
                try { r.endSessionAccess(); }
                catch (RuntimeException e) { LOG.log(System.Logger.Level.WARNING, "ending the session access failed", e); }
                // The container's temporary part files go with the request (section 3.2).
                try { r.releaseParts(); }
                catch (RuntimeException e) { LOG.log(System.Logger.Level.WARNING, "deleting the multipart files failed", e); }
            }
```

- [ ] **Step 5: Run the tests to see them pass.**
  - Run: `./mvnw -ntp -pl foy-core test -Dtest='ServletMultipartEndToEndTest,RequestBodyStateTest,MultipartParserTest'`
  - Expected: PASS.

- [ ] **Step 6: Run the gates and the TCK classes.**
  1. The build gate.
  2. `./mvnw -ntp -Ptck -pl foy-tck test`.
  3. `./run-official-tck-servlet6.1.sh --no-install -Dtest='PartTests,Part1Tests' > /tmp/foy-tck-p7-6.log 2>&1`, then `bash foy-tck/tck-tally.sh /tmp/foy-tck-p7-6.log`.
  4. Expected:
     - `servlet.tck.api.jakarta_servlet_http.part.PartTests 7 2 0`
     - `servlet.tck.api.jakarta_servlet_http.part1.Part1Tests 7 2 0`

     The failures left are `getPartTest` and `getPartsTest1`, the parameter gap of Task 7.7.

- [ ] **Step 7: Commit.**

```bash
git add foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java \
  foy-core/src/main/java/io/vidocq/foy/internal/bridge/ChappeServletBridge.java \
  foy-core/src/test/java/io/vidocq/foy/internal/ServletMultipartEndToEndTest.java
git commit -S -s -m "fix(core): follow the getParts contract and delete part files at the end of the request" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" \
  -m "Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi"
```

---

### Task 7.7: Form fields of a multipart request as request parameters

**TCK targeted:**
- `PartTests.getPartTest`, `PartTests.getPartsTest1`;
- `Part1Tests.getPartTest`, `Part1Tests.getPartsTest1`.

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java` (`parameters()`, `parts()`, new `mergePartFields()`/`fieldFallbackCharset()`)
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/bridge/MultipartParametersTest.java` (create)

**Interfaces:**
- Consumes:
  - Task 7.2: `parameters()`, `readFormBody()`, `formBodyCharset()`.
  - Task 7.6: `parts()`, `parsedParts`, `partsFailure`, `isMultipart()`, `multipartConfig()`.
  - Task 7.4: `PartImpl.text(Charset)`, `PartImpl.charset(Charset)`.
  - Task 7.2 (test): `RequestBodyStateTest.FakeRequest`.
- Produces, in `HttpServletRequestImpl`:
  - `private boolean partFieldsMerged`;
  - `private void mergePartFields() throws IOException`;
  - `private Charset fieldFallbackCharset() throws IOException`.

- [ ] **Step 1: Write the failing tests.**

`foy-core/src/test/java/io/vidocq/foy/internal/bridge/MultipartParametersTest.java`:

```java
package io.vidocq.foy.internal.bridge;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.http.MultipartLimitException;
import io.vidocq.foy.internal.http.RequestLimits;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Section 3.2: a part without a file name is also a request parameter, after the query string. */
class MultipartParametersTest {

    private static final String B = "PARAMS-BOUNDARY";
    private static final MultipartConfigElement CONFIG = new MultipartConfigElement("");

    @TempDir Path temp;
    private final VidocqServletContext ctx = new VidocqServletContext("/");

    @BeforeEach
    void setUp() { ctx.setAttribute(ServletContext.TEMPDIR, temp.toFile()); }

    private HttpServletRequestImpl request(HttpMethod method, String query, MultipartConfigElement config, String body) {
        if (config != null && ctx.getServletRegistration("U") == null) {
            ctx.addServlet("U", new HttpServlet() {}).setMultipartConfig(config);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        var headers = Headers.builder().add("Host", "localhost")
                .add("Content-Type", "multipart/form-data; boundary=" + B)
                .add("Content-Length", Integer.toString(bytes.length)).build();
        var req = new HttpServletRequestImpl(new RequestBodyStateTest.FakeRequest(method, query, headers,
                Body.of(new ByteArrayInputStream(bytes))), ctx, "/", "/f", null);
        req.enterServlet("U");
        return req;
    }

    private static String field(String name, String value) {
        return "--" + B + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
    }

    private static String typedField(String name, String contentType, String value) {
        return "--" + B + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\nContent-Type: "
                + contentType + "\r\n\r\n" + value + "\r\n";
    }

    private static String file(String name, String fileName, String value) {
        return "--" + B + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName
                + "\"\r\n\r\n" + value + "\r\n";
    }

    private static String end() { return "--" + B + "--\r\n"; }

    @Test
    void fieldsBecomeParametersAfterTheQueryString() {
        var req = request(HttpMethod.POST, "a=q", CONFIG, field("a", "f") + field("b", "g") + file("c", "c.txt", "file") + end());
        assertArrayEquals(new String[] {"q", "f"}, req.getParameterValues("a"));
        assertEquals("g", req.getParameter("b"));
        assertNull(req.getParameter("c"), "a file part is not a parameter");
        assertEquals(List.of("a", "b"), Collections.list(req.getParameterNames()));
        req.releaseParts();
    }

    @Test
    void theFieldCharsetIsThePartsThenTheRequestsThenTheCharsetFieldThenIso88591() throws Exception {
        String e = "\u00e9"; // sent as UTF-8 bytes
        var none = request(HttpMethod.POST, null, CONFIG,
                typedField("declared", "text/plain; charset=UTF-8", e) + field("plain", e) + end());
        assertEquals(e, none.getParameter("declared"), "the part's own charset");
        assertEquals("\u00c3\u00a9", none.getParameter("plain"), "ISO-8859-1 by default (section 3.12)");

        var charsetField = request(HttpMethod.POST, null, CONFIG, field("_charset_", "UTF-8") + field("plain", e) + end());
        assertEquals(e, charsetField.getParameter("plain"), "_charset_ (RFC 7578 section 4.6)");

        var encoding = request(HttpMethod.POST, null, CONFIG, field("_charset_", "ISO-8859-1") + field("plain", e) + end());
        encoding.setCharacterEncoding("UTF-8");
        assertEquals(e, encoding.getParameter("plain"), "the request encoding wins over _charset_");
    }

    @Test
    void withoutAMultipartConfigTheBodyGivesNoParameter() {
        var req = request(HttpMethod.POST, "q=1", null, field("a", "1") + end());
        assertEquals("1", req.getParameter("q"));
        assertNull(req.getParameter("a"));
        assertThrows(IllegalStateException.class, req::getParts);
    }

    @Test
    void getParameterAndGetPartsShareOneParse() throws Exception {
        var req = request(HttpMethod.POST, null, CONFIG, field("a", "1") + file("f", "f.txt", "x") + end());
        assertEquals("1", req.getParameter("a"));
        assertEquals(2, req.getParts().size());
        assertEquals(-1, req.getInputStream().read(), "the body belongs to the container");
        req.releaseParts();
    }

    @Test
    void aPutIsParsedForParametersOnlyOnceGetPartsRan() throws Exception {
        var req = request(HttpMethod.PUT, null, CONFIG, field("a", "1") + end());
        assertNull(req.getParameter("a"), "section 3.1.1: only a POST body feeds the parameters by itself");
        assertEquals(1, req.getParts().size());
        assertEquals("1", req.getParameter("a"), "the parsed fields are merged");
    }

    @Test
    void parametersAfterGetInputStreamIgnoreTheBody() throws Exception {
        var req = request(HttpMethod.POST, "q=1", CONFIG, field("a", "1") + end());
        req.getInputStream();
        assertNull(req.getParameter("a"));
        assertEquals("1", req.getParameter("q"));
    }

    @Test
    void aFailedParseThrowsOnceFromGetParameterAndAlwaysFromGetParts() {
        ctx.setRequestLimits(RequestLimits.DEFAULTS.withMaxMultipartParts(1));
        var req = request(HttpMethod.POST, "q=1", CONFIG, field("a", "1") + field("b", "2") + end());
        var first = assertThrows(MultipartLimitException.class, () -> req.getParameter("a"));
        assertEquals("1", req.getParameter("q"));
        assertNull(req.getParameter("a"));
        assertSame(first, assertThrows(MultipartLimitException.class, req::getParts));
    }

    @Test
    void parsingMultipartParametersLocksTheEncoding() throws Exception {
        var req = request(HttpMethod.POST, null, CONFIG, field("a", "1") + end());
        req.getParameter("a");
        req.setCharacterEncoding("UTF-8");
        assertNull(req.getCharacterEncoding());
    }
}
```

- [ ] **Step 2: Run them to see them fail.**
  - Run: `./mvnw -ntp -pl foy-core test -Dtest=MultipartParametersTest`
  - Expected: FAIL in:
    - `fieldsBecomeParametersAfterTheQueryString`, `theFieldCharset...`, `getParameterAndGetPartsShareOneParse`, `aPutIsParsedForParametersOnlyOnceGetPartsRan`, because the fields are `null`;
    - `aFailedParseThrowsOnce...`, because nothing is thrown;
    - `parsingMultipartParametersLocksTheEncoding`, because the encoding becomes `UTF-8`.

- [ ] **Step 3: Merge the fields.** In `HttpServletRequestImpl`:
1. Replace `parameters()` with the version below.
2. In `parts()`, replace the final `return parsedParts;` with:
   ```java
           if (parsedParams != null) mergePartFields();
           return parsedParts;
   ```
3. Add the field and the two methods after `parts()`.

```java
    /**
     * The request parameters: the query string first, then the body (section 3.1.1). The map is
     * settled before the body is read, so a refused or failed body read propagates once and leaves
     * the query-string parameters; the body is never read a second time. A multipart body feeds them
     * when it is a POST to a servlet with a multipart configuration, or once getParts() parsed it.
     */
    private Map<String, List<String>> parameters() {
        if (parsedParams != null) return parsedParams;
        var out = new java.util.LinkedHashMap<String, List<String>>();
        decodeInto(out, chappe.query(), StandardCharsets.UTF_8);
        parsedParams = out;
        try {
            if (parsedParts != null) {
                mergePartFields();
            } else if (inputStream == null && reader == null && !bodyConsumedByContainer) {
                if (isFormPost()) {
                    decodeInto(out, readFormBody(), formBodyCharset());
                } else if (partsFailure == null && "POST".equalsIgnoreCase(getMethod()) && isMultipart()
                        && multipartConfig() != null) {
                    parts(); // merges the fields into parsedParams
                }
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("cannot read the multipart body", e);
        } catch (ServletException e) {
            throw new IllegalStateException(e); // unreachable: the media type was checked
        }
        return out;
    }

    private boolean partFieldsMerged;

    /** Section 3.2: the parts without a file name are also parameters, after the query string. Once. */
    private void mergePartFields() throws IOException {
        if (partFieldsMerged) return;
        partFieldsMerged = true;
        Charset fallback = fieldFallbackCharset();
        var fields = new java.util.LinkedHashMap<String, List<String>>();
        for (PartImpl p : parsedParts) {
            if (p.getSubmittedFileName() != null) continue;
            fields.computeIfAbsent(p.getName(), _ -> new ArrayList<>()).add(p.text(p.charset(fallback)));
        }
        fields.forEach((k, v) -> parsedParams.computeIfAbsent(k, _ -> new ArrayList<>()).addAll(v));
    }

    /**
     * The charset of a field whose part declares none: the request encoding (setCharacterEncoding,
     * the Content-Type charset, request-character-encoding), else the {@code _charset_} field
     * (RFC 7578 section 4.6), else ISO-8859-1 (section 3.12).
     */
    private Charset fieldFallbackCharset() throws IOException {
        String encoding = getCharacterEncoding();
        if (encoding != null) {
            try {
                return Charset.forName(encoding);
            } catch (RuntimeException unknown) {
                // fall through to the next source
            }
        }
        for (PartImpl p : parsedParts) {
            if (!"_charset_".equals(p.getName()) || p.getSubmittedFileName() != null) continue;
            try {
                return Charset.forName(p.text(StandardCharsets.US_ASCII).trim());
            } catch (RuntimeException unknown) {
                break;
            }
        }
        return StandardCharsets.ISO_8859_1;
    }
```

- [ ] **Step 4: Run the tests to see them pass.**
  - Run: `./mvnw -ntp -pl foy-core test -Dtest='MultipartParametersTest,RequestBodyStateTest,ServletMultipartEndToEndTest'`
  - Expected: PASS.

- [ ] **Step 5: Run the gates and the TCK.**
  1. The build gate.
  2. `./mvnw -ntp -Ptck -pl foy-tck test`.
  3. `./run-official-tck-servlet6.1.sh --no-install -Dtest='PartTests,Part1Tests' > /tmp/foy-tck-p7-7.log 2>&1`, tallied. Expected:
     - `servlet.tck.api.jakarta_servlet_http.part.PartTests 7 0 0`
     - `servlet.tck.api.jakarta_servlet_http.part1.Part1Tests 7 0 0`
  4. `./run-official-tck-servlet6.1.sh --no-install --family api > /tmp/foy-tck-p7-7-api.log 2>&1`, tallied and joined. No `REGRESSION` line, and `reflection=0`.
  5. Expected `api.*`: 856/859 (857/860 with `GetServletRegistrationsTest`).

- [ ] **Step 6: Commit.**

```bash
git add foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java \
  foy-core/src/test/java/io/vidocq/foy/internal/bridge/MultipartParametersTest.java
git commit -S -s -m "feat(core): expose multipart form fields as request parameters" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" \
  -m "Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi"
```

---

### Task 7.8: Servlet API signature test behind `-Psigtest`

**Files:**
- Modify: `foy-tck/pom.xml` (new profile after `tck-official`)
- Modify: `foy-tck/README.md` (~148, the "not yet connected" paragraph)

**Interfaces:**
- Consumes: the `tck-official` profile (it scans `jakarta.tck:servlet-tck-runtime`, which ships `servlet/tck/signature/ServletSigTest.class`, `jakarta.servlet.sig_6.1`, `sig-test.map` and `sig-test-pkg-list.txt`).
- Produces: `./mvnw -ntp -Ptck,tck-official,sigtest -pl foy-tck test -Dtest=ServletSigTest -Dsurefire.failIfNoSpecifiedTests=false`. Task 7.9 runs it and records the result.

`ServletSigTest` reads two system properties:
- `sigTestClasspath`: the API jar plus the JDK modules `jimage` extracts;
- `jimage.dir`.

It needs `com.sun.tdk.signaturetest` on the test classpath, which `jakarta.tck:sigtest-maven-plugin:2.5` provides (as in `cassini-tck/pom.xml`).

- [ ] **Step 1: Show the gap.**
  - Run: `./mvnw -ntp -Ptck,tck-official -pl foy-tck test -Dtest=ServletSigTest -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: `ServletSigTest` errors with `NoClassDefFoundError` (`com/sun/tdk/signaturetest/...`) or reports no API jar on its classpath. Keep the output for the TCK.md entry.

- [ ] **Step 2: Add the profile.** In `foy-tck/pom.xml`, inside `<profiles>` after the `tck-official` profile:

```xml
        <profile>
            <!-- API signature test (the TCK's own ServletSigTest, signature file jakarta.servlet.sig_6.1)
                 of the jakarta.servlet-api jar Foy runs on. Combine with tck-official:
                 ./mvnw -ntp -Ptck,tck-official,sigtest -pl foy-tck test -Dtest=ServletSigTest
                        -Dsurefire.failIfNoSpecifiedTests=false
                 Build-time only: sigtest-maven-plugin (test scope) and maven-dependency-plugin. -->
            <id>sigtest</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-dependency-plugin</artifactId>
                        <version>3.8.1</version>
                        <executions>
                            <execution>
                                <id>copy-servlet-api-for-sigtest</id>
                                <phase>process-test-resources</phase>
                                <goals><goal>copy</goal></goals>
                                <configuration>
                                    <artifactItems>
                                        <artifactItem>
                                            <groupId>jakarta.servlet</groupId>
                                            <artifactId>jakarta.servlet-api</artifactId>
                                            <version>6.1.0</version>
                                            <destFileName>jakarta.servlet-api.jar</destFileName>
                                        </artifactItem>
                                    </artifactItems>
                                    <outputDirectory>${project.build.directory}/sigtest</outputDirectory>
                                </configuration>
                            </execution>
                        </executions>
                    </plugin>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <configuration>
                            <systemPropertyVariables>
                                <jimage.dir>${project.build.directory}/jimage</jimage.dir>
                                <sigTestClasspath>${project.build.directory}/sigtest/jakarta.servlet-api.jar${path.separator}${project.build.directory}/jimage/java.base</sigTestClasspath>
                            </systemPropertyVariables>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
            <dependencies>
                <dependency>
                    <groupId>jakarta.tck</groupId>
                    <artifactId>sigtest-maven-plugin</artifactId>
                    <version>2.5</version>
                    <scope>test</scope>
                </dependency>
            </dependencies>
        </profile>
```

- [ ] **Step 3: Run it.**
  - Run: `./mvnw -ntp -Ptck,tck-official,sigtest -pl foy-tck test -Dtest=ServletSigTest -Dsurefire.failIfNoSpecifiedTests=false > /tmp/foy-sigtest.log 2>&1`
  - Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in servlet.tck.signature.ServletSigTest`.
  - If it fails on signature differences (for example JDK 25 `java.base` members inherited by the API types), go on:
    1. Copy the diff section of the log (lines after `SigTest.test1() failed!`) into a new BUG.md entry (next free id, status `OPEN`).
    2. Record the outcome in Task 7.9.

    A configuration error (`NoClassDefFoundError`, an empty classpath) is not a result: fix the profile.

- [ ] **Step 4: Document it.** In `foy-tck/README.md`, replace the paragraph "Signature tests `sigtest-maven-plugin` not yet connected — to add in a `-Psigtest` profile if needed to certify API conformance." with:

```markdown
**Signature test.** The `sigtest` profile runs the TCK's own `servlet.tck.signature.ServletSigTest`
(signature file `jakarta.servlet.sig_6.1` from `servlet-tck-runtime`) against the
`jakarta.servlet-api` 6.1.0 jar Foy runs on, plus the JDK `java.base` module extracted with `jimage`
into `target/jimage`:

    ./mvnw -ntp -Ptck,tck-official,sigtest -pl foy-tck test -Dtest=ServletSigTest \
        -Dsurefire.failIfNoSpecifiedTests=false

Build-time dependencies of the profile only: `jakarta.tck:sigtest-maven-plugin:2.5` (test scope, it
provides `com.sun.tdk.signaturetest`) and `maven-dependency-plugin` (copies the API jar). The latest
result is in `TCK.md`.
```

- [ ] **Step 5: Run the build gate.** The profile is inactive by default, so `./mvnw -ntp install` must not change.

- [ ] **Step 6: Commit.**

```bash
git add foy-tck/pom.xml foy-tck/README.md
git commit -S -s -m "build(tck): run the Servlet API signature test behind the sigtest profile" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" \
  -m "Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi"
```

---

### Task 7.9: Phase exit — full TCK, baseline, signature result, documentation, follow-ups

**Files:**
- Modify: `foy-tck/tck-baseline.txt` (refresh from the full run)
- Modify: `TCK.md`: a new `## 0. Phase 7 exit: multipart and request body limits (<date>)` above the Phase 6 one. The existing `0.`–`0.x` headings shift by one, so Phase 6 becomes `## 0.1`, as the earlier exits did.
- Modify: `docs/superpowers/plans/2026-10-07-servlet-completion-roadmap.md`:
  - "Phase 7 — Finishing": what shipped, what is out (CDI -07/-08, certification decision);
  - a new "Phase 7 exit / follow-ups" section after Phase 6's.
- Modify: `docs/en/modules/ROOT/pages/{usage,concepts,internals,reference,tck,migration,whats-new}.adoc`
- Modify: `README.md` (tally, multipart wording)
- Modify: `BUG.md` (entries below). Also mark BUG-20261011-10 (FoyVaubanBootstrap offers no way to configure an IdentityStore) **FIXED**, resolved on the Vidocq side by vidocq#222 (merged 2026-10-11, closes vidocq#220): the Vidocq Servlet extension passes an IdentityStore bean or `vidocq.servlet.users.*` to `FoyChappeBoot`.

- [ ] **Step 1: Run the builds.** The build gate and `./mvnw -ntp -Ptck -pl foy-tck test`.

- [ ] **Step 2: Run the full TCK.**
  1. `./run-official-tck-servlet6.1.sh --all > /tmp/foy-tck-phase7.log 2>&1`. It takes about 11 minutes: run it in the background and poll for `BUILD`.
  2. `./run-official-tck-servlet6.1.sh --no-install --family api > /tmp/foy-tck-phase7-api.log 2>&1`, for `GetServletRegistrationsTest`, which only runs in family mode.
  3. Tally and check:

```bash
bash foy-tck/tck-tally.sh /tmp/foy-tck-phase7.log > /tmp/tally-phase7.txt
LC_ALL=C join foy-tck/tck-baseline.txt /tmp/tally-phase7.txt \
  | awk '$5 != $2 || $6 > $3 || $7 > $4 || $5-$6-$7 < $2-$3-$4 {print "REGRESSION", $0}'   # must print nothing
LC_ALL=C join -v1 foy-tck/tck-baseline.txt /tmp/tally-phase7.txt                           # must print nothing
LC_ALL=C join -v2 foy-tck/tck-baseline.txt /tmp/tally-phase7.txt                           # must print nothing
awk '{r+=$2; b+=$3; s+=$4} END {print r - b - s " passing / " r " run, " s " skipped"}' /tmp/tally-phase7.txt
grep 'tiers=Stats' /tmp/foy-tck-phase7.log | grep -v 'reflection=0'                       # must print nothing
```

  4. Expected: **1700 passing / 1714 run, 7 skipped**, 7 bad (the sum of the third column). The two part lines read:
     - `servlet.tck.api.jakarta_servlet_http.part.PartTests 7 0 0`
     - `servlet.tck.api.jakarta_servlet_http.part1.Part1Tests 7 0 0`

     Every other line equals its line in `foy-tck/tck-baseline.txt` at main 8f5a768. The 7 bad are:
     - `ClientCertTests 1 1 0`;
     - `ClientCertAnnoTests 1 1 0`;
     - `SecFormTests 25 2 0`;
     - `ServletContext40Tests 12 3 0`.
  5. Per family:
     - `api.*` 856/859 (857/860 in family mode);
     - `pluggability.*` 646/646;
     - `spec.*` 196/207 with 7 skipped;
     - `compat.*` 2/2.
  6. Any other figure is investigated before going on. A new failure is a regression, not a gap.

- [ ] **Step 3: Run the signature test.**
  - Run: `./mvnw -ntp -Ptck,tck-official,sigtest -pl foy-tck test -Dtest=ServletSigTest -Dsurefire.failIfNoSpecifiedTests=false > /tmp/foy-sigtest-phase7.log 2>&1`
  - Keep the summary line and, on failure, the diff section.

- [ ] **Step 4: Refresh the baseline.** Run `cp /tmp/tally-phase7.txt foy-tck/tck-baseline.txt`, then commit with the message `build(tck): refresh the baseline to the Phase 7 exit tally` (signed, with the trailers).

- [ ] **Step 5: Write the documentation.** English only; say "Java Modules", never the abbreviation.

  **`TCK.md` §0:**
  - Date, branch at its commit, figures overall and per family, and the surefire summary.
  - The join result: zero regression, `join -v` empty, 2 classes improved (8 tests), `reflection=0`.
  - The signature test result: the summary line, and the diffs with their BUG id if it failed.
  - The remaining gaps:
    - CLIENT-CERT 2 (BUG-20261011-03);
    - JSP 2 + 3 (BUG-20261011-04, the `ServletContext40Tests` trio);
    - HTTP/2 push 7 skipped.

  **`usage.adoc`, section "File upload (`@MultipartConfig`)":**
  - Replace the "fully buffered in memory … Keep uploads small" paragraph with how it works now:
    - a configuration is required: `@MultipartConfig`, `<multipart-config>` or `setMultipartConfig`;
    - `location` (empty: the context temp dir; relative: under it; absolute: as is);
    - `fileSizeThreshold` (0: every non-empty part on disk);
    - `maxFileSize`/`maxRequestSize` (`IllegalStateException`);
    - the files are deleted at the end of the request, async included.
  - `Part.write`: relative to the location, `..` refused, absolute as provided; the moved file is the application's.
  - `getSubmittedFileName()` is raw: sanitise it, with an example that keeps only `Path.of(name).getFileName()` after replacing `\` with `/`.
  - Form fields as parameters and their charset order.
  - The `FoyChappeBoot` limits, with an example: `FoyChappeBoot.builder().maxMultipartParts(20).maxFormBodySize(512 * 1024)`.

  **`concepts.adoc:38`:** the Multipart row says "streamed (RFC 7578), memory up to `fileSizeThreshold` then owner-only temporary files, deleted at the end of the request".

  **`internals.adoc`:**
  - A "Request body ownership" subsection:
    - one owner (application stream or reader, or container form or parts);
    - the tracked stream;
    - `bodySource()` and `maxChunkedRequestBodySize`;
    - the parameters settled before the body read;
    - the failure cached for parts and thrown once for parameters;
    - the encoding lock on body parse.
  - A "Multipart" subsection:
    - the KMP delimiter scan;
    - `PartSink`/`PartStorage`;
    - the configuration frozen at `markInitialized` and resolved for the executing servlet (`enterServlet`), which a forward changes;
    - the cleanup in `runPipeline`'s `finally`.
  - Replace the "Known limits" bullet at line 274 with:
    - multipart body parameters are decided by the servlet executing when the parameters are first read;
    - the epilogue is left to chappe's drain;
    - no aggregate in-memory cap beyond the body size.

  **`reference.adoc`:**
  - In "Public API: `FoyChappeBoot`", the four limit setters with defaults, `-1` semantics and failure types: a table (setting, default, applies to, exceeded →).
  - Rows 270/272: unchanged wording plus "`<multipart-config>` reaches the runtime".
  - Row 464: Multipart ✅ with the notes.
  - The exceptions `MultipartLimitException` (an `IllegalStateException`) and `MalformedMultipartException` (an `IOException`): internal types, catch the specification types.
  - The `sigtest` build tooling: `sigtest-maven-plugin` 2.5 and `maven-dependency-plugin`, test or build scope only.

  **`tck.adoc`:**
  - the summary at 1700/1714, 99.2 %;
  - `api.*` 856/859, 99.7 %;
  - the remaining 7;
  - the signature test result;
  - the multipart wording removed from "the rest is…".

  **`migration.adoc`:**
  - Line 9 tally: 1700/1714, 99.2 %, with the gaps CLIENT-CERT and JSP.
  - In `== Known gaps`, delete the Multipart bullet (line 108).
  - Add `=== Request bodies and multipart` to the "Behaviour changes for `FoyChappeBoot` embedders" section, after `=== Security` and before `== Common pitfalls`, with the same one-line lead sentence as its siblings and these bullets:
    - `getParts`/`getPart` now require a multipart configuration (`IllegalStateException` without one; this used to parse anyway) and throw `ServletException` for a non-multipart request (this used to return no part).
    - Multipart fields are request parameters (POST, or once `getParts` ran).
    - `Part.write` resolves against the location, no longer the working directory. `Part.delete` deletes.
    - Parts above the threshold go to disk (default threshold 0).
    - Form bodies above 2 MiB make `getParameter` throw `IllegalStateException` (`maxFormBodySize`).
    - Bodies without `Content-Length` above 10 MiB fail the read (`maxChunkedRequestBodySize`).
    - At most 50 parts and 8 KiB of headers per part.
    - `setCharacterEncoding` after a body was parsed into parameters is ignored.
    - `setMultipartConfig(null)` throws `IllegalArgumentException`, and so does any call after initialisation.

  **`whats-new.adoc`:** a Phase 7 entry.

  **`README.md`:**
  - line 47: the tally (1700/1714, 99.2 %, per family);
  - line 57: the remaining gaps are JSP (3, plus 2 FORM tests) and CLIENT-CERT (2).

  **Roadmap "Phase 7 exit / follow-ups":**
  - shipped items, figures, the signature result;
  - remaining failures by phase (CLIENT-CERT 2 deferred, JSP 2 + 3, push 7 skipped);
  - open bugs: still open, plus the new ones below;
  - out of scope and carried over: CDI BUG-20261010-07/-08 and the certification decision;
  - in "Phase 1 follow-ups", mark the Phase 7 line done with this phase's commits.

  Deferred minors to list:
  - no aggregate in-memory cap beyond the body size;
  - the multipart epilogue is drained by chappe;
  - `Part.write` checks a relative name lexically (`normalize`), not through `toRealPath`: a symlink the application placed inside the location can still point outside it;
  - `getParameter` on a multipart request outside any servlet scope (a filter of an unmapped path) sees no body parameters;
  - `PartImpl.text` reads a whole field into memory (bounded by `maxFileSize`);
  - a FORM `j_security_check` sent as multipart gets no credentials.

  **`BUG.md`:** entries numbered per day as the file does. On 2026-10-11 the next id is BUG-20261011-11; on a later day D, BUG-D-01 (or after the last entry of day D).
  - **Chappe drains an over-cap body without bound.** Symptom: once `maxChunkedRequestBodySize` trips, Foy stops reading, but chappe's `HttpBodyReader.drain` still reads the rest of a chunked body before the next request or the close. Memory stays bounded, but the connection is held. Minimal repro: `ChunkedBodyLimitEndToEndTest` with an endless `ofInputStream` publisher. Cause: chappe caps only `Content-Length` bodies. Status `OPEN` (chappe follow-up: cap chunked bodies at `maxRequestSize` and close instead of draining).
  - The signature test outcome when it failed in Task 7.8/7.9 (if not already filed).

- [ ] **Step 6: Commit.**

```bash
git add TCK.md README.md BUG.md docs/superpowers/plans/2026-10-07-servlet-completion-roadmap.md \
  docs/en/modules/ROOT/pages/usage.adoc docs/en/modules/ROOT/pages/concepts.adoc \
  docs/en/modules/ROOT/pages/internals.adoc docs/en/modules/ROOT/pages/reference.adoc \
  docs/en/modules/ROOT/pages/tck.adoc docs/en/modules/ROOT/pages/migration.adoc \
  docs/en/modules/ROOT/pages/whats-new.adoc
git commit -S -s -m "docs: document multipart, request body limits and the signature test (Phase 7 exit)" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" \
  -m "Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi"
```

---

## Self-review

**Spec coverage:**

| Requirement | Task |
|---|---|
| `@MultipartConfig` / `<multipart-config>` / `setMultipartConfig` (IAE, ISE) reach the request | 7.3, 7.6 |
| `getParts`/`getPart`: `ServletException` (not multipart), ISE (no configuration, limits, body taken), `null` for a missing name | 7.6 |
| Streaming RFC 7578 parser (CRLF-anchored, quoting, UTF-8, `filename*`, repeated headers, truncation is an error) | 7.5 |
| `maxFileSize`/`maxRequestSize` while streaming, 50 parts, 8 KiB part headers | 7.5 (+ `Content-Length` pre-check in 7.6) |
| Threshold spill, 0600 random temporary files, location under TEMPDIR | 7.4 |
| `Part.write` (relative under location, no `..` escape, absolute as is), `Part.delete` | 7.4 |
| Cleanup at the end of the request and the async cycle, and on a failed parse | 7.6, 7.5 |
| Fields as parameters, query first, charset order | 7.7 |
| Body through the tracked stream, mutual exclusion, keep-alive | 7.2, 7.6, 7.7 |
| Form body cap, encoding lock, failed-read state | 7.2 |
| Foy-side cap on bodies without `Content-Length` | 7.1 |
| Limits configurable on `FoyChappeBoot` and `DeployOptions` | 7.1 |
| `getPartsReturnsEmptyWhenNotMultipart` rewritten | 7.6 (`getPartsOnANonMultipartRequestThrowsServletException`) |
| Sigtest profile and result | 7.8, 7.9 |
| Exit gates and docs | 7.9 |

**Placeholders:** none. Two code steps are deliberately interim:
- Task 7.4 Step 7 keeps the old parser compiling;
- Task 7.5 Step 4 keeps the old request call.

Both are replaced by named later steps (7.5 Step 3, 7.6 Step 3).

**Type consistency:** these names are used identically across tasks:
- `RequestLimits` (`maxChunkedRequestBodySize`, `maxFormBodySize`, `maxMultipartParts`, `maxMultipartPartHeaderSize`, `with*`, `DEFAULTS`), `BoundedInputStream`, `RequestBodyTooLargeException.limit()`;
- `VidocqServletContext.setRequestLimits/requestLimits/multipartConfig`, `DeployOptions.requestLimits/withRequestLimits`;
- `PartStorage(Path)`, `resolveLocation(String, File)`, `createTempFile`, `disown`, `discard`, `writeTarget`, `close`;
- `PartSink(PartStorage, int, long)`, `write`, `finish`, `close`;
- `PartImpl.Header`, `inMemory`, `onDisk`, `text(Charset)`, `charset(Charset)`, `discard()`;
- `MultipartParser.Limits(maxFileSize, maxRequestSize, fileSizeThreshold, maxParts, maxPartHeaderSize)`, `Limits.of`, `extractBoundary`, `parse(InputStream, String, Limits, PartStorage)`;
- `MultipartLimitException`, `MalformedMultipartException`;
- in `HttpServletRequestImpl`: `limits()`, `bodySource()`, `containerBody()`, `readFormBody()`, `bodyConsumedByContainer`, `parts()`, `parsedParts`, `partsFailure`, `isMultipart()`, `mediaType()`, `multipartConfig()`, `executingServletName()`, `releaseParts()`, `mergePartFields()`, `fieldFallbackCharset()`;
- `RequestBodyStateTest.FakeRequest`.

**Review Focus:** each of the five lines names tests in its owning task (7.1, 7.2, 7.4, 7.5, 7.6, 7.7).

**Known behaviour change outside multipart:** a dynamic servlet whose class misuses the Servlet annotations is now left out even when `setServletSecurity` was called, because its descriptor is read for `@MultipartConfig` (Task 7.3 Step 5). Such a class is already a compile error under `foy-processor`.
