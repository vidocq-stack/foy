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
package io.vidocq.foy.internal.webxml;

import jakarta.servlet.ServletException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Accumulators shared by the fragment merge (Servlet 6.1 §8.2.3): a value declared by web.xml is
 * final; the first fragment declaring a value sets it; a different value from another fragment is a
 * conflict, unless web.xml declared it.
 */
final class MergeSlots {

    private MergeSlots() {}

    static ServletException conflict(String what, String firstFragment, String secondFragment) {
        return new ServletException("conflicting " + what + " in web fragments " + firstFragment + " and "
                + secondFragment);
    }

    /** One value per key; insertion order is kept (web.xml first, then fragment order). */
    static final class Keyed<V> {
        private final String element;
        private final String owner;
        private final Map<String, V> values = new LinkedHashMap<>();
        /** Fragment id that set the key; absent for web.xml keys. */
        private final Map<String, String> fromFragment = new HashMap<>();

        /**
         * @param element the element as written in messages, e.g. {@code <init-param>}
         * @param owner   message suffix naming the owning component, e.g. {@code for servlet 's'}
         */
        Keyed(String element, String owner) {
            this.element = element;
            this.owner = owner;
        }

        void webXml(String key, V value) {
            values.put(key, value);
        }

        void webXml(Map<String, V> all) {
            all.forEach(this::webXml);
        }

        void fragment(String fragmentId, String key, V value) throws ServletException {
            if (!values.containsKey(key)) {
                values.put(key, value);
                fromFragment.put(key, fragmentId);
                return;
            }
            String first = fromFragment.get(key);
            if (first == null) return; // web.xml wins
            if (!Objects.equals(values.get(key), value)) {
                throw conflict(element + " '" + key + "'" + owner, first, fragmentId);
            }
        }

        void fragment(String fragmentId, Map<String, V> all) throws ServletException {
            for (var e : all.entrySet()) fragment(fragmentId, e.getKey(), e.getValue());
        }

        Map<String, V> values() {
            return values;
        }
    }

    /** A single value; {@code null} means "not declared". */
    static final class Single<V> {
        private final String element;
        private V value;
        private boolean fromWebXml;
        private String firstFragment;

        Single(String element) {
            this.element = element;
        }

        Single<V> webXml(V v) {
            if (v != null) {
                value = v;
                fromWebXml = true;
            }
            return this;
        }

        void fragment(String fragmentId, V v) throws ServletException {
            if (v == null || fromWebXml) return;
            if (value == null) {
                value = v;
                firstFragment = fragmentId;
            } else if (!value.equals(v)) {
                throw conflict(element, firstFragment, fragmentId);
            }
        }

        V value() {
            return value;
        }
    }
}
