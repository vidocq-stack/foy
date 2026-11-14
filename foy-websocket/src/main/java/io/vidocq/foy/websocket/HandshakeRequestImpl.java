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
package io.vidocq.foy.websocket;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.websocket.Extension;
import jakarta.websocket.server.HandshakeRequest;

import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The servlet request of a handshake, as the configurator and the session see it. The headers,
 * the request URI, the query string and the parameters are read once, at construction; the
 * HTTP session, the principal and the roles are asked of the request, which the configurator
 * calls run within.
 */
final class HandshakeRequestImpl implements HandshakeRequest {

    private final HttpServletRequest request;
    private final Map<String, List<String>> headers;
    private final String queryString;
    private final URI requestUri;
    private final Map<String, List<String>> parameters;

    /**
     * Reads {@code request}.
     *
     * @throws IllegalArgumentException when the {@code Host} header and the request target do not
     *                                  form a URI (a malformed handshake)
     */
    HandshakeRequestImpl(HttpServletRequest request) {
        this.request = request;
        this.headers = headersOf(request);
        this.queryString = request.getQueryString();
        this.requestUri = requestUriOf(request, queryString);
        this.parameters = parametersOf(request);
    }

    /** Every header line, by name ignoring case; read-only. */
    @Override
    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    @Override
    public Principal getUserPrincipal() {
        return request.getUserPrincipal();
    }

    /**
     * The absolute request URI: {@code ws} or {@code wss}, the {@code Host} header, the request
     * target as received (context path included, not decoded) and the query string. Atmosphere
     * derives its path info from this path.
     */
    @Override
    public URI getRequestURI() {
        return requestUri;
    }

    @Override
    public boolean isUserInRole(String role) {
        return request.isUserInRole(role);
    }

    /** The HTTP session the request belongs to, {@code null} when there is none: the handshake creates none. */
    @Override
    public Object getHttpSession() {
        return request.getSession(false);
    }

    @Override
    public Map<String, List<String>> getParameterMap() {
        return parameters;
    }

    @Override
    public String getQueryString() {
        return queryString;
    }

    /** The subprotocols the client asked for, in its order of preference. */
    List<String> requestedSubprotocols() {
        return Handshake.tokens(lines(SEC_WEBSOCKET_PROTOCOL));
    }

    /** The extensions the client offered, with their parameters (a parameter without value has a {@code null} one). */
    List<Extension> requestedExtensions() {
        var offers = new ArrayList<Extension>();
        for (String offer : Handshake.tokens(lines(SEC_WEBSOCKET_EXTENSIONS))) {
            String[] parts = offer.split(";");
            var offerParameters = new ArrayList<Extension.Parameter>();
            for (int i = 1; i < parts.length; i++) {
                String[] pair = parts[i].split("=", 2);
                if (!pair[0].isBlank()) {
                    offerParameters.add(new OfferedParameter(pair[0].trim(), pair.length == 2 ? pair[1].trim() : null));
                }
            }
            offers.add(new OfferedExtension(parts[0].trim(), List.copyOf(offerParameters)));
        }
        return List.copyOf(offers);
    }

    private Enumeration<String> lines(String header) {
        return Collections.enumeration(headers.getOrDefault(header, List.of()));
    }

    private static Map<String, List<String>> headersOf(HttpServletRequest request) {
        var all = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            all.computeIfAbsent(names.nextElement(), name -> List.copyOf(Collections.list(request.getHeaders(name))));
        }
        return Collections.unmodifiableMap(all);
    }

    private static URI requestUriOf(HttpServletRequest request, String query) {
        String host = request.getHeader("Host");
        if (host == null) {
            String name = request.getServerName();
            host = (name.indexOf(':') >= 0 ? "[" + name + "]" : name) + ":" + request.getServerPort();
        }
        return URI.create((request.isSecure() ? "wss://" : "ws://") + host + request.getRequestURI()
                + (query == null ? "" : "?" + query));
    }

    private static Map<String, List<String>> parametersOf(HttpServletRequest request) {
        var all = new LinkedHashMap<String, List<String>>();
        request.getParameterMap().forEach((name, values) -> all.put(name, List.of(values)));
        return Collections.unmodifiableMap(all);
    }

    private record OfferedExtension(String name, List<Extension.Parameter> parameters) implements Extension {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public List<Extension.Parameter> getParameters() {
            return parameters;
        }
    }

    private record OfferedParameter(String name, String value) implements Extension.Parameter {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getValue() {
            return value;
        }
    }
}
