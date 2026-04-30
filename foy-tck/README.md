# vidocq-servlet-chappe-tck-runner

Harness de conformance et exécuteur du **TCK officiel Jakarta Servlet 6.1**
pour `vidocq-servlet-chappe-extension`.

Ce module :

1. Expose un **harness programmatique** (`ServletTestHarness`) qui démarre un
   serveur Chappe local avec notre bridge servlet, utilisable depuis JUnit
   sans container externe.
2. Contient un adaptateur **Arquillian** (`VidocqDeployableContainer`) qui
   packe les `WebArchive` ShrinkWrap du TCK sur ce harness.
3. Exécute le **TCK Jakarta Servlet 6.1.0** (Eclipse Foundation) via le profil
   Maven `-Ptck-official`.

## Pourquoi ce module est hors du reactor principal

> 📌 **Ceci est important si vous voulez lancer le TCK en CI.**

`vidocq-servlet-chappe-tck-runner` est volontairement **EN DEHORS** du
`<modules>` de `vidocq-core-extensions`. Il utilise un pom en
`modelVersion 4.0.0` standalone (sans `<parent>`).

**Raison** : ShrinkWrap Maven Resolver 3.3 (dépendance transitive du
TCK officiel Jakarta) s'appuie sur `maven-resolver 1.9` / `maven-model 3.9`
qui ne savent pas parser les POMs `Model 4.1.0`. Son
`ClasspathWorkspaceReader` scanne le reactor courant pour résoudre les
artifacts locaux et échoue dès qu'il rencontre un POM Vidocq (version
implicite via parent) :

```
Bad artifact coordinates io.vidocq.mpserver:vidocq-servlet-chappe-extension:jar:,
expected format is <groupId>:<artifactId>[:<extension>[:<classifier>]]:<version>
```

Laisser le module dans le reactor `Model 4.1.0` rend tout lancement
TCK depuis la racine impossible. Alternatives considérées :

- Forcer `maven-model-builder 4.0.0-rc-5` : ne résout pas, le crash
  est en amont dans `ClasspathWorkspaceReader.createFoundArtifact`.
- Attendre une release ShrinkWrap compatible Maven 4.1 : pas de date.
- Forker ShrinkWrap : lourd pour un gain marginal.

Tant qu'upstream ShrinkWrap ne gère pas Model 4.1, le module reste
détaché du reactor et se lance via le script dédié.

## Workflow utilisateur

### Prérequis : installer les artefacts du TCK

Le TCK Eclipse Foundation n'est **pas** publié sur Maven Central. Il faut
télécharger le zip et installer les 3 artifacts en dépôt local une fois :

```bash
# Télécharge le TCK
curl -Lo /tmp/jakarta-servlet-tck-6.1.0.zip \
  https://download.eclipse.org/jakartaee/servlet/6.1/jakarta-servlet-tck-6.1.0.zip
unzip /tmp/jakarta-servlet-tck-6.1.0.zip -d /tmp/servlet-tck

# Installe les 3 artifacts dans ~/.m2
mvn install:install-file \
  -Dfile=/tmp/servlet-tck/jakarta-servlet-tck/lib/servlet-tck-runtime-6.1.0.jar \
  -DgroupId=jakarta.tck -DartifactId=servlet-tck-runtime -Dversion=6.1.0 \
  -Dpackaging=jar
mvn install:install-file \
  -Dfile=/tmp/servlet-tck/jakarta-servlet-tck/lib/servlet-tck-util-6.1.0.jar \
  -DgroupId=jakarta.tck -DartifactId=servlet-tck-util -Dversion=6.1.0 \
  -Dpackaging=jar
# Le pom agrégateur (optionnel, référencé par certains tests pluggability)
mvn install:install-file \
  -Dfile=/tmp/servlet-tck/jakarta-servlet-tck/pom.xml \
  -DgroupId=jakarta.tck -DartifactId=servlet-tck -Dversion=6.1.0 \
  -Dpackaging=pom
```

### Lancer le TCK

Depuis la **racine** du projet Vidocq :

```bash
./run-official-tck-servlet6.1.sh                     # smoke test (DoDestroyedTest)
./run-official-tck-servlet6.1.sh --all               # suite TCK complète (~10 min)
./run-official-tck-servlet6.1.sh -Dtest=ServletTests # une classe Tests entière
./run-official-tck-servlet6.1.sh -Dtest=ServletTests#DoInit1Test   # une seule méthode
```

Le script :

1. Installe d'abord en dépôt local les modules dont dépend le TCK runner :
   `vidocq-spi`, `vidocq-core`, `vidocq-chappe-extension`,
   `vidocq-servlet-chappe-extension`.
2. Se place dans `vidocq-core-extensions/vidocq-servlet-chappe-tck-runner/`
   (indispensable : le `cwd` doit contenir le pom Model 4.0).
3. Lance `mvn test -Ptck-official` avec les arguments transmis.

### Intégration CI

Dans une pipeline (GitHub Actions, GitLab CI, etc.) :

```yaml
- name: Build reactor
  run: mvn install -DskipTests

- name: Run Jakarta Servlet 6.1 TCK
  run: ./run-official-tck-servlet6.1.sh --all
  # Prérequis : cache des artefacts TCK dans ~/.m2 (cf. section ci-dessus)
```

## Harness programmatique

Utilisable indépendamment du TCK, pour des tests JUnit ad hoc :

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

Fonctionnalités supportées :

- `servlet`, `filter`, `listener`, `errorPage`, `contextPath`,
  `securityProvider`, `sessionTimeoutMinutes`, `localeEncodingMappings`,
  `contextInitParams`, `servletContainerInitializer`
- Port libre auto-alloué avec retry (absorbe les courses bind)
- `AutoCloseable` : `close()` arrête le serveur et fire les destroy()

## État de conformité TCK Jakarta Servlet 6.1

Au dernier run complet (cf. `target/surefire-reports/`), le profil
`tck-official` passe **~90 %** des tests Jakarta Servlet 6.1 sur les
packages `api.*`. Les non-conformités restantes :

| Cluster | Raison |
|---|---|
| `dispatchtest.DispatchTests` (~18 err) | Cross-context dispatch (`ServletContext.getContext`) non implémenté |
| `registration.RegistrationTests` (10 err) | `CommonServlets.jar` auto-attaché au WAR non scanné |
| `asynccontext.*` (~11 err) | Cas pointus du timeout / startAsync after dispatch |
| `httpservletrequest.HttpServletRequestTests` (3 err) | `getRequestedSessionId` semantics + TCK bug substring |
| Tests JSP (`sc40.addJsp*`, TLD) | Pas de JSP engine |

Les détails sont dans l'historique git (branche `main`, recherche `TCK`).

## Implémentation Arquillian

`VidocqDeployableContainer` est enregistré via le SPI
`org.jboss.arquillian.core.spi.LoadableExtension` dans
`src/test/resources/META-INF/services/`.

Il :

1. Lit le `WebArchive` ShrinkWrap passé par `@Deployment`.
2. Scanne les classes `/WEB-INF/classes/*.class` avec `@WebServlet/@WebFilter/@WebListener`.
3. Parse `WEB-INF/web.xml` (servlets, filters, listeners, error-pages,
   context-params, session-timeout, locale-encoding-mapping-list).
4. Découvre les `ServletContainerInitializer` via
   `META-INF/services/jakarta.servlet.ServletContainerInitializer`.
5. Démarre un `ServletTestHarness` et expose son URL en `HTTPContext`.
6. Supporte le multi-deployment (plusieurs WAR en parallèle, utilisé
   par `DispatchTests`).

Signature tests `sigtest-maven-plugin` pas encore branché — à ajouter
en profil `-Psigtest` si nécessaire pour certifier la conformité API.
