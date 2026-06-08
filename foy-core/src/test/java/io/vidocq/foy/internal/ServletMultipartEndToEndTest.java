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
import io.vidocq.foy.internal.dispatcher.FilterRegistry;
import io.vidocq.foy.internal.dispatcher.ServletDispatcher;
import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServletMultipartEndToEndTest {

    private Server server;
    private int port;

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

    @Test
    void uploadsFileAndFormField() throws Exception {
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setContentType("text/plain");
                try {
                    Part field = req.getPart("desc");
                    Part file = req.getPart("upload");
                    String text = new String(field.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    String content = new String(file.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    resp.getWriter().write(text + "|" + file.getSubmittedFileName()
                            + "(" + file.getContentType() + "):" + content);
                } catch (Exception e) { throw new IOException(e); }
            }
        };
        var ctx = new VidocqServletContext("/");
        var bridge = new ChappeServletBridge(
                new ServletDispatcher(List.of(new ServletDispatcher.Mapping(
                        UrlPatternMatcher.of("/upload"), s, "U"))),
                new FilterRegistry(List.of()), ctx, null, "/");
        var r = TestServerLauncher.start(bridge);
        server = r.server;
        port = r.port;

        String boundary = "CHAPPE-TEST-BOUNDARY";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"desc\"\r\n\r\n"
                + "release notes\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"upload\"; filename=\"changelog.txt\"\r\n"
                + "Content-Type: text/markdown\r\n\r\n"
                + "# M2g\r\nmultipart support\r\n"
                + "--" + boundary + "--\r\n";

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/upload"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .timeout(Duration.ofSeconds(3))
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertEquals("release notes|changelog.txt(text/markdown):# M2g\r\nmultipart support",
                resp.body());
    }

    @Test
    void getPartsReturnsEmptyWhenNotMultipart() throws Exception {
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                try {
                    resp.getWriter().write(Integer.toString(req.getParts().size()));
                } catch (Exception e) { throw new IOException(e); }
            }
        };
        var ctx = new VidocqServletContext("/");
        var bridge = new ChappeServletBridge(
                new ServletDispatcher(List.of(new ServletDispatcher.Mapping(
                        UrlPatternMatcher.of("/p"), s, "P"))),
                new FilterRegistry(List.of()), ctx, null, "/");
        var r = TestServerLauncher.start(bridge);
        server = r.server;
        port = r.port;

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/p"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(3))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("0", resp.body());
    }
}
