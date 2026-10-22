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
    /** Others-groups: before others, neither, after others. */
    private static final int HEAD = 0;
    private static final int MIDDLE = 1;
    private static final int TAIL = 2;

    private FragmentOrderer() {}

    /**
     * Orders the web fragments by the absolute ordering of web.xml, or else by their relative orderings.
     *
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
            if (!WebAppDescriptor.OTHERS.equals(n)) {
                addNamed(n, fragments, byName, emitted, out);
            } else if (othersDone) {
                LOG.log(System.Logger.Level.WARNING, "<others/> repeated in absolute-ordering; ignoring repeat");
            } else {
                othersDone = true;
                addOthers(fragments, seen, out);
            }
        }
        return out;
    }

    /** Adds the fragments the absolute ordering does not name ({@code seen}), unnamed ones included, to {@code out}. */
    private static void addOthers(List<Fragment> fragments, Set<String> seen, List<Fragment> out) {
        for (Fragment f : fragments) {
            String fn = f.descriptor().fragmentName();
            if (fn == null || !seen.contains(fn)) out.add(f);
        }
    }

    /** Adds the fragment the absolute ordering names {@code n} to {@code out}, unless unknown or already {@code emitted}. */
    private static void addNamed(String n, List<Fragment> fragments, Map<String, Integer> byName,
                                 Set<String> emitted, List<Fragment> out) {
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

    private static List<Fragment> relative(List<Fragment> fragments, Map<String, Integer> byName)
            throws ServletException {
        int n = fragments.size();
        List<Set<Integer>> out = successors(fragments, byName, groups(fragments));
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
        throw cycle(fragments, out, done);
    }

    /** The others-group of each fragment: {@link #HEAD}, {@link #MIDDLE} or {@link #TAIL}. */
    private static int[] groups(List<Fragment> fragments) throws ServletException {
        int[] group = new int[fragments.size()];
        for (int i = 0; i < group.length; i++) {
            Ordering o = fragments.get(i).descriptor().ordering();
            if (o.beforeOthers() && o.afterOthers()) {
                throw new ServletException("web-fragment " + label(fragments.get(i))
                        + " declares ordering both before and after others");
            }
            group[i] = group(o);
        }
        return group;
    }

    private static int group(Ordering o) {
        if (o.beforeOthers()) return HEAD;
        return o.afterOthers() ? TAIL : MIDDLE;
    }

    /**
     * The successors of each fragment (indexes, in ascending order): the fragments of a later
     * others-group, then the fragments its before/after lists name.
     */
    private static List<Set<Integer>> successors(List<Fragment> fragments, Map<String, Integer> byName,
                                                 int[] group) {
        int n = fragments.size();
        List<Set<Integer>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new TreeSet<>());
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (groupPrecedes(fragments, group, i, j)) out.get(i).add(j);
            }
            addNamedSuccessors(fragments, byName, i, out);
        }
        return out;
    }

    /**
     * Whether fragment {@code i}'s others-group comes before fragment {@code j}'s, the pair not
     * being exempt: a fragment declaring {@code <others/>} that names the other one.
     */
    private static boolean groupPrecedes(List<Fragment> fragments, int[] group, int i, int j) {
        return group[i] < group[j] && !(group[i] != MIDDLE && names(fragments.get(i), fragments.get(j)))
                && !(group[j] != MIDDLE && names(fragments.get(j), fragments.get(i)));
    }

    /** The edges of fragment {@code i}'s before/after lists into {@code out}. */
    private static void addNamedSuccessors(List<Fragment> fragments, Map<String, Integer> byName, int i,
                                           List<Set<Integer>> out) {
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

    /**
     * The failure naming the fragments on a cycle: the fragments the sort left ({@code !done}),
     * pruned of those without a leftover successor.
     */
    private static ServletException cycle(List<Fragment> fragments, List<Set<Integer>> out, boolean[] done) {
        int n = fragments.size();
        boolean changed = true;
        boolean[] alive = new boolean[n];
        for (int i = 0; i < n; i++) alive[i] = !done[i];
        while (changed) {
            changed = false;
            for (int i = 0; i < n; i++) {
                if (alive[i] && !hasAliveSuccessor(out.get(i), alive)) {
                    alive[i] = false;
                    changed = true;
                }
            }
        }
        List<String> involved = new ArrayList<>();
        for (int i = 0; i < n; i++) if (alive[i]) involved.add(label(fragments.get(i)));
        return new ServletException("circular web-fragment ordering among: " + String.join(", ", involved));
    }

    private static boolean hasAliveSuccessor(Set<Integer> successors, boolean[] alive) {
        for (int t : successors) {
            if (alive[t]) return true;
        }
        return false;
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
