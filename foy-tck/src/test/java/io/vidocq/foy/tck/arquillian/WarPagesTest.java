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
package io.vidocq.foy.tck.arquillian;

import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.tck.ServletTestHarness;
import io.vidocq.ibarra.spi.PageIndex;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BUG-20261011-04: the JSP pages of a TCK war, compiled by Ibarra and served through Foy's JSP engine SPI. */
class WarPagesTest {

    @TempDir
    Path work;

    private final ClassLoader loader = WarPagesTest.class.getClassLoader();

    @Test
    void a_war_without_pages_gets_no_engine() throws Exception {
        WebArchive war = ShrinkWrap.create(WebArchive.class, "plain.war")
                .addAsWebResource(new StringAsset("<html/>"), "index.html");

        assertTrue(WarPages.compile(war, loader, work).isEmpty());
    }

    @Test
    void the_pages_index_comes_from_the_pages_module_and_matches_the_class_path_type() throws Exception {
        WebArchive war = ShrinkWrap.create(WebArchive.class, "pages.war")
                .addAsWebResource(new StringAsset("<%= \"hello from \" + request.getServletPath() %>"), "hello.jsp")
                .addClass(WarPagesTest.class);

        var pages = WarPages.compile(war, loader, work).orElseThrow();

        Module module = pages.layer().findModule(WarPages.MODULE).orElseThrow();
        var indexes = ServiceLoader.load(pages.layer(), PageIndex.class).stream().toList();
        assertEquals(1, indexes.size(), "one generated page index");
        assertEquals(module, indexes.getFirst().type().getModule());
        assertFalse(module.getPackages().contains(WarPagesTest.class.getPackageName()),
                "the war's classes stay with the test class loader");
    }

    @Test
    void foy_serves_a_compiled_page() throws Exception {
        WebArchive war = ShrinkWrap.create(WebArchive.class, "served.war")
                .addAsWebResource(new StringAsset("<%= \"hello from \" + request.getServletPath() %>"), "hello.jsp");
        var pages = WarPages.compile(war, loader, work).orElseThrow();

        try (var harness = ServletTestHarness.builder()
                .model(WebAppModel.builder("/served"))
                .jsp(pages.layer(), pages.engine())
                .start()) {
            var response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(harness.baseUrl() + "/hello.jsp")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            assertEquals("hello from /hello.jsp", response.body().strip());
        }
    }

    @Test
    void a_listener_declared_in_a_library_tld_is_compiled_in() throws Exception {
        JavaArchive library = ShrinkWrap.create(JavaArchive.class, "listeners.jar")
                .addAsResource(new StringAsset("""
                        <taglib xmlns="https://jakarta.ee/xml/ns/jakartaee" version="3.0">
                          <tlib-version>1.0</tlib-version>
                          <short-name>probe</short-name>
                          <listener><listener-class>%s</listener-class></listener>
                        </taglib>
                        """.formatted(TldProbeListener.class.getName())), "META-INF/probe.tld");
        WebArchive war = ShrinkWrap.create(WebArchive.class, "tld.war")
                .addAsLibrary(library)
                .addClass(TldProbeListener.class);

        var pages = WarPages.compile(war, loader, work).orElseThrow();

        var index = ServiceLoader.load(pages.layer(), PageIndex.class).findFirst().orElseThrow();
        assertEquals(1, index.listeners().size(), "the TLD listener reaches the page index");
    }
}
