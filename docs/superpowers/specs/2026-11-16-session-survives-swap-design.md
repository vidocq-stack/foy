# HTTP sessions survive a dev swap: the Foy half

Status: draft for review (VIDOCQ, then Yann). Date: 2026-11-16. Branch: `feat/session-survives-swap`.

## Intent

Yann decided (2026-11-16) that HTTP sessions survive an in-JVM dev swap of `vidocq:dev`. Today every reload undeploys
the servlet application. That invalidates its sessions, and a Vaadin page then starts over (`Undeploying the servlet
application invalidated N HTTP sessions …`). After this work, the old deployment exports its live sessions, Vidocq
keeps the bytes in the boot layer across the swap, and the new deployment imports them before it serves a request.
The browser's `JSESSIONID` stays valid and the session keeps its state.

This document covers what Foy, a brick, provides. The Vidocq half belongs to VAADIN's design
(`vidocq` branch `design/dev-session-handover`, `docs/superpowers/specs/2026-11-16-dev-session-handover-design.md`).
That half covers when a swap exports, the handover store in the boot layer, the calls in `FoyDeployments`, and the
log line.

## Goals

- An additive API on Foy's deployment: export the live sessions while undeploying, and import them into a new
  deployment through its own class loader.
- Servlet semantics of a migration: `HttpSessionActivationListener.sessionWillPassivate` before the write,
  `sessionDidActivate` after the read. No `sessionCreated` or `sessionDestroyed` for a session that moves, and no
  `valueBound`, `valueUnbound` or attribute events.
- Kept: the id, the creation time, the last-accessed time and the maximum inactive interval. `isNew()` is `false`
  after the import.
- A session that cannot move never fails the swap: it ends as today, with one WARN line that names the cause.
- `@SessionScoped` instances move with their session and rebind to the new CDI container.

## Non-goals and anti-goals

- No persistence to disk, no clustering, no move between JVMs. The bytes never leave the process, and the format is
  private to one Foy version.
- Foy knows nothing about `vidocq:dev`. The API is neutral, and Vidocq alone decides when to export.
- No signature change on an existing method. Snapshots publish apart, so a changed signature would break the Vidocq
  snapshot built against the previous Foy (`NoSuchMethodError`).
- Anti-goal: a session carried over half-restored. The application would see state that never existed together.
  A whole fresh session is better.

## Constraints from the code

- `Deployment.close()` undeploys in this order: refuse new requests; close upgraded connections (WebSocket `onClose`,
  then `destroy()`); wait up to 2 s for the requests in service; `destroy()` of filters, then servlets; then
  `SessionManager.close()` invalidates every session with its listeners; then `contextDestroyed`.
- `FoyChappeBoot.Builder.build()` deploys, including `contextInitialized`, and returns a `Mounted` whose handler the
  caller mounts afterwards. So a new deployment can import between `build()` and the mount.
- `HttpSessionImpl` keeps two kinds of state:
  - application attributes;
  - container-private notes: `FormAuthenticator`'s saved target, a `String`, and the cached identity,
    `WebSecurity.CachedIdentity(Identity(Principal, Set<String>), String)`. These records are not `Serializable`.
- Vauban's session context stores each `@SessionScoped` instance as a session attribute.
  - The key is `io.vidocq.vauban.webcontexts.session:` plus the bean's passivation id, which is already stable.
  - The value is `SessionContext.Entry(Contextual, instance, CreationalContext)`, a package-private record that is
    not `Serializable`. It holds the old container's `Bean`.
- Vauban's generated client proxies are not `Serializable`, and there is no `writeReplace` anywhere in Vauban. An
  object in the session that holds an injected normal-scoped bean cannot be written today.
- Vidocq's Vaadin extension does not create views through CDI. A plain Vaadin application keeps only Vaadin and
  application objects in its `VaadinSession`, and Vaadin designs them to be serializable.

## Approaches considered

1. **Export taken before `close()`, then the normal close.** This is what the first brief described. Rejected:
   - requests still run and push connections are still open when the snapshot is taken, so their later changes are
     lost;
   - the normal close would then fire `sessionDestroyed` and `valueUnbound` on sessions that live on.
2. **Export inside the undeploy, in place of the invalidation (chosen).** A new `closeExportingSessions()` runs the
   same undeploy, and only the session step changes:
   - it runs after the upgraded connections closed and the requests drained, so Vaadin's push disconnect has already
     run on the live application;
   - it runs after `destroy()`, as Tomcat persists sessions after stopping the servlets;
   - it runs before `contextDestroyed`.
   It is the only point where the snapshot is complete and no request can change it.
3. **A keep-the-objects handover**, where the session objects themselves cross the swap. Rejected: the objects belong
   to the old layer's classes, so the new layer's code would see foreign classes (`ClassCastException`) and keep the
   old layer in memory.

## Design

### API (all additive)

`foy-core`, `io.vidocq.foy.internal.session.SessionManager`:

```java
/** Undeploy step: stops creations and the reaper like close(), then exports instead of invalidating. */
public byte[] closeExporting();

/** Imports an export into this manager; call before any request reaches the deployment. */
public SessionImport importSessions(byte[] export, ClassLoader loader);

public record SessionImport(int imported, int expired, int dropped) {}
```

`foy-core`, `io.vidocq.foy.internal.boot.Deployment`:

```java
/** close(), except that the live sessions are exported instead of invalidated. A second call returns an empty export. */
public byte[] closeExportingSessions();

/** Imports through the deployment's own application class loader. */
public SessionImport importSessions(byte[] export);
```

`foy-chappe`, `FoyChappeBoot.Mounted`, the type Vidocq holds, with JDK types only so that Vidocq reads no Foy-core
type:

```java
public byte[] closeExportingSessions();   // in place of close()
public int importSessions(byte[] export); // after build(), before the handler is mounted; returns the count imported
```

The Vidocq contract follows:
- at a dev swap, `mounted.closeExportingSessions()` replaces `mounted.close()`;
- the bytes are stored keyed by the context path;
- after the new `build()`, `mounted.importSessions(bytes)` runs, then the handler is mounted;
- `activeSessions()` read before the close still gives the count for the log line.

### Export, per live session, under the application's class loader

1. A session idle beyond its interval is invalidated as today, with its listeners, and is not exported.
2. `sessionWillPassivate` fires on each attribute that implements `HttpSessionActivationListener`.
3. Each attribute value is written on its own, to its own `byte[]`, with `ObjectOutputStream`. The outer frame holds
   only JDK types, so a failing attribute never corrupts the stream:
   - a magic and a format version;
   - per session: the id, the creation time, the last-accessed time, the maximum interval, and the name and bytes of
     each attribute.
4. If an attribute fails to write (`NotSerializableException` or any `IOException`), the whole session stays behind:
   - `sessionDidActivate` fires back on the attributes that were passivated;
   - the session is invalidated as today, with `sessionDestroyed` and the unbinding;
   - one WARN line names the session id prefix, the attribute name and the class that failed.
5. A session that is written leaves silently. It is removed from the store and marked invalidated, so a stale
   reference throws `IllegalStateException`. No `sessionDestroyed`, `valueUnbound` or `attributeRemoved` fires: the
   session has not ended.

Notes are container-private state:
- **The saved target** (a `String`) is written as is.
- **The cached identity** moves when its `Principal` is serializable. `Identity` and `CachedIdentity` gain
  `implements Serializable`, which is additive.
- **A non-serializable `Principal`** drops only that note, and that user logs in again. The session itself moves: the
  note is Foy's state, not the application's, so the whole-session rule does not apply.

### Import, under the new application class loader

- The bytes are read with an `ObjectInputStream` whose `resolveClass` and `resolveProxyClass` use the given loader.
  Classes pinned in the parent layer (`vidocq.app.layer.keep`, the bricks, the APIs) are found through that loader's
  parents, so they stay the same `Class` objects: nothing special is needed for them.
- A session already idle beyond its interval when imported is skipped and counted in `expired`. The swap time counts
  as idle time.
- A session whose attribute fails to read is skipped and counted in `dropped`:
  - `InvalidClassException` after a field change without a `serialVersionUID`;
  - `ClassNotFoundException` for a class that was deleted;
  - an exception thrown from the attribute's `readObject`.
  The other sessions are kept. One summary WARN line gives the count and the first failing class.
- Each imported session gets a new `HttpSessionImpl` with the same id and times, `isNew() == false`, its attributes
  and its notes. It is put in the store, then `sessionDidActivate` fires on its activation listeners. No
  `sessionCreated` fires, and no attribute or binding event.
- An id already in the store is skipped. The import throws `IllegalStateException` on a closed manager.

### `@SessionScoped` and CDI references: needs a Vauban companion

With the rule above, a session that holds a `@SessionScoped` instance (its `Entry`), or any object that holds a CDI
client proxy, cannot be written and stays behind. Foy alone would keep plain Vaadin applications and any
CDI-free session, but not CDI state. The proposal is an additive Vauban change on a companion branch
`feat/session-survives-swap`.

- **Entries.** `SessionContext.Entry` writes itself (`writeReplace`) as the bean's passivation id plus the instance.
  On read, it rebinds to the importing container:
  - `getPassivationCapableBean(id)`, then a new `CreationalContext`;
  - the same attribute key, since the key already carries the passivation id.
  A bean without a stable id cannot be written, and its session stays behind with a WARN.
- **Client proxies.** They write themselves as the bean's id. On read, they resolve the importing container's proxy
  for that bean.
- **The importing container** is bound for the import's duration by a Vauban API (a `ScopedValue`, never a
  `ThreadLocal`). Foy reaches it through one default method added to the existing SPI `CdiContextListeners`:
  `default <T> T aroundSessionImport(BeanManager beanManager, Supplier<T> importer) { return importer.get(); }`.
  `foy-cdi-vauban` overrides it to bind its `BeanManager`. Foy core stays CDI-neutral.
- **No lifecycle callbacks.** No `@PreDestroy` runs on the old side for an instance that moves, and no
  `@PostConstruct` on the new side. This is CDI passivation, not destruction.

Rejected alternative: `foy-cdi-vauban` strips the CDI entries at export, and the beans are created afresh on next
use. That gives a half-restored session (anti-goal). Stripping the entries does not help an object that holds a
client proxy anyway.

### Push reconnection (the Foy side)

Upgraded connections close with 1001 before the export, so Vaadin's push disconnect runs on the live application and
its result is in the snapshot. The client reconnects with the same `JSESSIONID`. Because the import runs before the
mount, a reconnect never finds an empty store and never creates a new session. Until the new handler is mounted, the
old deployment answers 503 with `Retry-After`. What Vaadin's client then does is VAADIN's to check end to end.

## Open points

1. **Vauban companion** (passivation of entries and client proxies). Who: I propose doing it myself, after this
   design is agreed. Without it, CDI state does not move, and a session holding it starts afresh as today.
2. **Whole-session drop.** VAADIN proposed it and I agree. VIDOCQ's brief allowed dropping the attribute or the
   session. Yann to confirm.
3. **Identity note.** The design moves only a serializable `Principal`, so a user whose `Principal` cannot be written
   logs in again. Yann to confirm, or to ask for a re-authentication through the identity store instead.

## Tests (TDD, written first)

`foy-core`:
- A round trip through two class loaders. A test class is loaded twice by two child-first loaders. The value imported
  is an instance of loader B's class, which differs from A's, and its fields are equal.
- The id, the creation and last-accessed times and the interval are kept, and `isNew()` is `false`. A request that
  carries the old `JSESSIONID` finds the imported session (end to end through Chappe).
- Listener calls:
  - `sessionWillPassivate` once per exported session, and `sessionDidActivate` once after the import;
  - no `sessionCreated`, `sessionDestroyed`, attribute or binding event for a session that moves.
- The skip case at export. A non-serializable attribute keeps the session behind: it is passivated, activated back,
  then invalidated with `sessionDestroyed`. One WARN line names the attribute and its class. The other sessions move.
- The skip case at import. A class missing from loader B drops that session, the other sessions are kept, and one
  summary WARN line is logged.
- Expiry:
  - a session expired at export is invalidated with its listeners and not exported;
  - a session expired at import is counted in `expired` and not imported.
- After `closeExportingSessions()`, `activeSessions()` is 0, a stale reference throws, and a second call returns an
  empty export.
- Notes:
  - an authenticated session stays authenticated;
  - a non-serializable `Principal` drops only the identity note.

`foy-cdi-vauban`, after the Vauban companion: a `@SessionScoped` bean's state survives a round trip into a second
container. Its injected `@ApplicationScoped` bean is the new container's.

## Delivery

1. Foy PR 1: core export and import, notes, `Mounted` methods, the `CdiContextListeners` default method, docs
   (`internals.adoc`, `whats-new.adoc`), and `BUG.md` if a bug turns up.
2. A Vauban PR and Foy PR 2 (`foy-cdi-vauban` override), on companion branches.
3. Vidocq PR (VAADIN or VIDOCQ), once the Foy snapshot is published.
