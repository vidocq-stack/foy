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
package io.vidocq.foy.nocdi;

import io.vidocq.foy.internal.session.HttpSessionImpl;
import io.vidocq.foy.internal.session.InMemorySessionStore;
import io.vidocq.foy.internal.session.SessionManager;

/**
 * Run in a child JVM by {@code OptionalCdiModuleTest}: foy-core on a module path that holds no CDI
 * module. Creates and invalidates a session, the code path every later task of foy#21 touches.
 */
public final class NoCdiSmoke {

    public static final String OK = "foy-core ok without jakarta.cdi";

    private NoCdiSmoke() {}

    public static void main(String[] args) {
        if (ModuleLayer.boot().findModule("jakarta.cdi").isPresent()) {
            throw new AssertionError("jakarta.cdi must not be resolved in this JVM");
        }
        Module core = ModuleLayer.boot().findModule("io.vidocq.foy.core")
                .orElseThrow(() -> new AssertionError("io.vidocq.foy.core is not resolved"));
        var manager = new SessionManager(new InMemorySessionStore(), null, 60);
        HttpSessionImpl session = manager.createNew();
        session.setAttribute("a", "1");
        session.invalidate();
        manager.close();
        System.out.println(OK + " " + core.getDescriptor().provides());
    }
}
