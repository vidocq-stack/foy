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
package io.vidocq.foy.internal.bridge;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Parameters seen by the target of a forward or an include (Servlet 6.1 section 9.1.1): the
 * parameters of the dispatch query string come first, followed by the original values of the
 * same name; the other original parameters are unchanged. The original request keeps its own
 * parameters, so they revert once the dispatch returns.
 */
final class DispatchParameters {

    private DispatchParameters() {}

    /**
     * @param original    the caller's parameters (query string and form body)
     * @param queryString the dispatch query string, may be {@code null}
     * @return an unmodifiable merged view; {@code original} itself when the query adds nothing
     */
    static Map<String, String[]> merge(Map<String, String[]> original, String queryString, Charset charset) {
        Map<String, List<String>> query = parse(queryString, charset);
        if (query.isEmpty()) return original;
        var out = new LinkedHashMap<String, String[]>();
        for (var e : query.entrySet()) {
            List<String> values = new ArrayList<>(e.getValue());
            String[] previous = original.get(e.getKey());
            if (previous != null) values.addAll(Arrays.asList(previous));
            out.put(e.getKey(), values.toArray(new String[0]));
        }
        for (var e : original.entrySet()) out.putIfAbsent(e.getKey(), e.getValue().clone());
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, List<String>> parse(String qs, Charset charset) {
        var out = new LinkedHashMap<String, List<String>>();
        if (qs == null || qs.isEmpty()) return out;
        for (String pair : qs.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            if (k.isEmpty()) continue;
            try {
                k = URLDecoder.decode(k, charset);
                v = URLDecoder.decode(v, charset);
            } catch (IllegalArgumentException malformed) {
                continue; // malformed %-encoding: skip this pair only, like the request parser
            }
            out.computeIfAbsent(k, _ -> new ArrayList<>()).add(v);
        }
        return out;
    }

    /** The merged parameters of one dispatch, computed on first use and only once. */
    static final class View {
        private final Supplier<Map<String, String[]>> original;
        private final String queryString;
        private final Charset charset;
        private Map<String, String[]> merged;

        View(Supplier<Map<String, String[]>> original, String queryString, Charset charset) {
            this.original = original;
            this.queryString = queryString;
            this.charset = charset;
        }

        Map<String, String[]> map() {
            Map<String, String[]> m = merged;
            if (m == null) merged = m = merge(original.get(), queryString, charset);
            return m;
        }

        String first(String name) {
            String[] v = map().get(name);
            return v == null || v.length == 0 ? null : v[0];
        }

        String[] values(String name) {
            String[] v = map().get(name);
            return v == null ? null : v.clone();
        }

        java.util.Enumeration<String> names() {
            return Collections.enumeration(map().keySet());
        }
    }

    /**
     * Section 9.1: a relative dispatcher path is resolved against the current request path
     * ({@code servletPath + pathInfo}); returns the context-relative absolute path.
     */
    static String resolveRelative(String servletPath, String pathInfo, String path) {
        String current = (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
        int slash = current.lastIndexOf('/');
        String parent = slash <= 0 ? "/" : current.substring(0, slash + 1);
        return parent + path;
    }
}
