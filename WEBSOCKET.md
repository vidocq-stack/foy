# Jakarta WebSocket 2.2 in Foy — design

Status: lot 1 delivered on 2026-11-14 in Vidocq/chappe#83, Vidocq/foy#100 and Vidocq/vidocq#567.
It was accepted with Vaadin `@Push(transport = WEBSOCKET_XHR)`: 10 minutes idle, pushes received, a foreign
origin refused with 403. The design was approved on 2026-11-14. Tracking issue:
[Vidocq/vidocq#65](https://codefloe.com/Vidocq/vidocq/issues/65). Web Profile plan:
`vidocq/WEB-PROFILE.md` (WebSocket 2.2, target 1.1.0).

## 1. Intent

Foy implements Servlet 6.1 on Chappe. Chappe speaks RFC 6455, but no Vidocq brick
implements the Jakarta WebSocket API, so a web application cannot open a WebSocket
through the standard API.

The first consumer is a Vaadin Flow application (`VidocqTools/forge-dashboard`) running on
Vidocq 0.4.0-SNAPSHOT with `@Push`. Vaadin pushes through Atmosphere, and Atmosphere does
WebSocket only when the container publishes a `jakarta.websocket.server.ServerContainer`
as the `ServletContext` attribute of the same name. Foy publishes none today, so Vaadin
falls back to long polling, which the maintainer does not want.

The longer-term goal is Jakarta WebSocket 2.2 for the Web Profile 11 (1.1.0): annotated
endpoints generated at build time, encoders and decoders, the client API, and the
official TCK.

## 2. Goals, non-goals, anti-goals

Goals of the whole effort, delivered in three lots (§4):

- A Jakarta WebSocket 2.2 server implementation in the Foy reactor, on Chappe's RFC 6455
  support.
- A Vidocq runtime extension that brings it, and the Vaadin extension depending on it, so
  `@Push` uses WebSocket without the application adding anything.
- Lot 3 ends with the official Jakarta WebSocket 2.2 TCK passing.

Non-goals:

- WebSocket over HTTP/2 (RFC 8441 extended CONNECT). The handshake is HTTP/1.1 only, as
  Foy's `HttpServletRequest.upgrade()` already is.
- Compression extensions (`permessage-deflate`). Chappe has none; `getInstalledExtensions()`
  is empty.
- Running on another servlet container. The implementation depends on Foy (§3).

Anti-goals (a failure even if it works):

- A second RFC 6455 codec in Foy. Framing, masking, UTF-8 validation and the close
  handshake stay in Chappe, which is fuzzed and covered by the threat model.
- Runtime reflection outside Foy's documented fallbacks, platform threads, or
  `ThreadLocal` in what Foy ships (enforced by `foy-architecture-tests`).
- Foy's other modules learning about WebSocket beyond one transport hook (§5.2).

## 3. Approaches considered

1. **A module in the Foy reactor, on Chappe's WebSocket (chosen by the maintainer).**
   The spec's Servlet integration (the `ServerContainer` attribute, the `HttpSession` and
   the user principal of the handshake, security constraints on the handshake) is
   already Foy's; Foy's TCK harness is there; Chappe's hardened codec is reused; no new
   repository to bootstrap, so the first SNAPSHOT comes quickly. Cost: it runs on Foy
   only.
2. **A new brick on the portable `HttpServletRequest.upgrade()`**, parsing frames itself
   over `WebConnection`, as Tomcat does. Runs on any Servlet 6.1 container, but duplicates
   Chappe's codec and its security surface, and needs a new repository, a name and a CI
   bootstrap. Rejected for the anti-goal above.
3. **A new brick plus a Chappe engine adapter** (the `cassini-chappe` pattern). Keeps a
   dedicated repository and Chappe's codec, but is the most pieces to assemble, for
   portability nobody asked for. Rejected.

Runtime wiring, chosen by the maintainer: a dedicated extension,
`vidocq-runtime-foy-websocket-extension`, like the Ibarra and Bodoni extensions, so a
servlet application without WebSocket does not load the `jakarta.websocket` modules. The
existing `vidocq-runtime-vaadin-extension` depends on it. The alternative, the servlet
extension always bringing WebSocket as Tomcat and Jetty do, was declined.

## 4. Lots

| Lot | Content | Exit |
|---|---|---|
| 1 | The programmatic server API Atmosphere uses, with the Chappe changes of §5.7; the runtime extension; a SNAPSHOT on maven.vidocq.dev | Vaadin `@Push(transport = WEBSOCKET_XHR)` in `vidocq:run`: a 101 on `/VAADIN/push`, the page stays connected more than 5 minutes without activity, then receives a push |
| 2 | `@ServerEndpoint` endpoints generated at build time (APT) and at deployment (Class-File API) for jars compiled without the processor; encoders and decoders; `@PathParam`; CDI injection in endpoints under Vidocq | to be designed after lot 1 |
| 3 | The client API (likely on `java.net.http.WebSocket`); what `chappe-api` must gain, if anything (§7); the official TCK runner under `-Ptck` and its convergence | official TCK green |

This document designs lot 1 in full. Lots 2 and 3 get their own design sections when they
start.

## 5. Lot 1 design

### 5.1 What Atmosphere needs (verified)

Vaadin 25.3.2 pulls `com.vaadin.external.atmosphere:atmosphere-runtime:3.0.5.slf4jvaadin1`
(its `-jpms` variant is the automatic module `com.vaadin.external.atmosphere.runtime`; it
reads `jakarta.websocket` once that module is in the boot layer). Read from its sources
(`JSR356AsyncSupport`, `JSR356Endpoint`, `JSR356WebSocket`):

- `ServletContext.getAttribute("jakarta.websocket.server.ServerContainer")`: null makes
  Atmosphere throw and fall back to long polling.
- `ServerContainer.addEndpoint(ServerEndpointConfig)` with `ServerEndpointConfig.Builder`,
  for `servletPath`, `servletPath/` and the URI templates `servletPath/{path0}` up to
  `servletPath/{path0}/…/{path4}`. With Vaadin mapped on `/*`, the servlet path is empty
  and `/VAADIN/push` matches `/{path0}/{path1}`. `getInstalledExtensions()` is passed back
  to `extensions(...)`.
- Atmosphere's own `Configurator`: `getEndpointInstance(Class)` and
  `modifyHandshake(...)` are paired through a `ThreadLocal`, so **both must run on the same
  thread, for the same handshake**. Either order works.
- `ServiceLoader.load(ServerEndpointConfig.Configurator.class)`: Atmosphere only checks
  that a container default configurator exists, and the API's `Configurator` default
  methods delegate to it. Foy must `provides` one.
- `HandshakeRequest.getHttpSession()` (cast to `HttpSession`) and `getHeaders()`.
- `Session`: `isOpen()`, `getRequestURI()` (its path must contain the context path and the
  servlet path: Atmosphere derives `pathInfo` from it), `getQueryString()`,
  `getUserPrincipal()`, `isSecure()`, `setMaxIdleTimeout`, `setMaxTextMessageBufferSize`,
  `setMaxBinaryMessageBufferSize`, `addMessageHandler(Class, MessageHandler.Whole)` for
  `String` and `ByteBuffer` (it falls back to the one-argument
  `addMessageHandler(MessageHandler)` only when the two-argument method is missing),
  `close()` and `close(CloseReason)`.
- `getAsyncRemote().sendText(String, SendHandler)`,
  `getAsyncRemote().sendBinary(ByteBuffer, SendHandler)`, `setSendTimeout(long)`.
- `EndpointConfig.getUserProperties()` read for
  `jakarta.websocket.endpoint.remoteAddress` / `localAddress` (lazily; null is tolerated).
- Whole messages only. Chappe's model (complete messages in, unfragmented messages out) is
  enough for Vaadin.

### 5.2 Modules and the transport hook

- **`foy-api`** gains a transport-neutral package `io.vidocq.foy.spi.websocket`: an
  `HttpUpgradeHandler` subtype that asks the bridge for a WebSocket takeover instead of a
  raw connection, a listener of connection events (open, text, binary, ping, pong, close,
  error) and a peer handle (send text, binary, ping, pong, close, `isOpen`, remote address,
  secure). It does not name Chappe: `foy-api` stays transport-free, the direction
  `foy-core`'s `module-info` already announces for M2.
- **`foy-core`**: in `ChappeServletBridge.upgraded(...)`, an upgrade handler of that
  subtype is answered with Chappe's `WebSocketUpgrade` (handler adapted to the listener,
  the negotiated subprotocol, the handshake request) instead of a `ConnectionUpgrade`.
  Everything before stays as it is: security constraints, filters, `requestDestroyed`.
  Foy tracks these connections per context, so an undeploy closes them with 1001 (Going
  Away) as it closes raw upgraded connections today, and calls `destroy()` on the handler.
  Both happen before the application's `contextDestroyed`: the undeploy delivers `onClose`
  itself once the Close is sent, without waiting for the peer (BUG-20261114-08).
- **`foy-websocket`** (new, Java module `io.vidocq.foy.websocket`): the Jakarta WebSocket
  implementation. It requires `jakarta.websocket` (the API it implements, at `compile`
  scope per the ecosystem rule; `jakarta.websocket.client` comes transitively),
  `jakarta.servlet` and `io.vidocq.foy.api`. Not `chappe-api`, not `foy-core`. It provides
  a `jakarta.servlet.ServletContainerInitializer`, a
  `jakarta.websocket.server.ServerEndpointConfig.Configurator` (the container default),
  and the `io.vidocq.foy.spi.gen.WebComponent` that lets Foy instantiate its upgrade
  handler without reflection (the `upgrade(Class)` path goes through the context's
  component factory).
- **Endpoint instances without reflection.** The spec's container default
  `Configurator.getEndpointInstance` calls the no-argument constructor. Foy already has a
  tiered component factory for that (generated companion, Class-File hidden factory, CDI
  through `CdiWebComponents`, the logged reflective tier last), so `foy-api` gains a small,
  transport-neutral instantiation hook that the context exposes, and the default
  configurator uses it. `getEndpointInstance(Class)` receives no context, so
  `foy-websocket` binds the current context in a `ScopedValue` around the configurator
  calls of the handshake (§5.3 step 6), never a `ThreadLocal`. Lot 2's CDI injection in
  endpoints goes through the same hook.
- **`foy-architecture-tests`** covers the new module: its allowed dependencies, no
  reflection, no platform thread, no `ThreadLocal`.

### 5.3 Deployment and handshake

1. The initializer's `onStartup` creates one `ServerContainer` per `ServletContext`, sets
   the attribute, and registers a filter on `/*` for the `REQUEST` dispatch, after the
   application's filters (`isMatchAfter = true`, as Tomcat's `WsFilter`).
2. `addEndpoint` is accepted during deployment and afterwards, because Vaadin may
   initialise Atmosphere at the first request. Adding a path already registered throws
   `DeploymentException` (Atmosphere expects that). Lot 3 revisits this if the TCK
   requires a stricter rule.
3. The filter passes through any request that is not a WebSocket upgrade or whose path,
   relative to the context, matches no endpoint. Matching follows the spec's precedence:
   an exact path beats a template; among templates, the longest literal prefix segment by
   segment wins. A path has no empty segment, except a trailing `/` on a path without
   variables (Tomcat's `UriTemplate` rule): Atmosphere registers `/VAADIN/push/` beside
   `/VAADIN/push`. That trailing `/` is a literal empty segment, so `/a/b/` matches only
   `/a/b/`, and a variable never matches it.
4. On a match it validates the handshake (RFC 6455 §4.2.1): `GET`, HTTP/1.1, `Upgrade:
   websocket`, a `Connection` header holding the `upgrade` token, a `Sec-WebSocket-Key`
   that decodes to 16 bytes (else 400), `Sec-WebSocket-Version: 13` (else 426 with
   `Sec-WebSocket-Version: 13`), `Configurator.checkOrigin` (false gives 403). The
   container default `checkOrigin` is the origin policy of §5.3.1.
5. It negotiates the subprotocol (`getNegotiatedSubprotocol`); extensions negotiate to none.
6. On the request thread, in this order: `modifyHandshake(sec, request, response)`, then
   `getEndpointInstance(endpointClass)`. Both on the same thread, as §5.1 requires.
7. It calls `request.upgrade(...)` with the takeover handler, configured with the endpoint
   instance, the config, the path parameters and the handshake data. Chappe writes the 101
   (`Sec-WebSocket-Accept`, subprotocol) and runs the frames.

Chappe's `WebSocketUpgrade` writes only its own headers today, so headers added by
`modifyHandshake`, by application filters, or a session cookie created during the
handshake would be dropped. Lot 1 lets `WebSocketUpgrade` carry extra response headers,
validated as `ConnectionUpgrade` validates its own (§5.7), and Foy passes the response
headers the application set, the session cookie included.

#### 5.3.1 Origin policy (cross-site WebSocket hijacking)

Decided by the maintainer: same-origin by default, opened by configuration only.

- A browser handshake whose `Origin` does not match the server is refused with 403 and a
  WARNING naming the refused origin, so a Vaadin page silently falling back to long
  polling shows up in the log.
- Matching compares host and port, not the scheme, so a TLS-terminating proxy that keeps
  `Host` does not break it. The server side is the host and port of the request's single
  `Host` line, a `Host` without a port standing for the default port of the origin's
  scheme; a request with no `Host` or several refuses every origin not listed.
- A handshake without `Origin` (a non-browser client) is accepted: hijacking needs a
  browser, and browsers always send it.
- Opening is configuration, never code: a comma-separated list of origins
  (`https://admin.example.com, http://localhost:5173`), or `*` for all.
  - Foy: the context init parameter `io.vidocq.foy.websocket.allowed-origins`
    (`web.xml` `<context-param>` or `ServletContext.setInitParameter` from an initializer).
  - Vidocq: the key `vidocq.websocket.allowed-origins`, read at start (not frozen at
    build), which the runtime extension turns into that init parameter.
  - A malformed entry stops the deployment, naming the key.
- An application `Configurator` that overrides `checkOrigin` still decides for its own
  endpoints, as the spec requires.
- The same policy, the same default and the same Vidocq key apply to Chappe's native
  WebSocket routes (`Router.Builder.webSocket`, exposed by
  `vidocq-runtime-chappe-webserver-extension`). This closes finding F-06 of the
  workspace's `CYBER_SECU.md`. It changes the behaviour of an existing Chappe route that
  is opened from another origin, so Chappe's migration notes say so.

### 5.4 Session and remote endpoints

- **Threads.** Chappe calls back on the connection's virtual thread, one event at a time;
  `Endpoint.onOpen`, the message handlers, `onClose` and `onError` run there, so an
  endpoint instance never sees two threads at once. One exception: the `onClose` of a
  session an undeploy closes runs on the virtual thread the undeploy closes that session on
  (all sessions at once), after the event in progress returned (at most 2 s), so that it
  precedes `contextDestroyed`; nothing follows it. Every callback runs with the
  application's class loader as context class loader, as raw upgrades do
  (`WebConnectionImpl`). No CDI request or session context is active in a callback in
  lot 1: the handshake request has ended (lot 2 decides). Vaadin with
  `WEBSOCKET_XHR` sends client-to-server messages over XHR, which are ordinary requests.
- **Session data.** A unique id. `getRequestURI()` as the handshake's full request URI
  (context path included, query string included), built from the `Host` header: a `Host`
  that is not `host[:port]` (the grammar of §5.3.1's origins: a name, an IPv4 address or a
  bracketed IPv6 literal, a port up to 65535) would move the path of that URI away from the
  one matched and authorized, so it refuses the handshake with 400. `getQueryString()`,
  `getRequestParameterMap()`, `getPathParameters()`, `getUserPrincipal()` and
  `getHttpSession()` (on the handshake request) captured at handshake. `isSecure()`,
  `getProtocolVersion()` = `13`, `getNegotiatedSubprotocol()`,
  `getNegotiatedExtensions()` empty, `getContainer()`, `getOpenSessions()` (the open
  sessions of the same endpoint config). `getUserProperties()` is a per-session copy of the
  config's user properties, with the remote address under
  `jakarta.websocket.endpoint.remoteAddress`.
- **Message handlers.** `addMessageHandler(Class, Whole)` for `String` and `ByteBuffer`
  (and `byte[]`); at most one text and one binary handler, a second one throws
  `IllegalStateException`. The one-argument form resolves the handler's type argument by
  reading its class's `Signature` attribute with the Class-File API (no reflection), and
  throws `IllegalStateException` when it cannot (a lambda). `MessageHandler.Partial` gets
  each whole message as one part with `last = true`. A message larger than the session's
  maximum closes with 1009.
- **Sends.** `getBasicRemote()` sends block until written (`sendText`, `sendBinary`,
  `sendPing`, `sendPong`; `sendObject` for `String`, `ByteBuffer`, `byte[]` and boxed
  primitives, encoders being lot 2). `getAsyncRemote()` keeps a per-session FIFO drained by
  one virtual thread started on demand, so async sends are never reordered nor concurrent,
  and each `SendHandler` hears its own `SendResult`; with a send timeout, a send not written
  in time fails its `SendResult` and closes the session.
- **Idle timeout.** `setMaxIdleTimeout(ms)`: no incoming nor outgoing frame for that long
  closes with 1001. Default `0`, no timeout, so an idle Vaadin page stays connected. No
  platform thread for the timer.
- **Limits.** `getDefaultMaxTextMessageBufferSize()` and the binary one default to 64 KiB
  (Atmosphere sets its own when configured). Chappe still re-assembles up to its 64 MiB
  cap before Foy can check; lot 3 decides whether `chappe-api` gets a per-connection limit.
- **Close.** `close(CloseReason)` maps to Chappe's close; `onClose(Session, CloseReason)`
  carries the reason of the side that closed first. An idle timeout, a send timeout, an
  undeploy or any other local close reports the session's own reason, even when the peer
  then vanishes; otherwise `onClose` carries the peer's code, and 1006 only when TCP is cut
  with no local close first. `onError` precedes
  `onClose` when a transport failure closes the session, except once an undeploy closed it: the
  server's stop cutting that closing handshake is not an error of the session; an exception from a message
  handler reaches `onError` and the session stays open.
- **Not in lot 1.** `connectToServer` (client API, lot 3) throws `DeploymentException` with
  a message naming lot 3. `ServerContainer.upgradeHttpToWebSocket` reuses steps 4 to 7 of
  §5.3 and landed in lot 1. Partial sends
  (`sendText(String, boolean)`, `getSendStream()`, `getSendWriter()`) buffer the message
  and send it whole on the last part; they landed in lot 1 too.

### 5.5 Vidocq runtime

- `vidocq-runtime-foy-websocket-extension` in
  `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web`, depending on the
  servlet extension and on `foy-websocket`; listed in `vidocq-runtime-bom`.
- `vidocq-runtime-vaadin-extension` depends on it.
- The key `vidocq.websocket.allowed-origins` (§5.3.1) is read at start and feeds both
  Foy (through the init parameter) and the native Chappe WebSocket routes of
  `vidocq-runtime-chappe-webserver-extension`.
- An integration test of the assembled runtime in module mode: an application registering
  an endpoint through `addEndpoint` with templates, connected with `java.net.http.WebSocket`.
- Docs: Vidocq's Vaadin page states that push uses WebSocket; `WEB-PROFILE.md` moves
  WebSocket to "in progress".

### 5.6 Tests (TDD)

- Unit tests in `foy-websocket`: template matching and precedence, handshake validation
  (each refusal and its status), session behaviour against a fake peer (handler
  registration rules, async FIFO, send timeout, idle timeout, close codes, size limit).
- Integration tests on a real Foy + Chappe deployment with the JDK client
  `java.net.http.WebSocket` (test scope, no new dependency): a 101 and an echo through
  `addEndpoint` with a five-level template; a configurator pairing `modifyHandshake` and
  `getEndpointInstance` through a `ThreadLocal` exactly as Atmosphere does; the
  `HttpSession` of the handshake; ordered async sends; a security constraint refusing the
  handshake; undeploy closing with 1001; a connection idle for longer than Chappe's
  `idleTimeout` staying open; the origin policy (same origin accepted, another refused
  with 403, no `Origin` accepted, the init parameter opening a listed origin and `*`); a
  session cookie and a `modifyHandshake` header present on the 101; a peer that stops
  reading or vanishes is cut by the idle timeout and `onClose` fires.
- In Chappe: the origin policy on native routes, the extra 101 headers, and a test for
  each of the two bugs, red before its fix.
- Acceptance (lot 1 exit): the forge-dashboard application, by the FORGE-DASHBOARD
  session, with the criterion of §4.

### 5.7 Delivery

- Branch `feat/websocket` in `chappe`, `foy` and `vidocq` (companion branches: each
  producer's `pr-validate` builds its consumers on that branch).
- Merge order `chappe`, `foy`, `vidocq`, each SNAPSHOT published on maven.vidocq.dev from
  `main` before the next merges. The FORGE-DASHBOARD session is told when the `vidocq`
  SNAPSHOT is published.
- Chappe changes in lot 1 (the maintainer allowed it), on a `feat/websocket` companion
  branch:
  - `chappe-api`: `WebSocketUpgrade` carries extra response headers for the 101 (§5.3);
    native WebSocket routes apply the origin policy of §5.3.1, its allowed origins coming
    from configuration;
  - `chappe-http`: writes those headers; fixes the two bugs below, each logged in
    Chappe's `BUG.md`, because Foy's idle and send timeouts cannot cut a dead connection
    without them:
  - after a server-initiated Close frame, the connection waits for the peer's Close
    without limit (the read loop reads the raw channel, the watchdog is not armed). Fix: a
    close-handshake deadline, after which Chappe closes TCP and reports 1006;
  - a frame write to a peer that stopped reading blocks without limit while holding the
    write lock, so `close()` blocks behind it. Fix: frame writes bounded by Chappe's
    `writeTimeout`, as HTTP responses are; a write past it closes the connection.

  The same bugs let a vanished client hold a socket and a virtual thread on any Chappe
  WebSocket route today. Merge order: `chappe`, then `foy`, then `vidocq`.
- Foy docs: `usage.adoc` (§HTTP Upgrade says WebSocket is not built yet), `reference.adoc`,
  `concepts.adoc`, `migration.adoc` and `internals.adoc` describe what lot 1 delivers and
  what it does not.

## 6. Decisions made by the designer (to check)

- The transport hook lives in `foy-api` as a transport-neutral SPI, so `foy-websocket`
  depends on neither Chappe nor `foy-core` (§5.2).
- The upgrade goes through the standard `HttpServletRequest.upgrade()` path, so security
  constraints, filters and request listeners apply to the handshake unchanged.
- `addEndpoint` stays open after deployment (§5.3 step 2).
- Default maximum message size 64 KiB; default idle timeout none.
- Partial receive is emulated by delivering each whole message as one part.
- Endpoint instances come from Foy's component factory through a `foy-api` hook reached
  with a `ScopedValue` during the handshake, not from reflection (§5.2).
- Callbacks run with the application's class loader; CDI contexts in callbacks wait for
  lot 2 (§5.4).
- The two `chappe-http` bugs of §5.7 are fixed in lot 1.
- `WebSocketUpgrade` gains extra handshake headers in lot 1 rather than lot 3, since
  `chappe-api` changes anyway for the origin policy.
- The origin check compares host and port, ignores the scheme, and accepts a handshake
  without `Origin` (§5.3.1).

## 7. Open for lots 2 and 3

- Whether the TCK needs real fragmentation (streaming a message larger than memory,
  partial frames on the wire) from `chappe-api`; if so, a companion branch in `chappe`.
- A per-connection maximum message size in `chappe-api`: Chappe still re-assembles up to
  64 MiB before Foy can refuse a message larger than the session's limit.
- An abort in `chappe-api`, cutting a WebSocket without a Close frame. Chappe's `close()`
  takes its write lock interruptibly: when it throws before its frame is written, it arms
  no close deadline and nothing cuts TCP, so a vanished peer keeps the socket and the
  session until Chappe's own teardown. Lot 1 clears the interrupt around the undeploy
  close, the one Foy close made on a thread that may be interrupted (Foy's timer and send
  threads never are); a close whose write fails without a cut still has no fallback.
- The client API: `java.net.http.WebSocket` covers the protocol; `ClientEndpointConfig`
  and its configurator map onto its builder.
- Annotated endpoints: one model of the endpoint shared by the annotation processor and the
  deployment-time Class-File reader, as Foy already does for servlets
  (`ClassFileDescriptorReader`, hidden factories).
- `HttpSession` invalidation or logout closing protected endpoints with 1008 (spec §7.2).
