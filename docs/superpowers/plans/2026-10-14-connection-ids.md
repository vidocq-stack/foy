# Connection and Protocol Request Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix BUG-20261009-09 by mapping the Chappe identities added in CHAPPE-035 (chappe#32, merged) to the Servlet 6.1 API:
- `ServletConnection.getConnectionId()` comes from `Request.connectionId()`;
- `ServletRequest.getProtocolRequestId()` comes from `Request.streamId()`.

As approved by the maintainer, `getProtocol()` also returns `"HTTP/2.0"` instead of `"HTTP/2"`.

**Architecture:** All the changes are in `foy-core`'s `io.vidocq.foy.internal.bridge` package:
- `HttpServletRequestImpl` reads the two Chappe `Optional`s;
- `getProtocol()` maps `HttpVersion` with an explicit switch;
- `HttpServletResponseImpl.setTrailerFields`, which compares the protocol string, follows the new value.

**Tech Stack:** Java 25, Java Modules, JUnit 5, `java.net.http.HttpClient`. The HTTP/2 end-to-end test runs over TLS with ALPN, since the JDK client cannot speak h2c with prior knowledge.

**Spec:**
- `BUG.md` BUG-20261009-09.
- Chappe CHAPPE-035:
  - `default OptionalLong connectionId()` is positive, unique for the JVM lifetime, and shared by every request of one connection. It is empty when unknown.
  - `default OptionalInt streamId()` is the HTTP/2 stream id. It is empty for HTTP/1.x.
- The Servlet 6.1 Javadoc:
  - `ServletConnection.getConnectionId()`: "unique (within the lifetime of the JVM) identifier … no defined format".
  - `ServletRequest.getProtocolRequestId()`: HTTP/1.x `""`; HTTP/2 "the stream identifier".
  - `ServletConnection.getProtocolConnectionId()`: HTTP/1.x and HTTP/2 `""`.
  - `ServletRequest.getProtocol()`: "the form *protocol/majorVersion.minorVersion*, for example, HTTP/1.1". Tomcat and Jetty return `HTTP/2.0`.

## Global Constraints

- **Language:** English for code, Javadoc, comments, commits and Markdown. Write "Java Modules", never "JPMS". The existing French comment in `getProtocol()` goes away with the rewrite.
- **Dependencies:** zero new dependencies.
- **TDD:** write the failing test first, run it, and see it fail.
- **Commits:**
  - `git commit -S -s`, Conventional Commits, explicit paths only.
  - Trailers: `Co-Authored-By: Claude <model> 5.5 <noreply@anthropic.com>`, then `Claude-Session: https://claude.ai/code/session_01GMHXRzzix2eKEoiro7nVgi`.
- **Worktree:** work in `/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq-foy/714bb865-5c9f-4aac-a489-970c2a1d362e/scratchpad/wt-foy-connid`, on branch `pr/ybl/foy-connection-ids`. Never use the main foy checkout.
- **Maven:** run offline against the private repository: `./mvnw -ntp -o -Dmaven.repo.local=/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq-foy/714bb865-5c9f-4aac-a489-970c2a1d362e/scratchpad/m2 -Dmaven.repo.local.tail=$HOME/.m2/repository ...`. That repository already holds chappe `0.4.0-SNAPSHOT` built from the merged `main` (f4c3f98), so it has the new `Request` methods. `~/.m2` may hold an older chappe, so never drop the flag.

## Rulings (made while planning; the maintainer approves them with the plan)

- **Connection id format:** the decimal value of `connectionId()`, e.g. `"42"`. When Chappe gives none, which happens for a `Request` that is not Chappe's own, such as a test fake or a custom `Request` implementation, the id falls back to today's socket-address pair. The two formats cannot collide, because the address pair always contains `:` or `?`. The fallback keeps today's limit, which is documented.
- **Protocol request id:** `Integer.toString(streamId)` when `streamId()` is present, else `""`. Chappe only sets it on HTTP/2, so it is not cross-checked against `version()`.
- **`getProtocolConnectionId()` stays `""`.** That is correct for HTTP/1.x and HTTP/2, and Chappe has no HTTP/3.
- **`getProtocol()`** maps through an explicit switch on `HttpVersion`: `HTTP_1_0` gives `"HTTP/1.0"`, `HTTP_1_1` gives `"HTTP/1.1"`, and `HTTP_2` gives `"HTTP/2.0"`. Chappe's own `HttpVersion` label stays `"HTTP/2"`, which is the wire form for HTTP/2.
- **Trailers:** `setTrailerFields` keeps its string comparison, which now uses `"HTTP/2.0"`. Its HTTP/2 branch, where a `Content-Length` is allowed, had no test and gets one.
- **Behaviour change:** applications that compare `getProtocol()` with `"HTTP/2"` must update. This goes in `migration.adoc` under "Behaviour changes in the next version".

## Review Focus

1. **Keep-alive and distinct connections, end to end.** Two requests on one HTTP/1.1 keep-alive connection report the same `getConnectionId()`. Two separate connections report different ids. Pin this with a real server, not only with the fake.
2. **HTTP/2 over TLS, end to end.** `getProtocol()` is exactly `HTTP/2.0`. `getProtocolRequestId()` is an odd positive decimal. Two sequential requests on one client share the connection id and have distinct protocol request ids.
3. **The trailer rule on HTTP/2.** A `Content-Length` with trailers is accepted on HTTP/2, but still refused on HTTP/1.1.
4. **The fallback.** A `Request` without `connectionId()` still gets a non-empty, address-based id.
5. **No stale assertion.** `TlsAttributesEndToEndTest` checks `protocol=HTTP/2` with `contains`, which also matches `HTTP/2.0`. Tighten it to the whole line.

---

### Task 1: Map the Chappe identities and the HTTP/2.0 protocol string (BUG-20261009-09)

**Files:**
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletRequestImpl.java`:
  - `getProtocol()` at ~:219-223;
  - `getProtocolRequestId()` and its Javadoc at ~:260-264;
  - `getServletConnection()` and its Javadoc at ~:1346-1366.
- Modify: `foy-core/src/main/java/io/vidocq/foy/internal/bridge/HttpServletResponseImpl.java:281`, where `"HTTP/2"` becomes `"HTTP/2.0"`.
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/bridge/RequestIdentityTest.java`
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/bridge/ResponseTrailerFieldsTest.java`
- Test: `foy-core/src/test/java/io/vidocq/foy/internal/TlsAttributesEndToEndTest.java`, or a new `foy-core/src/test/java/io/vidocq/foy/internal/ConnectionIdentityEndToEndTest.java` that reuses `TestServerLauncher` and `TlsTestCertificates` the way `TlsAttributesEndToEndTest` does. Prefer the new class if adding the tests would blur `TlsAttributesEndToEndTest`'s purpose.
- Modify:
  - `BUG.md`: BUG-20261009-09 becomes FIXED.
  - `docs/en/modules/ROOT/pages/reference.adoc`, section "Request identity and connection", ~:504-510.
  - `docs/en/modules/ROOT/pages/internals.adoc:291`.
  - `docs/en/modules/ROOT/pages/migration.adoc`, section "Behaviour changes in the next version", ~:211-217.

**Interfaces:**
- Consumes these Chappe `io.vidocq.chappe.api.Request` methods:
  - `OptionalLong connectionId()`;
  - `OptionalInt streamId()`;
  - `HttpVersion version()`, whose constants are `HTTP_1_0`, `HTTP_1_1` and `HTTP_2`.
- Produces no new API. The public Servlet methods change their values.

- [ ] **Step 1: Write the failing unit tests.**

  In `RequestIdentityTest`, give the `FakeRequest` record two more components, `OptionalLong connectionId` and `OptionalInt streamId`. Record accessors then override the `Request` defaults. Update the `request(...)` helper, and add an overload:

```java
record FakeRequest(HttpVersion version, boolean isSecure, InetSocketAddress remoteAddress,
                   InetSocketAddress localAddress, OptionalLong connectionId, OptionalInt streamId)
        implements Request { /* existing overrides unchanged */ }

private static HttpServletRequestImpl request(HttpVersion version, boolean secure, int remotePort) {
    return request(version, secure, remotePort, OptionalLong.empty(), OptionalInt.empty());
}

private static HttpServletRequestImpl request(HttpVersion version, boolean secure, int remotePort,
                                              OptionalLong connectionId, OptionalInt streamId) {
    var chappe = new FakeRequest(version, secure, new InetSocketAddress("127.0.0.1", remotePort), LOCAL,
            connectionId, streamId);
    return new HttpServletRequestImpl(chappe, new VidocqServletContext("/ctx"), "/ctx", "/x", null);
}
```

  Add these tests:

```java
@Test
void theConnectionIdIsChappesConnectionId() {
    var a = request(HttpVersion.HTTP_1_1, false, 40010, OptionalLong.of(42), OptionalInt.empty());
    var sameConnection = request(HttpVersion.HTTP_1_1, false, 40010, OptionalLong.of(42), OptionalInt.empty());
    // Same addresses, another connection: the address pair would collide, Chappe's id does not.
    var reusedPort = request(HttpVersion.HTTP_1_1, false, 40010, OptionalLong.of(43), OptionalInt.empty());
    assertEquals("42", a.getServletConnection().getConnectionId());
    assertEquals("42", sameConnection.getServletConnection().getConnectionId());
    assertEquals("43", reusedPort.getServletConnection().getConnectionId());
}

@Test
void withoutAChappeConnectionIdTheAddressPairIsUsed() {
    String id = request(HttpVersion.HTTP_1_1, false, 40011).getServletConnection().getConnectionId();
    assertEquals("127.0.0.1:40011-127.0.0.1:8080", id);
}

@Test
void protocolRequestIdIsTheHttp2StreamId() {
    var r = request(HttpVersion.HTTP_2, true, 40012, OptionalLong.of(7), OptionalInt.of(5));
    assertEquals("5", r.getProtocolRequestId());
    assertEquals("", r.getServletConnection().getProtocolConnectionId());
}

@Test
void protocolIsMajorDotMinor() {
    assertEquals("HTTP/1.0", request(HttpVersion.HTTP_1_0, false, 40013).getProtocol());
    assertEquals("HTTP/1.1", request(HttpVersion.HTTP_1_1, false, 40013).getProtocol());
    assertEquals("HTTP/2.0", request(HttpVersion.HTTP_2, true, 40013).getProtocol());
}
```

  The existing `servletConnectionReportsProtocolSecurityAndAStableConnectionId` keeps passing, because it uses the fallback.

  In `ResponseTrailerFieldsTest`, add tests for the HTTP/2 `Content-Length` branch. Build the bound request with `RequestIdentityTest.FakeRequest`, which is visible because it is in the same package and package-private. First read how the existing HTTP/1.x refusal is tested, then reuse its way of declaring a `Content-Length`: `setContentLength`, or `setContentLengthLong`.

```java
private static HttpServletRequestImpl boundTo(HttpVersion version) {
    var chappe = new RequestIdentityTest.FakeRequest(version, true,
            new InetSocketAddress("127.0.0.1", 40100), new InetSocketAddress("127.0.0.1", 8443),
            OptionalLong.empty(), OptionalInt.empty());
    return new HttpServletRequestImpl(chappe, new VidocqServletContext("/ctx"), "/ctx", "/x", null);
}

@Test
void http2AcceptsTrailersWithADeclaredContentLength() {
    var res = new HttpServletResponseImpl();
    res.bindRequest(boundTo(HttpVersion.HTTP_2));
    res.setContentLength(3);
    assertDoesNotThrow(() -> res.setTrailerFields(() -> Map.of("grpc-status", "0")));
}

@Test
void http11RefusesTrailersWithADeclaredContentLength() {
    var res = new HttpServletResponseImpl();
    res.bindRequest(boundTo(HttpVersion.HTTP_1_1));
    res.setContentLength(3);
    assertThrows(IllegalStateException.class, () -> res.setTrailerFields(() -> Map.of("x", "1")));
}
```

- [ ] **Step 2: Write the failing end-to-end tests.** Use a servlet that writes one line, `conn=<getServletConnection().getConnectionId()> preq=<getProtocolRequestId()> protocol=<getProtocol()>`.
  - `keepAliveRequestsShareTheConnectionIdAndSeparateConnectionsDoNot`:
    - start a plain server with `TestServerLauncher.start(...)`;
    - send two requests on one HTTP/1.1 `HttpClient`, which keeps the connection alive, and assert the two `conn=` values are equal and match `[0-9]+`;
    - send a third request on a second `HttpClient`, and assert its `conn=` differs;
    - assert `preq=` is empty and `protocol=HTTP/1.1` in all three.
  - `http2RequestsCarryTheirStreamIdAndShareTheConnectionId`:
    - start a TLS server as `TlsAttributesEndToEndTest` does;
    - send two sequential requests on one `HttpClient.Version.HTTP_2` client;
    - assert both have `protocol=HTTP/2.0`, so the client really negotiated h2;
    - assert both `conn=` values are equal decimals;
    - assert each `preq=` is an odd positive decimal, and the two differ.
  - In `TlsAttributesEndToEndTest.get(HttpClient.Version, SSLContext)`, make the protocol check exact: `"protocol=HTTP/2.0"` or `"protocol=HTTP/1.1"`, matched as a whole line. Use `body.lines().anyMatch(expected::equals)`.
- [ ] **Step 3: Run the tests to verify they fail.** Run `./mvnw <Maven flags> -pl foy-core test -Dtest='RequestIdentityTest,ResponseTrailerFieldsTest,TlsAttributesEndToEndTest,ConnectionIdentityEndToEndTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected:
  - the new identity tests fail, because the id is the address pair and the protocol request id is `""`;
  - `protocolIsMajorDotMinor` fails on `HTTP/2`;
  - `http2AcceptsTrailersWithADeclaredContentLength` PASSES with the old code, because both sides still say `"HTTP/2"`. It is the guard for the protocol change. In Step 4, change `getProtocol()` first and re-run it: it must now FAIL with `IllegalStateException`. Only then fix `HttpServletResponseImpl`. Record both runs in the report.
- [ ] **Step 4: Implement.**

```java
/** {@code protocol/major.minor}, as the Servlet Javadoc requires: HTTP/2 is {@code HTTP/2.0}. */
@Override public String getProtocol() {
    return switch (chappe.version()) {
        case HTTP_1_0 -> "HTTP/1.0";
        case HTTP_1_1 -> "HTTP/1.1";
        case HTTP_2 -> "HTTP/2.0";
    };
}

/** {@code ""} on HTTP/1.x; on HTTP/2, the stream identifier Chappe reports. */
@Override public String getProtocolRequestId() {
    var stream = chappe.streamId();
    return stream.isPresent() ? Integer.toString(stream.getAsInt()) : "";
}
```

  In `getServletConnection()`, take the id from `chappe.connectionId()` when it is present, as `Long.toString(...)`. Otherwise use the existing `endpoint(remote) + "-" + endpoint(local)`. Rewrite its Javadoc:
  - the id is Chappe's connection id, unique for the JVM lifetime and shared by every request of the connection;
  - a `Request` that carries none falls back to the socket-address pair, which a later connection may reuse;
  - `getProtocolConnectionId()` is `""`, which is correct for HTTP/1.x and HTTP/2.

  Keep the ALPN protocol switch as it is. In `HttpServletResponseImpl.setTrailerFields`, change `"HTTP/2"` to `"HTTP/2.0"`.
- [ ] **Step 5: Run the tests to verify they pass.** Run the four test classes, then `./mvnw <Maven flags> -pl foy-core test`. Then grep `foy-*/src` for remaining `"HTTP/2"` string literals that compare `getProtocol()`: there must be none.
- [ ] **Step 6: Commit** with `fix(bridge): report Chappe's connection id and the HTTP/2 stream id; HTTP/2 is HTTP/2.0 (BUG-20261009-09)`.
- [ ] **Step 7: Update the docs.**
  - `reference.adoc`, "Request identity and connection": replace the paragraph and its two limit bullets with the new behaviour:
    - the connection id is Chappe's JVM-unique connection counter, as a decimal string;
    - the address-pair fallback applies to a non-Chappe `Request`;
    - `getProtocolRequestId()` is `""` on HTTP/1.x and the stream id on HTTP/2;
    - `getProtocolConnectionId()` is `""`;
    - `getProtocol()` is `HTTP/1.0`, `HTTP/1.1` or `HTTP/2.0`.
  - `internals.adoc:291`: delete the limit bullet.
  - `migration.adoc`, "Behaviour changes in the next version":
    - make the intro sentence general, for example "These changes may need attention:", and keep the three wrapper bullets, prefixed by a short phrase that says they concern wrappers;
    - add `getProtocol()` returning `HTTP/2.0` on HTTP/2 (was `HTTP/2`);
    - add `getServletConnection().getConnectionId()` now being a decimal counter, no longer the address pair.
  - `BUG.md` BUG-20261009-09: set the status to `FIXED (<hash of step 6>)`, and add an investigation line dated 2026-10-14 naming chappe CHAPPE-035 and the mapping.
- [ ] **Step 8: Run the full build** from the worktree root: `./mvnw <Maven flags> install`. All tests must pass.
- [ ] **Step 9: Commit** with `docs: document the connection id, the HTTP/2 stream id and HTTP/2.0 (BUG-20261009-09)`.
