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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

/** Orders web fragments per Servlet 6.1 §8.2.2 (absolute and relative ordering). */
public final class FragmentOrderer {

    private static final System.Logger LOG = System.getLogger(FragmentOrderer.class.getName());

    private FragmentOrderer() {}

    /**
     * @param absoluteOrdering web.xml's list (may contain {@link WebAppDescriptor#OTHERS}), or null
     * @param fragments        in discovery order (deterministic: the caller sorts by jar URL)
     * @return the fragments to merge, in merge order; excluded fragments are absent
     * @throws ServletException on duplicate fragment names or a relative-ordering cycle
     */
    public static List<Fragment> order(List<String> absoluteOrdering, List<Fragment> fragments)
            throws ServletException {
        Map<String, Integer> byName = new HashMap<>();
        for (int i = 0; i < fragments.size(); i++) {
            String n = fragments.get(i).descriptor().fragmentName();
            if (n == null) continue;
            Integer prev = byName.put(n, i);
            if (prev != null) {
                throw new ServletException("duplicate web-fragment name '" + n + "' in "
                        + fragments.get(prev).jar() + " and " + fragments.get(i).jar());
            }
        }
        return absoluteOrdering != null
                ? absolute(absoluteOrdering, fragments, byName)
                : relative(fragments, byName);
    }

    private static List<Fragment> absolute(List<String> list, List<Fragment> fragments,
                                           Map<String, Integer> byName) {
        Set<String> seen = new HashSet<>();
        for (String n : list) if (!WebAppDescriptor.OTHERS.equals(n)) seen.add(n);
        List<Fragment> out = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        boolean othersDone = false;
        for (String n : list) {
            if (WebAppDescriptor.OTHERS.equals(n)) {
                if (othersDone) {
                    LOG.log(System.Logger.Level.WARNING, "<others/> repeated in absolute-ordering; ignoring repeat");
                    continue;
                }
                othersDone = true;
                for (Fragment f : fragments) {
                    String fn = f.descriptor().fragmentName();
                    if (fn == null || !seen.contains(fn)) out.add(f);
                }
                continue;
            }
            Integer idx = byName.get(n);
            if (idx == null) {
                LOG.log(System.Logger.Level.DEBUG, () -> "absolute-ordering names unknown fragment '" + n + "'");
            } else if (!emitted.add(n)) {
                LOG.log(System.Logger.Level.WARNING,
                        "fragment '" + n + "' repeated in absolute-ordering; ignoring repeat");
            } else {
                out.add(fragments.get(idx));
            }
        }
        return out;
    }

    private static List<Fragment> relative(List<Fragment> fragments, Map<String, Integer> byName)
            throws ServletException {
        int n = fragments.size();
        int[] group = new int[n]; // 0 head, 1 middle, 2 tail
        for (int i = 0; i < n; i++) {
            Ordering o = fragments.get(i).descriptor().ordering();
            if (o.beforeOthers() && o.afterOthers()) {
                throw new ServletException("web-fragment " + label(fragments.get(i))
                        + " declares ordering both before and after others");
            }
            group[i] = o.beforeOthers() ? 0 : o.afterOthers() ? 2 : 1;
        }
        List<Set<Integer>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new TreeSet<>());
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (group[i] < group[j] && !(group[i] != 1 && names(fragments.get(i), fragments.get(j)))
                        && !(group[j] != 1 && names(fragments.get(j), fragments.get(i)))) {
                    out.get(i).add(j);
                }
            }
            Ordering o = fragments.get(i).descriptor().ordering();
            for (String b : o.before()) {
                Integer t = byName.get(b);
                if (t == null) logUnknown(fragments.get(i), b);
                else if (t != i) out.get(i).add(t);
            }
            for (String a : o.after()) {
                Integer t = byName.get(a);
                if (t == null) logUnknown(fragments.get(i), a);
                else if (t != i) out.get(t).add(i);
            }
        }
        int[] indegree = new int[n];
        for (Set<Integer> s : out) for (int t : s) indegree[t]++;
        PriorityQueue<Integer> ready = new PriorityQueue<>();
        for (int i = 0; i < n; i++) if (indegree[i] == 0) ready.add(i);
        List<Fragment> result = new ArrayList<>(n);
        boolean[] done = new boolean[n];
        while (!ready.isEmpty()) {
            int i = ready.poll();
            done[i] = true;
            result.add(fragments.get(i));
            for (int t : out.get(i)) if (--indegree[t] == 0) ready.add(t);
        }
        if (result.size() == n) return result;

        // Keep only the nodes that sit on a cycle: prune leftovers with no leftover successor.
        boolean changed = true;
        boolean[] alive = new boolean[n];
        for (int i = 0; i < n; i++) alive[i] = !done[i];
        while (changed) {
            changed = false;
            for (int i = 0; i < n; i++) {
                if (!alive[i]) continue;
                boolean hasSucc = false;
                for (int t : out.get(i)) if (alive[t]) { hasSucc = true; break; }
                if (!hasSucc) { alive[i] = false; changed = true; }
            }
        }
        List<String> involved = new ArrayList<>();
        for (int i = 0; i < n; i++) if (alive[i]) involved.add(label(fragments.get(i)));
        throw new ServletException("circular web-fragment ordering among: " + String.join(", ", involved));
    }

    private static void logUnknown(Fragment f, String name) {
        LOG.log(System.Logger.Level.DEBUG,
                () -> "web-fragment " + label(f) + " orders against unknown fragment '" + name + "'");
    }

    /** True when {@code a}'s before/after lists name {@code b}; a pair is exempt from the others-groups only when the fragment declaring {@code <others/>} names the other. */
    private static boolean names(Fragment a, Fragment b) {
        String bn = b.descriptor().fragmentName();
        if (bn == null) return false;
        Ordering o = a.descriptor().ordering();
        return o.before().contains(bn) || o.after().contains(bn);
    }

    private static String label(Fragment f) {
        String n = f.descriptor().fragmentName();
        return n != null ? n : f.id();
    }
}
