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

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServletDispatcherTest {

    @Test
    void exactMatchWinsOverPrefix() {
        var servletExact = stub("A");
        var servletPrefix = stub("B");
        var dispatcher = new ServletDispatcher(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/api/*"), servletPrefix, "B"),
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/api/users"), servletExact, "A")
        ));
        assertEquals("A", dispatcher.find("/api/users").orElseThrow().servletName());
        assertEquals("B", dispatcher.find("/api/users/42").orElseThrow().servletName());
    }

    @Test
    void longerPrefixWins() {
        var shallow = stub("S");
        var deep = stub("D");
        var dispatcher = new ServletDispatcher(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/a/*"), shallow, "S"),
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/a/b/*"), deep, "D")
        ));
        assertEquals("D", dispatcher.find("/a/b/c").orElseThrow().servletName());
        assertEquals("S", dispatcher.find("/a/x").orElseThrow().servletName());
    }

    @Test
    void extensionMatchesWhenNoPrefix() {
        var jsp = stub("JSP");
        var dispatcher = new ServletDispatcher(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("*.jsp"), jsp, "JSP")));
        assertEquals("JSP", dispatcher.find("/foo/index.jsp").orElseThrow().servletName());
        assertTrue(dispatcher.find("/foo/index.html").isEmpty());
    }

    @Test
    void defaultServletCatchesAll() {
        var def = stub("DEF");
        var exact = stub("E");
        var dispatcher = new ServletDispatcher(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/"), def, "DEF"),
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/foo"), exact, "E")
        ));
        assertEquals("E", dispatcher.find("/foo").orElseThrow().servletName());
        assertEquals("DEF", dispatcher.find("/bar").orElseThrow().servletName());
    }

    @Test
    void findReturnsEmptyWhenNoMatch() {
        var dispatcher = new ServletDispatcher(List.of(
                new ServletDispatcher.Mapping(UrlPatternMatcher.of("/foo"), stub("F"), "F")));
        assertTrue(dispatcher.find("/bar").isEmpty());
    }

    private static Servlet stub(String name) {
        return new Servlet() {
            @Override public void init(ServletConfig config) {}
            @Override public ServletConfig getServletConfig() { return null; }
            @Override public void service(ServletRequest req, ServletResponse res) {}
            @Override public String getServletInfo() { return name; }
            @Override public void destroy() {}
        };
    }
}
