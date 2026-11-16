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
package io.vidocq.foy.internal.session;

import java.util.function.Supplier;

/**
 * Wraps each {@linkplain SessionManager#importSessions import of sessions}, with the deserialization of their
 * values and their {@code sessionDidActivate} listeners. The deployment uses it to bind, for the import's duration,
 * the CDI container that rebinds the session's beans; without one, {@link SessionManager} runs the import as is.
 *
 * <p>{@link #around} runs on the importing thread, with the application's class loader as its context loader, and
 * returns what {@code importer} returns.</p>
 */
public interface SessionImportScope {

    /** Runs {@code importer} inside this scope and returns its result. */
    <T> T around(Supplier<T> importer);
}
