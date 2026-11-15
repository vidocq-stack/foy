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

import io.vidocq.chappe.api.Server;
import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code getContext} of a URI under the application's own path, from a request its undeploy waits for
 * (Servlet 6.1 section 4.8, BUG-20261115-07). The undeploy takes the context out of the cross-context
 * registry at its start, so that other applications no longer find it (BUG-20261115-04); the application
 * itself still resolves its own path to its own context, neither to {@code null} nor to the root
 * application, and a context deployed under a longer path still answers for the URIs under that path.
 */
@Timeout(30)
class OwnPathLookupEndToEndTest {

    private static final String OWN = "/own";
    private static final String SELF = "this";

    /** Held in service until the test releases it, then answers what {@code getContext} gives for each {@code uri}. */
    public static final class LookupServlet extends HttpServlet {
        private final transient CountDownLatch entered;
        private final transient CountDownLatch release;

        LookupServlet(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            entered.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            ServletContext self = getServletContext();
            var answers = new StringJoiner(",");
            for (String uri : req.getParameterValues("uri")) answers.add(describe(self, self.getContext(uri)));
            resp.getWriter().write(answers.toString());
        }

        private static String describe(ServletContext self, ServletContext found) {
            if (found == null) return "none";
            // A context is equal to itself only: equals is identity here.
            return found.equals(self) ? SELF : found.getContextPath();
        }
    }

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch undeployBegan = new CountDownLatch(1);
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Deployment> others = new ArrayList<>();
    private Deployment own;
    private Server server;

    @AfterEach
    void tearDown() {
        release.countDown();
        if (own != null) own.close();
        others.forEach(Deployment::close);
        if (server != null) server.stop();
        client.close();
    }

    /** Its own path, with or without a trailing slash, and a URI under it: this context, not {@code null}. */
    @Test
    void duringItsUndeployAnApplicationResolvesItsOwnPathToItself() throws Exception {
        assertEquals("this,this,this", lookupDuringTheUndeploy(OWN, OWN + "/", OWN + "/page"));
    }

    /** The root application's path is a prefix of every URI: it does not take the application's own. */
    @Test
    void duringItsUndeployItsOwnPathIsNotTheRootApplications() throws Exception {
        deployOther("/");
        assertEquals("this,this", lookupDuringTheUndeploy(OWN, OWN + "/page"));
    }

    /** {@code /own/sub} answers for its URIs; {@code /own/subway} is not under it, but under {@code /own}. */
    @Test
    void aContextDeployedUnderALongerPathStillAnswersForItsUris() throws Exception {
        deployOther(OWN + "/sub");
        assertEquals("/own/sub,/own/sub,this",
                lookupDuringTheUndeploy(OWN + "/sub", OWN + "/sub/page", OWN + "/subway"));
    }

    private void deployOther(String contextPath) {
        others.add(WebAppDeployer.deploy(WebAppModel.builder(contextPath).build(),
                DeployOptions.defaults(getClass().getClassLoader())));
    }

    /**
     * Deploys and serves {@code /own}, holds a request in its servlet, starts the undeploy, and once the
     * undeploy left the registry, lets the request look {@code uris} up: what it answered, in order.
     */
    private String lookupDuringTheUndeploy(String... uris)
            throws InterruptedException, ExecutionException, TimeoutException {
        var servlet = new ServletDecl("lookup", LookupServlet.class, () -> new LookupServlet(entered, release),
                List.of("/lookup"), Map.of(), -1, true);
        own = WebAppDeployer.deploy(WebAppModel.builder(OWN).servlet(servlet).build(),
                DeployOptions.defaults(getClass().getClassLoader()));
        // The undeploy closes the upgraded connections once it refused requests and left the registry: this
        // one tells the test so.
        assertTrue(own.servletContext().registerUpgradedConnection(undeployBegan::countDown));
        var started = io.vidocq.foy.internal.TestServerLauncherAccess.start(own.handler());
        server = started.server();
        String query = Arrays.stream(uris).map(uri -> "uri=" + URLEncoder.encode(uri, StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        CompletableFuture<HttpResponse<String>> response = client.sendAsync(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + started.port() + OWN + "/lookup?" + query)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the request did not reach the servlet");
        Thread undeploy = Thread.ofVirtual().start(own::close);
        assertTrue(undeployBegan.await(5, TimeUnit.SECONDS), "the undeploy did not begin");
        release.countDown();
        HttpResponse<String> answered = response.get(5, TimeUnit.SECONDS);
        assertTrue(undeploy.join(Duration.ofSeconds(5)), "the undeploy did not return");
        assertEquals(200, answered.statusCode(), answered.body());
        return answered.body();
    }
}
