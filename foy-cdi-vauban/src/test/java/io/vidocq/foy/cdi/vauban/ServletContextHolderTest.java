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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletContext;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServletContextHolderTest {

    @Test
    void theFirstDeploymentKeepsTheContextAndTheWarningNamesTheRootSlash() {
        var holder = new ServletContextHolder();
        ServletContext root = contextAt("");
        ServletContext other = contextAt("/other");

        try (var log = LogRecords.of(ServletContextHolder.class.getName())) {
            holder.attach(root);
            holder.attach(other);

            assertSame(root, holder.context());
            assertEquals(List.of("The ServletContext bean stays the one of the application deployed at /: the "
                            + "application deployed at /other runs on the same CDI container and does not get its own. "
                            + "Foy on Vauban gives the Servlet built-in beans to one deployment per CDI container."),
                    log.warnings());
        }
    }

    @Test
    void onlyTheAttachedDeploymentDetachesIt() {
        var holder = new ServletContextHolder();
        ServletContext first = contextAt("/first");
        holder.attach(first);

        assertFalse(holder.detach(contextAt("/second")));
        assertSame(first, holder.context());
        assertTrue(holder.detach(first));
        var none = assertThrows(IllegalStateException.class, holder::context);
        assertEquals(ServletContextHolder.NO_CONTEXT, none.getMessage());
    }

    private static ServletContext contextAt(String path) {
        return (ServletContext) Proxy.newProxyInstance(ServletContextHolderTest.class.getClassLoader(),
                new Class<?>[] {ServletContext.class},
                (proxy, method, args) -> "getContextPath".equals(method.getName()) ? path : null);
    }
}
