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
package io.vidocq.foy.chappe;

import io.vidocq.foy.spi.cdi.CdiContextListeners;
import jakarta.enterprise.inject.spi.BeanManager;

import java.util.EventListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * A {@link CdiContextListeners} provider found by {@code ServiceLoader} on the test class path. It adds no
 * listener and records each session import it wraps, with the bean manager it was given.
 */
public final class RecordingCdiAdapter implements CdiContextListeners {

    /** The bean managers of the session imports this provider wrapped, in order. */
    static final List<BeanManager> IMPORTS = new CopyOnWriteArrayList<>();

    @Override
    public List<EventListener> listeners(BeanManager beanManager) {
        return List.of();
    }

    @Override
    public <T> T aroundSessionImport(BeanManager beanManager, Supplier<T> importer) {
        IMPORTS.add(beanManager);
        return importer.get();
    }
}
