# foy-tck

Conformance harness and runner for the **Jakarta Servlet 6.1 official TCK**
against Foy (transport chappe, CDI vauban).

`foy-tck` is an **in-reactor module gated behind the `tck` Maven profile**:
a plain `./mvnw install` neither downloads nor runs anything TCK-related.
Run it through `../run-official-tck-servlet6.1.sh` (recommended) or
`./mvnw -Ptck,tck-official -pl foy-tck test`.

Historical note: the module used to live out of the reactor because
ShrinkWrap Maven Resolver 3.3 could not parse Model 4.1.0 POMs; that
constraint disappeared with the move to Maven 3.9.16 / Model 4.0.0.

This module:

1. Exposes a **programmatic harness** (`ServletTestHarness`) that starts a
   local Chappe server with our servlet bridge, usable from JUnit
   without an external container.
2. Contains an **Arquillian** adapter (`VidocqDeployableContainer`) that
   packs the TCK's ShrinkWrap `WebArchive` archives onto this harness.
3. Runs the **Jakarta Servlet 6.1.0 TCK** (Eclipse Foundation) via the
   Maven profile `-Ptck-official`.

## User Workflow

### Prerequisites: install TCK artifacts

The Eclipse Foundation TCK is **not** published on Maven Central. You must
download the zip and install the 3 artifacts in the local repository once:

```bash
# Download the TCK
curl -Lo /tmp/jakarta-servlet-tck-6.1.0.zip \
  https://download.eclipse.org/jakartaee/servlet/6.1/jakarta-servlet-tck-6.1.0.zip
unzip /tmp/jakarta-servlet-tck-6.1.0.zip -d /tmp/servlet-tck

# Install the 3 artifacts into ~/.m2
./mvnw install:install-file \
  -Dfile=/tmp/servlet-tck/jakarta-servlet-tck/lib/servlet-tck-runtime-6.1.0.jar \
  -DgroupId=jakarta.tck -DartifactId=servlet-tck-runtime -Dversion=6.1.0 \
  -Dpackaging=jar
./mvnw install:install-file \
  -Dfile=/tmp/servlet-tck/jakarta-servlet-tck/lib/servlet-tck-util-6.1.0.jar \
  -DgroupId=jakarta.tck -DartifactId=servlet-tck-util -Dversion=6.1.0 \
  -Dpackaging=jar
# The aggregate POM (optional, referenced by some pluggability tests)
./mvnw install:install-file \
  -Dfile=/tmp/servlet-tck/jakarta-servlet-tck/pom.xml \
  -DgroupId=jakarta.tck -DartifactId=servlet-tck -Dversion=6.1.0 \
  -Dpackaging=pom
```

### Running the TCK

From the root of the **foy** repository:

```bash
./run-official-tck-servlet6.1.sh                     # smoke test (DoDestroyedTest)
./run-official-tck-servlet6.1.sh --all               # full TCK suite (~11 min)
./run-official-tck-servlet6.1.sh -Dtest=ServletTests # an entire test class
./run-official-tck-servlet6.1.sh -Dtest=ServletTests#DoInit1Test   # a single method
./run-official-tck-servlet6.1.sh --family compat     # one family: api | spec | pluggability | compat
./run-official-tck-servlet6.1.sh --failing           # only the classes failing in tck-baseline.txt
./run-official-tck-servlet6.1.sh --no-install --family api   # skip the reactor install
./run-official-tck-servlet6.1.sh --dry-run --failing # print the Maven commands only
```

Options (all combinable except where noted):

| Option | Effect |
|--------|--------|
| `--all` | Runs the whole suite. |
| `--family <api\|spec\|pluggability\|compat>` | Runs one TCK family (`-Dtest='servlet/tck/<family>/**/*'`). |
| `--failing [tally-file]` | Runs only the classes whose bad count (3rd column) is above 0 in the tally (default `foy-tck/tck-baseline.txt`, format `<fqcn> <run> <bad>`, produced by `foy-tck/tck-tally.sh`). Combined with `--family`, narrows to that family. |
| `--no-install` | Skips `./mvnw -ntp install -DskipTests`; use it when the code has not changed since the last build. |
| `--dry-run` | Prints the Maven commands without running them. |
| `-Dtest=...` | Forwarded to surefire; cannot be combined with `--family` / `--failing`. |
| `-h`, `--help` | Prints the usage. |

`--family` and `--failing` also pass `-Dsurefire.failIfNoSpecifiedTests=false`.
Unknown options print the usage and exit with status 2.

Unless `--no-install` is given, the script installs the Foy reactor into the
local repository (`./mvnw -ntp install -DskipTests`), then runs
`./mvnw -ntp -Ptck,tck-official -pl foy-tck test` with the selection.

### CI Integration

In a pipeline (GitHub Actions, GitLab CI, etc.):

```yaml
- name: Build reactor
  run: ./mvnw -ntp install -DskipTests

- name: Run Jakarta Servlet 6.1 TCK
  run: ./run-official-tck-servlet6.1.sh --all
  # Prerequisite: TCK artifacts cached in ~/.m2 (see section above)
```

## Programmatic Harness

Usable independently of the TCK, for ad hoc JUnit tests:

```java
try (var h = ServletTestHarness.builder()
        .servlet("/hello", new HelloServlet())
        .filter("/*", new LoggingFilter())
        .errorPage(404, "/notFound")
        .contextPath("/app")
        .securityProvider(myProvider)
        .start()) {
    HttpResponse<String> r = h.get("/app/hello?name=alice");
    assertEquals(200, r.statusCode());
}
```

Supported features:

- `servlet`, `filter`, `listener`, `errorPage`, `contextPath`,
  `securityProvider`, `sessionTimeoutMinutes`, `localeEncodingMappings`,
  `contextInitParams`, `servletContainerInitializer`
- Auto-allocated free port with retry (absorbs bind races)
- `AutoCloseable`: `close()` stops the server and fires `destroy()`

## Jakarta Servlet 6.1 TCK Conformance Status

Current figures: see `../TCK.md`.

## Arquillian Implementation

`VidocqDeployableContainer` is registered via the SPI
`org.jboss.arquillian.core.spi.LoadableExtension` in
`src/test/resources/META-INF/services/`.

It:

1. Reads the ShrinkWrap `WebArchive` passed by `@Deployment`.
2. Scans `/WEB-INF/classes/*.class` for `@WebServlet/@WebFilter/@WebListener`.
3. Parses `WEB-INF/web.xml` (servlets, filters, listeners, error-pages,
   context-params, session-timeout, locale-encoding-mapping-list).
4. Discovers `ServletContainerInitializer` via
   `META-INF/services/jakarta.servlet.ServletContainerInitializer`.
5. Starts a `ServletTestHarness` and exposes its URL in an `HTTPContext`.
6. Supports multi-deployment (several WARs in parallel, used
   by `DispatchTests`).

Signature tests `sigtest-maven-plugin` not yet connected — to add
in a `-Psigtest` profile if needed to certify API conformance.
