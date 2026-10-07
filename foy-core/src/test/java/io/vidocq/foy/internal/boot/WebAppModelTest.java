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

import io.vidocq.foy.internal.boot.WebAppModel.*;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebAppModelTest {

    static final class S extends HttpServlet {}
    static final class F implements Filter {
        @Override public void doFilter(jakarta.servlet.ServletRequest q, jakarta.servlet.ServletResponse r,
                                       jakarta.servlet.FilterChain c) {}
    }

    static ServletDecl servlet(String name, String... patterns) {
        return new ServletDecl(name, S.class, S::new, List.of(patterns), Map.of(), Integer.MIN_VALUE, true);
    }

    @Test
    void keepsDeclarationOrderOfFilterMappings() {
        var m = WebAppModel.builder("/app")
                .filter(new FilterDecl("a", F.class, F::new, Map.of(), false))
                .filter(new FilterDecl("b", F.class, F::new, Map.of(), false))
                .filterMapping(new FilterMappingDecl("b", "/*", null, EnumSet.of(DispatcherType.REQUEST)))
                .filterMapping(new FilterMappingDecl("a", "/*", null, EnumSet.of(DispatcherType.REQUEST)))
                .build();
        assertEquals(List.of("b", "a"), m.filterMappings().stream().map(FilterMappingDecl::filterName).toList());
    }

    @Test
    void rejectsDuplicateServletNames() {
        var b = WebAppModel.builder("/").servlet(servlet("x", "/a")).servlet(servlet("x", "/b"));
        var e = assertThrows(IllegalStateException.class, b::build);
        assertTrue(e.getMessage().contains("x"));
    }

    @Test
    void rejectsMappingOfUnknownFilter() {
        var b = WebAppModel.builder("/")
                .filterMapping(new FilterMappingDecl("ghost", "/*", null, EnumSet.of(DispatcherType.REQUEST)));
        assertThrows(IllegalStateException.class, b::build);
    }

    @Test
    void isImmutableAgainstCallerCollections() {
        var patterns = new ArrayList<>(List.of("/a"));
        var params = new LinkedHashMap<String, String>(Map.of("k", "v"));
        var m = WebAppModel.builder("/")
                .servlet(new ServletDecl("s", S.class, S::new, patterns, params, 1, true)).build();
        patterns.add("/b");
        params.put("k2", "v2");
        assertEquals(List.of("/a"), m.servlets().getFirst().urlPatterns());
        assertEquals(Map.of("k", "v"), m.servlets().getFirst().initParams());
        assertThrows(UnsupportedOperationException.class, () -> m.servlets().add(servlet("t")));
    }

    @Test
    void defaultsMatchTheHarnessDefaults() {
        var m = WebAppModel.builder("/").build();
        assertEquals(-1, m.sessionTimeoutMinutes());
        assertEquals(6, m.effectiveMajorVersion());
        assertEquals(1, m.effectiveMinorVersion());
        assertNotNull(m.errorPages());
    }
}
