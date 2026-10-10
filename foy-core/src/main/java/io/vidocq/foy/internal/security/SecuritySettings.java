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

import io.vidocq.foy.spi.security.IdentityStore;

/**
 * Container-side security settings of a deployment, chosen by the embedder.
 *
 * @param identityStore          user base behind BASIC, FORM and {@code login()}; {@code null} means
 *                               {@link IdentityStore#none()}
 * @param confidentialPort       HTTPS port a CONFIDENTIAL/INTEGRAL constraint redirects a plain request
 *                               to; {@code -1}: such a request gets 403
 * @param rotateSessionIdOnLogin whether a successful login changes the session id
 */
public record SecuritySettings(IdentityStore identityStore, int confidentialPort, boolean rotateSessionIdOnLogin) {

    public static final SecuritySettings DEFAULTS = new SecuritySettings(IdentityStore.none(), -1, true);

    public SecuritySettings {
        if (identityStore == null) identityStore = IdentityStore.none();
        checkConfidentialPort(confidentialPort);
    }

    /**
     * Rejects anything but {@code -1} or a TCP port.
     *
     * @throws IllegalArgumentException when {@code port} is neither
     */
    public static int checkConfidentialPort(int port) {
        if (port != -1 && (port < 1 || port > 65_535)) {
            throw new IllegalArgumentException("confidentialPort must be -1 or a TCP port: " + port);
        }
        return port;
    }

    public SecuritySettings withIdentityStore(IdentityStore s) {
        return new SecuritySettings(s, confidentialPort, rotateSessionIdOnLogin);
    }

    public SecuritySettings withConfidentialPort(int port) {
        return new SecuritySettings(identityStore, port, rotateSessionIdOnLogin);
    }

    public SecuritySettings withRotateSessionIdOnLogin(boolean rotate) {
        return new SecuritySettings(identityStore, confidentialPort, rotate);
    }
}
