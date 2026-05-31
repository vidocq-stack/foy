# vidocq-servlet-chappe-tck-runner

Conformance harness and runner for the **Jakarta Servlet 6.1 official TCK**
for `vidocq-servlet-chappe-extension`.

This module:

1. Exposes a **programmatic harness** (`ServletTestHarness`) that starts a
   local Chappe server with our servlet bridge, usable from JUnit
   without an external container.
2. Contains an **Arquillian** adapter (`VidocqDeployableContainer`) that
   packs the TCK's ShrinkWrap `WebArchive` archives onto this harness.
3. Runs the **Jakarta Servlet 6.1.0 TCK** (Eclipse Foundation) via the
   Maven profile `-Ptck-official`.

## Why This Module Is Out of the Main Reactor

> 📌 **This is important if you want to run the TCK in CI.**

`vidocq-servlet-chappe-tck-runner` is intentionally **OUTSIDE** the
`<modules>` of `vidocq-core-extensions`. It uses a standalone
`modelVersion 4.0.0` POM (no `<parent>`).

**Reason**: ShrinkWrap Maven Resolver 3.3 (transitive dependency of the
official Jakarta TCK) relies on `maven-resolver 1.9` / `maven-model 3.9`
which cannot parse `Model 4.1.0` POMs. Its
`ClasspathWorkspaceReader` scans the current reactor to resolve local
artifacts and fails as soon as it encounters a Vidocq POM (implicit
version via parent):

```
Bad artifact coordinates io.vidocq.runtime:vidocq-servlet-chappe-extension:jar:,
expected format is <groupId>:<artifactId>[:<extension>[:<classifier>]]:<version>
```

Keeping the module in the `Model 4.1.0` reactor makes any TCK launch
from the root impossible. Considered alternatives:

- Force `maven-model-builder 4.0.0-rc-5`: does not resolve, the crash
  is upstream in `ClasspathWorkspaceReader.createFoundArtifact`.
- Wait for a ShrinkWrap release compatible with Maven 4.1: no date.
- Fork ShrinkWrap: heavy for marginal gain.

Until upstream ShrinkWrap handles Model 4.1, the module stays
detached from the reactor and is launched via the dedicated script.

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
mvn install:install-file \
  -Dfile=/tmp/servlet-tck/jakarta-servlet-tck/lib/servlet-tck-runtime-6.1.0.jar \
  -DgroupId=jakarta.tck -DartifactId=servlet-tck-runtime -Dversion=6.1.0 \
  -Dpackaging=jar
mvn install:install-file \
  -Dfile=/tmp/servlet-tck/jakarta-servlet-tck/lib/servlet-tck-util-6.1.0.jar \
  -DgroupId=jakarta.tck -DartifactId=servlet-tck-util -Dversion=6.1.0 \
  -Dpackaging=jar
# The aggregate POM (optional, referenced by some pluggability tests)
mvn install:install-file \
  -Dfile=/tmp/servlet-tck/jakarta-servlet-tck/pom.xml \
  -DgroupId=jakarta.tck -DartifactId=servlet-tck -Dversion=6.1.0 \
  -Dpackaging=pom
```

### Running the TCK

From the **root** of the Vidocq project:

```bash
./run-official-tck-servlet6.1.sh                     # smoke test (DoDestroyedTest)
./run-official-tck-servlet6.1.sh --all               # full TCK suite (~10 min)
./run-official-tck-servlet6.1.sh -Dtest=ServletTests # an entire test class
./run-official-tck-servlet6.1.sh -Dtest=ServletTests#DoInit1Test   # a single method
```

The script:

1. First installs in the local repository the modules the TCK runner depends on:
   `vidocq-spi`, `vidocq-core`, `vidocq-chappe-extension`,
   `vidocq-servlet-chappe-extension`.
2. Changes to `vidocq-core-extensions/vidocq-servlet-chappe-tck-runner/`
   (required: the `cwd` must contain the Model 4.0 POM).
3. Runs `mvn test -Ptck-official` with the passed arguments.

### CI Integration

In a pipeline (GitHub Actions, GitLab CI, etc.):

```yaml
- name: Build reactor
  run: mvn install -DskipTests

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

On the last full run (see `target/surefire-reports/`), the
`tck-official` profile passes **~90%** of the Jakarta Servlet 6.1 tests on
the `api.*` packages. Remaining non-conformances:

| Cluster | Reason |
|---|---|
| `dispatchtest.DispatchTests` (~18 errors) | Cross-context dispatch (`ServletContext.getContext`) not implemented |
| `registration.RegistrationTests` (10 errors) | `CommonServlets.jar` auto-attached to WAR not scanned |
| `asynccontext.*` (~11 errors) | Edge cases of timeout / startAsync after dispatch |
| `httpservletrequest.HttpServletRequestTests` (3 errors) | `getRequestedSessionId` semantics + TCK substring bug |
| JSP tests (`sc40.addJsp*`, TLD) | No JSP engine |

Details are in the git history (branch `main`, search `TCK`).

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
