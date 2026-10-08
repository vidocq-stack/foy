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
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FragmentOrdererTest {

    private static Fragment f(String name, Ordering o) throws Exception {
        var d = WebAppDescriptor.fragmentForTest(name, o);
        return new Fragment(name == null ? "anon" : name,
                URI.create("file:/jars/" + (name == null ? "anon" : name) + ".jar").toURL(), d);
    }

    private static List<String> names(List<Fragment> fs) {
        var out = new ArrayList<String>();
        for (var x : fs) out.add(x.descriptor().fragmentName() == null ? "anon" : x.descriptor().fragmentName());
        return out;
    }

    private static Ordering after(String... n) { return new Ordering(List.of(), false, List.of(n), false); }
    private static Ordering before(String... n) { return new Ordering(List.of(n), false, List.of(), false); }
    private static final Ordering BEFORE_OTHERS = new Ordering(List.of(), true, List.of(), false);
    private static final Ordering AFTER_OTHERS = new Ordering(List.of(), false, List.of(), true);

    @Test
    void noOrderingKeepsDiscoveryOrder() throws Exception {
        var in = List.of(f("A", Ordering.NONE), f("B", Ordering.NONE), f("C", Ordering.NONE));
        assertEquals(List.of("A", "B", "C"), names(FragmentOrderer.order(null, in)));
    }

    @Test
    void absoluteOrderingWithOthers() throws Exception {
        var in = List.of(f("A", Ordering.NONE), f("B", Ordering.NONE), f("C", Ordering.NONE), f(null, Ordering.NONE));
        var out = FragmentOrderer.order(List.of("C", WebAppDescriptor.OTHERS, "A"), in);
        assertEquals(List.of("C", "B", "anon", "A"), names(out));
    }

    @Test
    void unnamedFragmentExcludedWithoutOthers() throws Exception {
        var in = List.of(f("A", Ordering.NONE), f(null, Ordering.NONE), f("B", Ordering.NONE));
        assertEquals(List.of("B", "A"), names(FragmentOrderer.order(List.of("B", "Missing", "A"), in)));
    }

    @Test
    void absoluteOrderingIgnoresRelativeOrdering() throws Exception {
        var in = List.of(f("A", after("B")), f("B", Ordering.NONE));
        assertEquals(List.of("A", "B"), names(FragmentOrderer.order(List.of("A", "B"), in)));
    }

    @Test
    void relativeBeforeAfterAndOthers() throws Exception {
        // TCK FragmentTests shape: Fragment1 after Fragment2; Fragment3 before others
        var in = List.of(f("Fragment1", after("Fragment2")), f("Fragment2", Ordering.NONE),
                         f("Fragment3", BEFORE_OTHERS), f("Fragment4", AFTER_OTHERS));
        assertEquals(List.of("Fragment3", "Fragment2", "Fragment1", "Fragment4"),
                names(FragmentOrderer.order(null, in)));
    }

    @Test
    void beforeNamedFragment() throws Exception {
        var in = List.of(f("A", Ordering.NONE), f("B", before("A")));
        assertEquals(List.of("B", "A"), names(FragmentOrderer.order(null, in)));
    }

    @Test
    void cycleFails() throws Exception {
        var in = List.of(f("A", after("B")), f("B", after("A")));
        var e = assertThrows(ServletException.class, () -> FragmentOrderer.order(null, in));
        assertTrue(e.getMessage().contains("A") && e.getMessage().contains("B"), e.getMessage());
    }

    @Test
    void duplicateNameFails() throws Exception {
        var in = List.of(f("A", Ordering.NONE), f("A", Ordering.NONE));
        assertThrows(ServletException.class, () -> FragmentOrderer.order(null, in));
    }

    @Test
    void beforeAndAfterOthersTogetherFails() throws Exception {
        var both = new Ordering(List.of(), true, List.of(), true);
        assertThrows(ServletException.class, () -> FragmentOrderer.order(null, List.of(f("A", both))));
    }

    @Test
    void nameConstraintContradictingGroupsIsACycle() throws Exception {
        var in = List.of(f("H", new Ordering(List.of(), true, List.of("M"), false)), f("M", Ordering.NONE));
        assertThrows(ServletException.class, () -> FragmentOrderer.order(null, in));
    }
}
