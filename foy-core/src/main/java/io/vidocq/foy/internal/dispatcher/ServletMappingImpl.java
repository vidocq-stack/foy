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

import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.MappingMatch;

/**
 * Immutable {@link HttpServletMapping} (Servlet 6.1 section 12.2).
 *
 * @param matchValue   the part of the request path that matched, without leading slash
 * @param pattern      the url-pattern that matched
 * @param servletName  the name of the matched servlet
 * @param mappingMatch the kind of match
 */
public record ServletMappingImpl(String matchValue, String pattern, String servletName,
                                 MappingMatch mappingMatch) implements HttpServletMapping {
    @Override public String getMatchValue() { return matchValue; }
    @Override public String getPattern() { return pattern; }
    @Override public String getServletName() { return servletName; }
    @Override public MappingMatch getMappingMatch() { return mappingMatch; }
}
