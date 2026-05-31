package io.vidocq.foy.internal;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Server;

import java.net.ServerSocket;

/**
 * Test-only helper which starts a {@link Server} Chappe on a free port,
 * with retry to absorb port-allocation races typical of tests
 * (a port obtained via {@link ServerSocket} can be taken before the bind).
 */
final class TestServerLauncher {

    static final class Result {
        final Server server;
        final int port;
        Result(Server server, int port) { this.server = server; this.port = port; }
    }

    static Result start(Handler handler) {
        RuntimeException last = null;
        for (int i = 0; i < 5; i++) {
            int port;
            try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
            catch (Exception e) { throw new RuntimeException(e); }
            try {
                Server server = Server.builder().host("127.0.0.1").port(port).handler(handler).build();
                server.start();
                return new Result(server, port);
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last;
    }

    private TestServerLauncher() {}
}
