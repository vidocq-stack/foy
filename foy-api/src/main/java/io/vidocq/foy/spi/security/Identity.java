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
package io.vidocq.foy.spi.security;

import java.security.Principal;
import java.util.Objects;
import java.util.Set;

/**
 * The outcome of a successful credential validation: the caller's principal and the application
 * roles it holds (Servlet 6.1 section 13.3). The roles are application role names, used as they
 * are by {@code isUserInRole} (after the servlet's {@code security-role-ref}s) and by the security
 * constraints; Foy maps no group.
 */
public record Identity(Principal principal, Set<String> roles) {

    public Identity {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(principal.getName(), "principal name");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    /** An identity whose principal is a {@link NamedPrincipal} called {@code name}. */
    public static Identity of(String name, Set<String> roles) {
        return new Identity(new NamedPrincipal(name), roles);
    }

    /** The principal's name: the value of {@code getRemoteUser()}. */
    public String name() { return principal.getName(); }

    public boolean hasRole(String role) { return role != null && roles.contains(role); }

    /** A principal that carries only a name. */
    public record NamedPrincipal(String name) implements Principal {
        public NamedPrincipal { Objects.requireNonNull(name, "name"); }
        @Override public String getName() { return name; }
        @Override public String toString() { return name; }
    }
}
