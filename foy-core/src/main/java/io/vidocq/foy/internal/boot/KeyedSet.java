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
package io.vidocq.foy.internal.boot;

import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * An unmodifiable set in insertion order whose elements are the same when their keys are: for
 * {@link java.net.URL}s keyed by their external form, since {@link java.net.URL#equals} and
 * {@link java.net.URL#hashCode} may resolve host names, a lookup a set of jar and directory roots
 * never needs. Building the set and {@link #contains} only compare keys; {@link #hashCode()}, which
 * the {@link Set} contract defines from the elements' hash codes, is the only call to theirs.
 *
 * @param <E> the element type
 */
final class KeyedSet<E> extends AbstractSet<E> {

    private final Class<E> type;
    private final Function<? super E, String> key;
    private final Map<String, E> byKey;

    private KeyedSet(Class<E> type, Function<? super E, String> key, Map<String, E> byKey) {
        this.type = type;
        this.key = key;
        this.byKey = byKey;
    }

    /** {@code elements} in order, an element whose key comes earlier being left out. */
    static <E> Set<E> of(Class<E> type, Iterable<? extends E> elements, Function<? super E, String> key) {
        Map<String, E> byKey = new LinkedHashMap<>();
        for (E e : elements) byKey.putIfAbsent(key.apply(e), e);
        return new KeyedSet<>(type, key, byKey);
    }

    @Override
    public Iterator<E> iterator() {
        return Collections.unmodifiableCollection(byKey.values()).iterator();
    }

    @Override
    public int size() {
        return byKey.size();
    }

    @Override
    public boolean contains(Object o) {
        return type.isInstance(o) && byKey.containsKey(key.apply(type.cast(o)));
    }
}
