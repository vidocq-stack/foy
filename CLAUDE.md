# CLAUDE.md

Contributor guidance for agents working on this repository. See also the companion `AGENTS.md` file.

## Documentation (Antora) conventions

The project documentation lives in `docs/en` as an Antora component and is
aggregated by the **vidocq-docs** site, which provides a **shared UI bundle** (banner,
logo, fonts, colours, footer). **Never customise the documentation UI per project** —
all visual harmonisation is centralised in `vidocq-docs/ui-bundle`.

### Gold reference
**Vauban** is the reference implementation for documentation structure. Mirror its
`docs/en` layout when creating or updating docs. **Chappe** (HTTP server)
and **Vidocq** (runtime orchestrator) are *special cases*, not references: they are not
Jakarta EE / MicroProfile spec implementations.

### Repository layout
- `docs/en/antora.yml` → `name: <project>`, `title:`, versioned per branch (`dev` prerelease on `main`, `'<version>'` on `docs/<version>`), `project-version` attribute, `nav:`, `lang: en`.
- Pages in `modules/ROOT/pages/`, navigation in `modules/ROOT/nav.adoc`, images in
  `modules/ROOT/images/`.
- **English-only** (ADR 0004 in vidocq-docs): no French mirror — do not reintroduce one.

### Canonical navigation (section order)
`index` → `getting-started` → `usage` → `concepts` → `internals` → `tck` →
`performance` → `reference` → `migration`

Multi-module projects (e.g. Vidocq, Mansart) may append `modules/*` / `sub-modules/*`
sub-pages after `migration`.

### TCK / Performance rule (not mutually exclusive)
- Every **spec implementation** — i.e. **all projects except Chappe and Vidocq** — MUST
  have a **`tck`** section documenting TCK coverage/status.
- Projects with a performance story (e.g. **Chappe**) keep their **`performance`** section.
- When **both** sections exist, order them **TCK first, then Performance**.
- **Chappe** and **Vidocq** do not require a `tck` section (not spec implementations).

### `index.adoc` structure
Follow Vauban's `index.adoc`: page title (`= <Project>`), `:description:`, a centred logo
(`image::<project>-logo.png[...,role=module-logo]`), a `[.lead]` paragraph, then
`== Origin of the name`, an `== At a glance` table, and ecosystem / quick-links sections.

### Logo
Provide `modules/ROOT/images/<project>-logo.png` (PNG), referenced from `index.adoc`.

> When you change these documentation rules, keep `AGENTS.md` and `CLAUDE.md` in sync.

## Terminology

Use **Java Modules** (or **Java module** for a single module) when referring to
the Java Platform Module System. Do **not** use the abbreviation **JPMS** — in
prose, identifiers, or documentation.

## Dependencies and test libraries

What Foy ships depends only on the JDK, the Jakarta APIs it implements or reads
(`jakarta.servlet`, `jakarta.annotation`, `jakarta.enterprise`), Chappe's API
(`chappe-api`, never `chappe-core` or `chappe-http`) and, in `foy-cdi-vauban` only,
Vauban (workspace rule "Zero external dependency for what ships").

Test-scope libraries are never shipped: **JUnit**, and **ArchUnit**
(`com.tngtech.archunit:archunit`, Apache-2.0), used only by the test-only module
`foy-architecture-tests` from plain JUnit tests (Vidocq/vidocq#289). Its
`ArchitectureRules` turns these rules into tests on the classes of every shipped
module (`foy-api`, `foy-processor`, `foy-core`, `foy-cdi-vauban`, `foy-chappe`):
the dependencies of each module, the layers between them, no reflection outside
the documented fallbacks, no platform thread, no `ThreadLocal`.
`ArchitectureRulesTest` applies them to the shipped classes,
`ArchitectureRulesSelfTest` shows each one failing on a fixture that breaks it.
When a rule changes, cite the document it comes from in `because(...)` and keep a
fixture for it; a new reflective fallback goes into `REFLECTIVE_FALLBACKS` with its
reason, and into the list of `internals.adoc`.

> Keep this section the same in `AGENTS.md` and `CLAUDE.md`.
