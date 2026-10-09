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
package io.vidocq.foy.internal.dispatcher;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Servlet 6.1 section 6.2.4: filter chains built from URL-pattern and servlet-name mappings. */
class FilterRegistryServletNameTest {

    private static final Set<DispatcherType> REQUEST = EnumSet.of(DispatcherType.REQUEST);

    private static Filter filter() { return (req, res, chain) -> chain.doFilter(req, res); }

    private static FilterMapping url(String pattern, Filter f, String name) {
        return FilterMapping.onRequest(UrlPatternMatcher.of(pattern), f, name);
    }

    private static FilterMapping byName(String servletName, Filter f, String name, Set<DispatcherType> types) {
        return FilterMapping.forServletName(servletName, f, name, types, false);
    }

    @Test
    void filterMappedByUrlAndByServletNameRunsOnce() {
        Filter f = filter();
        var reg = new FilterRegistry(List.of(url("/x", f, "f"), byName("a", f, "f", REQUEST)));
        assertEquals(List.of(f), reg.chainFor("/x", DispatcherType.REQUEST, "a"));
    }

    @Test
    void filterWithTwoMatchingUrlPatternsRunsOnce() {
        Filter f = filter();
        var reg = new FilterRegistry(List.of(url("/x/*", f, "f"), url("/x/y", f, "f")));
        assertEquals(List.of(f), reg.chainFor("/x/y", DispatcherType.REQUEST, "a"));
    }

    @Test
    void urlPatternMatchesComeBeforeServletNameMatches() {
        Filter byServlet = filter();
        Filter byUrl = filter();
        Filter byUrl2 = filter();
        var reg = new FilterRegistry(List.of(
                byName("a", byServlet, "byServlet", REQUEST),
                url("/*", byUrl, "byUrl"),
                url("/x", byUrl2, "byUrl2")));
        assertEquals(List.of(byUrl, byUrl2, byServlet), reg.chainFor("/x", DispatcherType.REQUEST, "a"));
    }

    @Test
    void servletNameFilterNotAppliedWhenAnotherServletServesThePath() {
        Filter f = filter();
        var reg = new FilterRegistry(List.of(byName("a", f, "f", REQUEST)));
        assertTrue(reg.chainFor("/x/y", DispatcherType.REQUEST, "b").isEmpty());
        assertTrue(reg.chainFor("/x/y", DispatcherType.REQUEST, null).isEmpty());
    }

    @Test
    void starServletNameMatchesEveryServlet() {
        Filter f = filter();
        var reg = new FilterRegistry(List.of(byName("*", f, "f", REQUEST)));
        assertEquals(List.of(f), reg.chainFor("/x", DispatcherType.REQUEST, "a"));
        assertEquals(List.of(f), reg.chainFor("/y", DispatcherType.REQUEST, "b"));
        assertTrue(reg.chainFor("/z", DispatcherType.REQUEST, null).isEmpty());
    }

    @Test
    void servletNameMappingHonoursDispatcherTypes() {
        Filter f = filter();
        var reg = new FilterRegistry(List.of(byName("a", f, "f", EnumSet.of(DispatcherType.FORWARD))));
        assertTrue(reg.chainFor("/x", DispatcherType.REQUEST, "a").isEmpty());
        assertEquals(List.of(f), reg.chainFor("/x", DispatcherType.FORWARD, "a"));
    }

    @Test
    void namedDispatchUsesOnlyServletNameMappings() {
        Filter byUrl = filter();
        Filter byServlet = filter();
        var reg = new FilterRegistry(List.of(
                new FilterMapping(UrlPatternMatcher.of("/*"), byUrl, "byUrl", EnumSet.of(DispatcherType.FORWARD)),
                byName("a", byServlet, "byServlet", EnumSet.of(DispatcherType.FORWARD))));
        assertEquals(List.of(byServlet), reg.chainFor(null, DispatcherType.FORWARD, "a"));
    }

    @Test
    void asyncSupportConsidersServletNameMappings() {
        Filter f = filter();
        var reg = new FilterRegistry(List.of(byName("a", f, "f", REQUEST)));
        assertFalse(reg.asyncSupported("/x", DispatcherType.REQUEST, "a"));
        assertTrue(reg.asyncSupported("/x", DispatcherType.REQUEST, "b"));
    }
}
