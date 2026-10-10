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
package io.vidocq.foy.internal.security;

import io.vidocq.foy.spi.security.Identity;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Role names of one web application (Servlet 6.1 sections 13.3 and 13.5): the declared roles
 * ({@code <security-role>}, {@code @DeclareRoles}, {@code ServletContext.declareRoles}) and each
 * servlet's {@code security-role-ref}s.
 */
public final class RoleMapping {

    public static final RoleMapping EMPTY = new RoleMapping(Set.of(), Map.of());

    private final Set<String> declaredRoles;
    private final Map<String, Map<String, String>> roleRefs;

    public RoleMapping(Set<String> declaredRoles, Map<String, Map<String, String>> roleRefs) {
        this.declaredRoles = Set.copyOf(declaredRoles);
        var refs = new LinkedHashMap<String, Map<String, String>>();
        roleRefs.forEach((servlet, m) -> refs.put(servlet, Map.copyOf(m)));
        this.roleRefs = Map.copyOf(refs);
    }

    public Set<String> declaredRoles() { return declaredRoles; }

    public RoleMapping withDeclaredRoles(Collection<String> more) {
        var all = new LinkedHashSet<>(declaredRoles);
        all.addAll(more);
        return new RoleMapping(all, roleRefs);
    }

    /**
     * {@code isUserInRole(role)} for the servlet {@code servletName} now executing ({@code null}
     * outside any servlet, e.g. in an async {@code start} thread). The servlet's
     * {@code security-role-ref} with that role-name is followed to its role-link; otherwise the
     * role is tested as it is. {@code "*"} is never a role. {@code "**"} means "authenticated",
     * unless the application declares a role named {@code "**"}.
     */
    public boolean isUserInRole(String servletName, Identity identity, String role) {
        if (identity == null || role == null || "*".equals(role)) return false;
        if ("**".equals(role) && !declaredRoles.contains("**")) return true;
        String linked = servletName == null ? null : roleRefs.getOrDefault(servletName, Map.of()).get(role);
        return identity.hasRole(linked != null ? linked : role);
    }
}
