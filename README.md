<p align="center">
  <img src="foy-logo.png" alt="Foy" width="300">
</p>

<h1 align="center">Foy</h1>

<p align="center">
  <strong>Implémentation Jakarta Servlet 6.1 — moteur pur, transport HTTP via SPI, CDI optionnel</strong><br>
  <a href="https://jakarta.ee/specifications/servlet/6.1/">Jakarta Servlet 6.1</a> | JPMS natif | Virtual Threads | JDK 25
</p>

<p align="center">
  <img src="https://img.shields.io/badge/JDK-25-orange" alt="JDK">
  <img src="https://img.shields.io/badge/Maven-4.0--rc--5-purple" alt="Maven">
  <img src="https://img.shields.io/badge/Jakarta_Servlet-6.1-blue" alt="Jakarta Servlet">
  <img src="https://img.shields.io/badge/license-Apache_2.0-green" alt="License">
</p>

---

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
