# Foy

Implémentation **Jakarta Servlet 6.1** standalone — moteur Servlet pur, transport
HTTP via SPI, CDI optionnel.

Extrait en avril 2026 du module
[`vidocq-mps-servlet-chappe-extension`](https://forge.vidocq.dev/vidocq/vidocq)
pour devenir un projet indépendant utilisable hors écosystème Vidocq-MPS.

## Modules

| Module | Description |
| --- | --- |
| `foy-api` | Interfaces SPI publiques : `SessionStore`, `SecurityProvider`, `AuthenticatedUser`. Zéro dépendance hors `jakarta.servlet-api`. |
| `foy-core` | Moteur Servlet 6.1 — dispatcher, filter chain, session, error pages, listeners, security, web.xml, multipart. |
| `foy-cdi-vauban` | Pont vers le container CDI [Vauban](https://forge.vidocq.dev/vidocq/vauban) (`FoyVaubanBootstrap.beanManager()`). |
| `foy-chappe` | Adapter HTTP [Chappe](https://forge.vidocq.dev/vidocq/chappe) (`FoyChappeBoot.builder().beanManager(bm).build()`). |
| `foy-tck` | Harness Arquillian pour le TCK officiel Jakarta Servlet 6.1 (POM Model 4.0.0 standalone, hors reactor). |

## Statut

**M1 — extraction fonctionnelle** : le moteur tourne en mode standalone Vauban + Chappe.
Le reactor (`foy-api`, `foy-core`, `foy-cdi-vauban`, `foy-chappe`) compile sans
warning bloquant.

**TODO M2 — découplage transport** :

- `foy-core` dépend encore directement de `chappe-api` (les bridges
  `HttpServletRequestImpl`, `HttpServletResponseImpl`, `ServletOutputStreamImpl`
  et `ChappeServletBridge` référencent `chappe.api.Request/Response`).
  À découpler via une SPI `FoyHttpExchange` dans `foy-api` (pattern
  Cassini : `CassiniHttpAdapter` + `CassiniHttpExchange`).
- Promouvoir une SPI `BeanProvider` dans `foy-api` pour découpler
  `WebAppDiscovery` du `BeanManager` direct (permet alors d'écrire des
  intégrations CDI alternatives — Weld, OpenWebBeans).
- Introduire un `FoyServletEngine` (équivalent `CassiniStack`) avec
  `Builder` + `BuilderFactory` découvert via `ServiceLoader`.

Une fois M2 acquis, on pourra écrire `foy-jdk-http`, `foy-jetty`, `foy-netty`
sans toucher à `foy-core`.

## Lancement TCK Jakarta Servlet 6.1

```sh
./run-official-tck-servlet6.1.sh           # smoke test
./run-official-tck-servlet6.1.sh --all     # suite complète
./run-official-tck-servlet6.1.sh -Dtest=ServletTests
```

Prérequis : artifacts TCK officiels installés en local
(`jakarta.tck:servlet-tck-runtime:6.1.0`, `servlet-tck-util:6.1.0`,
`servlet-tck:6.1.0`).

## Build

```sh
mvn clean install
```
