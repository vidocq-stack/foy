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
import io.vidocq.foy.spi.security.IdentityStore;

import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Optional;

/** Calls an {@link IdentityStore} the way every Foy mechanism must: fail closed, wipe, log no credential. */
public final class CredentialCheck {

    private static final System.Logger LOG = System.getLogger(CredentialCheck.class.getName());

    private CredentialCheck() {}

    /**
     * Validates {@code username}/{@code password} and wipes {@code password}, whatever the outcome.
     * A store that throws or returns {@code null} refuses. The refusal is logged at WARNING with the
     * store class and the exception class only, never the user name, the password or the message.
     */
    public static Optional<Identity> validate(IdentityStore store, String username, char[] password) {
        try {
            if (username == null || password == null) return Optional.empty();
            Optional<Identity> result = store.validate(username, password);
            return result == null ? Optional.empty() : result;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "identity store {0} failed with {1}; the login is refused",
                    store.getClass().getName(), e.getClass().getName());
            return Optional.empty();
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    /** {@link IdentityStore#validate(X509Certificate[])}, with a failing or {@code null}-returning store read as a refusal. */
    public static Optional<Identity> validate(IdentityStore store, X509Certificate[] chain) {
        try {
            if (chain == null || chain.length == 0) return Optional.empty();
            Optional<Identity> result = store.validate(chain.clone());
            return result == null ? Optional.empty() : result;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "identity store {0} failed with {1}; the certificate is refused",
                    store.getClass().getName(), e.getClass().getName());
            return Optional.empty();
        }
    }
}
