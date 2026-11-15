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
package io.vidocq.foy.internal.container;

import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code ServletContext.getContext} follows one rule for every caller (Servlet 6.1 section 4.8, BUG-20261115-07):
 * the URI is matched against the context roots of the deployed applications on segment boundaries, the longest
 * root wins, and the root application holds every URI no other root does. The one exception: an application
 * being undeployed has left the registry, so the others no longer find it (BUG-20261115-04), yet it still counts
 * as deployed for its own lookups.
 */
class GetContextTest {

    /** The applications of every row; the root one only when the row says so. */
    private static final List<String> ROOTS = List.of("/", "/a", "/a/sub", "/b");

    private final Map<String, VidocqServletContext> contexts = new HashMap<>();

    @AfterEach
    void unregister() {
        contexts.values().forEach(CrossContextRegistry::unregister);
    }

    /**
     * A row: whether the root application is deployed, the application being undeployed ({@code -}: none), the
     * calling application, the URI it asks for ({@code -}: {@code null}), and the context path it gets
     * ({@code none}: {@code null}).
     */
    @ParameterizedTest(name = "root {0}, undeploying {1}: {2} asks {3}")
    @CsvSource(nullValues = "-", value = {
        // The same answer whoever asks.
        "true,  -,  /,      /b/x,        /b",
        "true,  -,  /a,     /b/x,        /b",
        "true,  -,  /a/sub, /b/x,        /b",
        "true,  -,  /b,     /b/x,        /b",
        "true,  -,  /,      /nothing,    /",
        "true,  -,  /a,     /nothing,    /",
        "true,  -,  /b,     /nothing,    /",
        "true,  -,  /,      /a/x,        /a",
        "true,  -,  /a/sub, /a/x,        /a",
        "true,  -,  /b,     /a/sub/page, /a/sub",
        "true,  -,  /a,     /a/sub,      /a/sub",
        // Segment boundaries: /a/subway is under /a, /ab is under no root but /.
        "true,  -,  /a,     /a/subway,   /a",
        "true,  -,  /a,     /ab,         /",
        "true,  -,  /a,     /a/,         /a",
        "true,  -,  /b,     /,           /",
        // No root application: a URI under no root is under nothing.
        "false, -,  /a,     /nothing,    none",
        "false, -,  /a,     /b/x,        /b",
        "false, -,  /a,     /,           none",
        "false, -,  /a,     relative,    none",
        "false, -,  /a,     -,           none",
        "true,  -,  /,      relative,    none",
        "true,  -,  /,      '',          none",
        "true,  -,  /,      -,           none",
        // The application being undeployed still finds itself; the others get what remains.
        "true,  /b, /b,     /b/x,        /b",
        "true,  /b, /b,     /b,          /b",
        "true,  /b, /a,     /b/x,        /",
        "false, /b, /a,     /b/x,        none",
        "true,  /a, /a,     /a/x,        /a",
        "true,  /a, /a,     /a/sub/x,    /a/sub",
        "true,  /a, /a/sub, /a/x,        /",
        "true,  /,  /,      /nothing,    /",
        "true,  /,  /,      /a/x,        /a",
        "true,  /,  /b,     /nothing,    none",
    })
    void oneRuleForEveryCaller(boolean rootDeployed, String undeploying, String caller, String uri, String expected) {
        for (String root : ROOTS) {
            if (rootDeployed || !"/".equals(root)) {
                var context = new VidocqServletContext(root);
                contexts.put(root, context);
                if (!root.equals(undeploying)) CrossContextRegistry.register(context);
            }
        }
        assertEquals(expected, name(contexts.get(caller).getContext(uri)));
    }

    /** The registry itself answers {@code null} for a URI no context can hold, whatever is registered. */
    @Test
    void theRegistryHoldsNoUriThatDoesNotStartWithASlash() {
        var root = new VidocqServletContext("/");
        contexts.put("/", root);
        CrossContextRegistry.register(root);
        assertNull(CrossContextRegistry.lookup(null));
        assertNull(CrossContextRegistry.lookup("relative"));
        assertNull(CrossContextRegistry.lookup(""));
    }

    private static String name(ServletContext found) {
        if (found == null) return "none";
        return found.getContextPath().isEmpty() ? "/" : found.getContextPath();
    }
}
