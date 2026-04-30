package io.vidocq.foy.tck.conformance;

import io.vidocq.foy.spi.security.AuthenticatedUser;
import io.vidocq.foy.tck.ServletTestHarness;
import jakarta.servlet.annotation.HttpConstraint;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Couverture conformance Servlet 6.1 §13 (security) via BASIC + @ServletSecurity. */
class ConformanceSecurityTest {

    @ServletSecurity(@HttpConstraint(rolesAllowed = "admin"))
    public static final class AdminServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("hello " + req.getRemoteUser());
        }
    }

    @Test
    void adminConstraintRejectsNonAdminAndAnonymous() throws Exception {
        try (var h = ServletTestHarness.builder()
                .servlet("/admin", new AdminServlet())
                .securityProvider((user, pass) -> {
                    Map<String, Set<String>> db = Map.of(
                            "alice:admin", Set.of("admin"),
                            "bob:user", Set.of("user"));
                    return db.entrySet().stream()
                            .filter(e -> e.getKey().equals(user + ":" + pass))
                            .findFirst()
                            .map(e -> new AuthenticatedUser(user,
                                    e.getValue()));
                })
                .start()) {
            assertEquals(401, h.get("/admin").statusCode());
            assertEquals(401, withAuth(h, "/admin", "alice", "wrong").statusCode());
            assertEquals(403, withAuth(h, "/admin", "bob", "user").statusCode());
            var ok = withAuth(h, "/admin", "alice", "admin");
            assertEquals(200, ok.statusCode());
            assertEquals("hello alice", ok.body());
        }
    }

    private static java.net.http.HttpResponse<String> withAuth(
            ServletTestHarness h, String path, String user, String pass) throws Exception {
        return h.send(h.request(path)
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8)))
                .GET().build());
    }

    @SuppressWarnings("unused")
    private static Optional<AuthenticatedUser> unused() { return Optional.empty(); }
}
