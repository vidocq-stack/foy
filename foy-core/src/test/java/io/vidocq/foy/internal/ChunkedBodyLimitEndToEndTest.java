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
import io.vidocq.foy.internal.http.RequestLimits;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Chappe bounds Content-Length bodies only; Foy bounds the others (maxChunkedRequestBodySize). */
class ChunkedBodyLimitEndToEndTest {

    private Server server;
    private int port;

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

    private void start(long cap) {
        HttpServlet s = new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                String outcome;
                try {
                    outcome = Integer.toString(req.getInputStream().readAllBytes().length);
                } catch (IOException e) {
                    outcome = e.getClass().getSimpleName();
                }
                resp.getWriter().write(outcome);
            }
        };
        var ctx = new VidocqServletContext("/");
        ctx.setRequestLimits(RequestLimits.DEFAULTS.withMaxChunkedRequestBodySize(cap));
        var bridge = new ChappeServletBridge(
                new ServletDispatcher(List.of(new ServletDispatcher.Mapping(UrlPatternMatcher.of("/body"), s, "B"))),
                new FilterRegistry(List.of()), TestSecurity.permitAll(ctx), null, "/");
        var r = TestServerLauncher.start(bridge);
        server = r.server;
        port = r.port;
    }

    private String post(HttpRequest.BodyPublisher body) throws Exception {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/body"))
                        .timeout(Duration.ofSeconds(5)).POST(body).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void chunkedBodyAboveTheCapFailsTheRead() throws Exception {
        start(1024);
        assertEquals("RequestBodyTooLargeException",
                post(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[4096]))));
    }

    @Test
    void chunkedBodyWithinTheCapIsRead() throws Exception {
        start(1024);
        assertEquals("1000",
                post(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[1000]))));
    }

    @Test
    void aSizedBodyIsLeftToChappesLimit() throws Exception {
        start(1024);
        assertEquals("4096", post(HttpRequest.BodyPublishers.ofByteArray(new byte[4096])));
    }
}
