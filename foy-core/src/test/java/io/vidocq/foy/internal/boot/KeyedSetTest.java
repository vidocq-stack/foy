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

import org.junit.jupiter.api.Test;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link KeyedSet} over URLs, each held as a {@link Collection}: a variable typed as a set of URLs is what the URL hashing checks report. */
class KeyedSetTest {

    private static URL url(String s) throws MalformedURLException {
        return URI.create(s).toURL();
    }

    @Test
    void keepsTheFirstElementOfEachKeyInInsertionOrder() throws MalformedURLException {
        URL b = url("file:/b.jar");
        URL a = url("file:/a.jar");
        Collection<URL> set = KeyedSet.of(URL.class, List.of(b, a, url("file:/b.jar")), URL::toExternalForm);
        assertEquals(List.of(b, a), List.copyOf(set));
        assertEquals(2, set.size());
    }

    @Test
    void containsComparesKeysAndRejectsOtherTypes() throws MalformedURLException {
        Collection<URL> set = KeyedSet.of(URL.class, List.of(url("file:/a.jar")), URL::toExternalForm);
        assertTrue(set.contains(url("file:/a.jar")));
        assertFalse(set.contains(url("file:/b.jar")));
        Object sameTextNotAUrl = URI.create("file:/a.jar");
        assertFalse(set.contains(sameTextNotAUrl));
        assertFalse(set.contains(null));
    }

    @Test
    void equalsAnyOtherSetOfTheSameElements() throws MalformedURLException {
        Collection<URL> set = KeyedSet.of(URL.class, List.of(url("file:/a.jar"), url("file:/b.jar")), URL::toExternalForm);
        assertEquals(Set.of(url("file:/b.jar"), url("file:/a.jar")), set);
        assertEquals(set, Set.of(url("file:/b.jar"), url("file:/a.jar")));
    }

    @Test
    void isUnmodifiable() throws MalformedURLException {
        URL a = url("file:/a.jar");
        Collection<URL> set = KeyedSet.of(URL.class, List.of(a), URL::toExternalForm);
        assertThrows(UnsupportedOperationException.class, () -> set.add(a));
        assertThrows(UnsupportedOperationException.class, () -> set.remove(a));
        assertThrows(UnsupportedOperationException.class, set::clear);
    }
}
