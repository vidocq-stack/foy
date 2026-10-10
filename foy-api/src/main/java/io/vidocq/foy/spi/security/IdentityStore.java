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

import java.util.Optional;

/**
 * Validates a user name and a password against the embedder's user base: the container "realm"
 * of Servlet 6.1 section 13.6. Foy calls it for BASIC, FORM and {@code HttpServletRequest.login}.
 *
 * <p>Implementations must:</p>
 * <ul>
 *   <li>never log, keep or echo the password; Foy wipes the array as soon as {@code validate}
 *       returns;</li>
 *   <li>compare secrets in constant time ({@link java.security.MessageDigest#isEqual}) and spend the
 *       same work on an unknown user as on a wrong password;</li>
 *   <li>return {@link Optional#empty()} on any refusal. Foy treats an exception or a {@code null}
 *       result as a refusal;</li>
 *   <li>never put the user name or the password in the message of an exception or error they
 *       throw: Foy logs only the class of a {@link RuntimeException}, but an {@link Error} escapes
 *       and may be logged with its message.</li>
 * </ul>
 */
@FunctionalInterface
public interface IdentityStore {

    Optional<Identity> validate(String username, char[] password);

    /** A store that refuses everyone: the default when the embedder configures none. */
    static IdentityStore none() {
        return (username, password) -> Optional.empty();
    }
}
