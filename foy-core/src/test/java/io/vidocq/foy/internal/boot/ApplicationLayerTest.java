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
package io.vidocq.foy.internal.boot;

import io.vidocq.foy.spi.jsp.JspEngine;
import jakarta.servlet.Servlet;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.spi.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** The JSP engine lookup over an application layer. */
class ApplicationLayerTest {
    @TempDir Path tmp;

    @BeforeEach
    @org.junit.jupiter.api.AfterEach
    void resetProperties() {
        System.clearProperty("test.jsp.created");
        System.getProperties().remove("test.jsp.layer");
    }

    @Test
    void theBootLayerHasNoEngine() {
        assertTrue(WebAppDeployer.jspEngine(ModuleLayer.boot()).isEmpty());
    }

    @Test
    void anEngineOfAChildLayerIsFoundAndAnswersHasPages() throws Exception {
        ModuleLayer child = layerWith("test.jsp");
        JspEngine engine = WebAppDeployer.jspEngine(child).orElseThrow();
        assertTrue(engine.hasPages(child), "the default answers true");
    }

    @Test
    void twoEnginesInTheLayerAreRefused() throws Exception {
        ModuleLayer child = layerWith("test.jsp", "test.jsp2");
        var e = assertThrows(IllegalStateException.class, () -> WebAppDeployer.jspEngine(child));
        assertTrue(e.getMessage().contains("Multiple JSP engines"), e.getMessage());
    }

    @Test
    void theThreeArgumentDeployOpensTheEngineWithTheLayer() throws Exception {
        ModuleLayer child = layerWith("test.jsp");
        var d = WebAppDeployer.deploy(WebAppModel.builder("/").build(),
                DeployOptions.defaults(getClass().getClassLoader()), child);
        try {
            assertNotNull(d.servletContext().getServletRegistration("jsp"), "*.jsp is mapped to the engine");
            assertSame(child, System.getProperties().get("test.jsp.layer"));
        } finally {
            d.close();
        }
    }

    @Test
    void theEngineIsLookedUpOnce() throws Exception {
        ModuleLayer child = layerWith("test.jsp");
        JspEngine engine = WebAppDeployer.jspEngine(child).orElseThrow();
        var d = WebAppDeployer.deploy(WebAppModel.builder("/").build(),
                DeployOptions.defaults(getClass().getClassLoader()), child, engine);
        try {
            assertEquals("1", System.getProperty("test.jsp.created"));
        } finally {
            d.close();
        }
    }

    /** Compiles one engine-providing module per name and resolves them together in a child of the boot layer. */
    private ModuleLayer layerWith(String... names) throws Exception {
        Path out = tmp.resolve("out");
        Files.createDirectories(out);
        // Compiled against foy-api's own classes, so the provider's JspEngine is the one foy-core loads.
        String modulePath = codeSource(JspEngine.class) + java.io.File.pathSeparator + codeSource(Servlet.class);
        for (String name : names) {
            Path src = tmp.resolve("src-" + name);
            Files.createDirectories(src.resolve(name.replace('.', '/')));
            Path info = src.resolve("module-info.java");
            Files.writeString(info, "module " + name + " { requires io.vidocq.foy.api; requires jakarta.servlet;"
                    + " provides io.vidocq.foy.spi.jsp.JspEngine with " + name + ".TestEngine; }");
            Path engine = src.resolve(name.replace('.', '/')).resolve("TestEngine.java");
            Files.writeString(engine, engineSource(name));
            int rc = ToolProvider.findFirst("javac").orElseThrow().run(System.out, System.err,
                    "--module-path", modulePath, "-d", out.resolve(name).toString(),
                    info.toString(), engine.toString());
            assertEquals(0, rc, "test module " + name + " must compile");
        }
        Path[] dirs = Arrays.stream(names).map(out::resolve).toArray(Path[]::new);
        Configuration cfg = ModuleLayer.boot().configuration()
                .resolve(ModuleFinder.of(dirs), ModuleFinder.of(), Set.of(names));
        return ModuleLayer.defineModulesWithOneLoader(cfg, List.of(ModuleLayer.boot()), getClass().getClassLoader()).layer();
    }

    private static String engineSource(String pkg) {
        return """
                package %s;
                import io.vidocq.foy.spi.jsp.JspEngine;
                import jakarta.servlet.*;
                import jakarta.servlet.http.*;
                public class TestEngine implements JspEngine {
                  public TestEngine() {
                    System.setProperty("test.jsp.created", String.valueOf(Integer.getInteger("test.jsp.created", 0) + 1));
                  }
                  public Application deploy(ServletContext context, ModuleLayer layer) {
                    System.getProperties().put("test.jsp.layer", layer);
                    return new Application() {
                      public Servlet servlet() { return servletFor(null); }
                      public Servlet servletFor(String file) {
                        return new HttpServlet() {
                          @Override protected void doGet(HttpServletRequest q, HttpServletResponse r) throws java.io.IOException {
                            r.getWriter().write("engine");
                          }
                        };
                      }
                      public void close() { }
                    };
                  }
                }
                """.formatted(pkg);
    }

    private static Path codeSource(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
