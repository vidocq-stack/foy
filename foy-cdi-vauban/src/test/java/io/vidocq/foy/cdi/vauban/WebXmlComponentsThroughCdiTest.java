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
package io.vidocq.foy.cdi.vauban;

import io.vidocq.foy.cdi.vauban.TestApps.ProcessorPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.vidocq.foy.cdi.vauban.TestApps.boot;
import static io.vidocq.foy.cdi.vauban.TestApps.deploy;
import static io.vidocq.foy.cdi.vauban.TestApps.withTccl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A component {@code web.xml} declares, and whose class is a bean, is created by the container: its injection points
 * are injected, as Servlet 6.1 §15.5 requires of a Jakarta EE container. A class that is no bean is still created
 * as before (BUG-20261118-62).
 */
@DisplayName("web.xml components whose class is a bean are created through CDI")
class WebXmlComponentsThroughCdiTest {

    private static final String WEB_XML = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <servlet><servlet-name>injected</servlet-name><servlet-class>xml.app.InjectedServlet</servlet-class></servlet>
              <servlet-mapping><servlet-name>injected</servlet-name><url-pattern>/injected</url-pattern></servlet-mapping>
              <servlet><servlet-name>plain</servlet-name><servlet-class>xml.app.PlainServlet</servlet-class></servlet>
              <servlet-mapping><servlet-name>plain</servlet-name><url-pattern>/plain</url-pattern></servlet-mapping>
            </web-app>
            """;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a servlet of web.xml that is a bean gets its injection points; one that is no bean is served too")
    void aWebXmlServletThatIsABeanIsInjected() throws Exception {
        var result = TestApps.compile(tempDir, ProcessorPath.CLASS_PATH, List.of(), """
                package xml.app;

                @jakarta.enterprise.context.ApplicationScoped
                public class Greeter {
                    public String greet() {
                        return "hello from an injected greeter";
                    }
                }
                """, """
                package xml.app;

                @jakarta.enterprise.context.Dependent
                public class InjectedServlet extends jakarta.servlet.http.HttpServlet {
                    @jakarta.inject.Inject
                    Greeter greeter;

                    @Override
                    protected void doGet(jakarta.servlet.http.HttpServletRequest q,
                            jakarta.servlet.http.HttpServletResponse r) throws java.io.IOException {
                        r.getWriter().write(greeter == null ? "not injected" : greeter.greet());
                    }
                }
                """, """
                package xml.app;

                public class PlainServlet extends jakarta.servlet.http.HttpServlet {
                    @Override
                    protected void doGet(jakarta.servlet.http.HttpServletRequest q,
                            jakarta.servlet.http.HttpServletResponse r) throws java.io.IOException {
                        r.getWriter().write("plain");
                    }
                }
                """);
        assertTrue(result.success(), result::messages);
        Files.createDirectories(result.classes().resolve("WEB-INF"));
        Files.writeString(result.classes().resolve("WEB-INF/web.xml"), WEB_XML);

        try (var loader = result.loader();
             var container = withTccl(loader, () -> boot(loader));
             var app = deploy(container, loader, "/")) {
            assertEquals("hello from an injected greeter", get(app, "/injected"));
            assertEquals("plain", get(app, "/plain"));
        }
    }

    private static String get(TestApps.Deployed app, String path) throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(app.uri(path)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response::body);
        return response.body();
    }
}
