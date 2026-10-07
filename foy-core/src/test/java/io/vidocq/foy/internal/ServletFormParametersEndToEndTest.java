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
package io.vidocq.foy.internal;

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.bridge.ChappeServletBridge;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServletFormParametersEndToEndTest {

    private Server server;
    private int port;

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

    private void start(HttpServlet servlet) {
        var dispatcher = new ServletDispatcher(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/form"), servlet, "Form")));
        var r = TestServerLauncher.start(new ChappeServletBridge(dispatcher, new VidocqServletContext("/"), "/"));
        server = r.server;
        port = r.port;
    }

    private String post(String query, String contentType, String body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/form" + query))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void urlEncodedBodyParametersAreVisibleAfterQueryParameters() throws Exception {
        start(new HttpServlet() {
            @Override protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setCharacterEncoding("UTF-8");
                resp.getWriter().write(Arrays.toString(req.getParameterValues("p")) + "|" + req.getParameter("q"));
            }
        });
        assertEquals("[fromQuery, fromBody]|café",
                post("?p=fromQuery", "application/x-www-form-urlencoded; charset=UTF-8", "p=fromBody&q=caf%C3%A9"));
    }

    @Test
    void bodyIsNotParsedForOtherContentTypes() throws Exception {
        start(new HttpServlet() {
            @Override protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getWriter().write(String.valueOf(req.getParameter("p")));
            }
        });
        assertEquals("null", post("", "text/plain", "p=x"));
    }

    @Test
    void streamReadFirstLeavesParametersQueryOnly() throws Exception {
        start(new HttpServlet() {
            @Override protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                String raw = new String(req.getInputStream().readAllBytes());
                resp.getWriter().write(raw + "|" + req.getParameter("p"));
            }
        });
        assertEquals("p=fromBody|null", post("", "application/x-www-form-urlencoded", "p=fromBody"));
    }

    @Test
    void consumedBodyIsEmptyForTheStream() throws Exception {
        start(new HttpServlet() {
            @Override protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                req.getParameter("p");
                resp.getWriter().write("[" + new String(req.getInputStream().readAllBytes()) + "]");
            }
        });
        assertEquals("[]", post("", "application/x-www-form-urlencoded", "p=fromBody"));
    }

    @Test
    void defaultBodyCharsetIsIso88591() throws Exception {
        start(new HttpServlet() {
            @Override protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setCharacterEncoding("UTF-8");
                resp.getWriter().write(req.getParameter("q"));
            }
        });
        assertEquals("café", post("", "application/x-www-form-urlencoded", "q=caf%E9"));
    }
}
