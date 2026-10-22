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

import io.vidocq.foy.internal.dispatcher.UrlPatternMatcher;
import io.vidocq.foy.internal.webxml.SecurityDefs.SecurityConstraintDef;
import io.vidocq.foy.internal.webxml.SecurityDefs.WebResourceCollectionDef;
import io.vidocq.foy.spi.security.Identity;
import jakarta.servlet.HttpConstraintElement;
import jakarta.servlet.HttpMethodConstraintElement;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.annotation.ServletSecurity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The security constraints of one web application, compiled once at deploy time (Servlet 6.1
 * sections 13.4 and 13.8).
 *
 * <p>A request is matched against the constrained url-patterns with the servlet mapping rules
 * (exact, longest prefix, extension, default; section 12.2). Only the best pattern applies. Within
 * it, the constraints that cover the request method combine:</p>
 * <ul>
 *   <li>an empty {@code auth-constraint} excludes (403 for everyone);</li>
 *   <li>otherwise a constraint without {@code auth-constraint} leaves the method unchecked;</li>
 *   <li>otherwise the role lists are unioned;</li>
 *   <li>the transport is confidential only when every covering constraint requires it.</li>
 * </ul>
 * <p>A method no constraint covers at a constrained pattern is "uncovered" (section 13.8.4): it is
 * excluded under {@code <deny-uncovered-http-methods/>}, otherwise unchecked and reported at
 * deploy time.</p>
 *
 * <p>The descriptor's constraints are added first. A servlet's {@code ServletSecurityElement}
 * (annotation or {@code setServletSecurity}) then applies to each of its patterns, except the ones
 * a descriptor constraint names exactly: there the descriptor wins for every method (section 13.4.1).</p>
 */
public final class ConstraintTable {

    /** What a request needs, for one url-pattern and one HTTP method. */
    public record Decision(Access access, Set<String> roles, boolean anyAuthenticated, boolean confidential) {

        public enum Access { UNCHECKED, EXCLUDED, ROLES }

        public static final Decision UNCHECKED = new Decision(Access.UNCHECKED, Set.of(), false, false);
        public static final Decision EXCLUDED = new Decision(Access.EXCLUDED, Set.of(), false, false);

        public Decision {
            roles = Set.copyOf(roles);
        }

        /** Whether {@code identity} ({@code null} when anonymous) may proceed. */
        public boolean permits(Identity identity) {
            if (access == Access.UNCHECKED) return true;
            if (access == Access.EXCLUDED || identity == null) return false;
            if (anyAuthenticated) return true;
            for (String r : roles) if (identity.hasRole(r)) return true;
            return false;
        }
    }

    /** One constraint at one url-pattern; before {@code build()} the roles are raw ({@code *}, {@code **}). */
    private record Entry(Set<String> methods, Set<String> omissions, Decision.Access access, Set<String> roles,
                         boolean anyAuthenticated, boolean confidential) {
        boolean covers(String method) {
            return methods.isEmpty() ? !omissions.contains(method) : methods.contains(method);
        }
    }

    public static final ConstraintTable EMPTY = builder().build();

    private final List<UrlPatternMatcher> patterns;
    private final Map<String, List<Entry>> byPattern;
    private final boolean denyUncovered;

    private ConstraintTable(List<UrlPatternMatcher> patterns, Map<String, List<Entry>> byPattern, boolean denyUncovered) {
        this.patterns = patterns;
        this.byPattern = byPattern;
        this.denyUncovered = denyUncovered;
    }

    public static Builder builder() { return new Builder(); }

    /** The decision for a canonical, context-relative path and a method name as sent ({@code HEAD} is its own method). */
    public Decision decide(String canonicalPath, String method) {
        String pattern = null;
        for (UrlPatternMatcher m : patterns) {
            if (m.matches(canonicalPath)) { pattern = m.pattern(); break; }
        }
        if (pattern == null) return Decision.UNCHECKED;
        boolean covered = false;
        boolean excluded = false;
        boolean unchecked = false;
        boolean any = false;
        boolean confidential = true;
        Set<String> roles = new LinkedHashSet<>();
        for (Entry e : byPattern.get(pattern)) {
            if (!e.covers(method)) continue;
            covered = true;
            confidential &= e.confidential();
            switch (e.access()) {
                case EXCLUDED -> excluded = true;
                case UNCHECKED -> unchecked = true;
                case ROLES -> { roles.addAll(e.roles()); any |= e.anyAuthenticated(); }
            }
        }
        if (!covered) return denyUncovered ? Decision.EXCLUDED : Decision.UNCHECKED;
        if (excluded) return Decision.EXCLUDED;
        if (unchecked) return new Decision(Decision.Access.UNCHECKED, Set.of(), false, confidential);
        return new Decision(Decision.Access.ROLES, roles, any, confidential);
    }

    /** One line per constrained url-pattern that leaves methods uncovered (section 13.8.4), for a deploy-time WARNING. */
    public List<String> uncoveredMethodWarnings() {
        List<String> out = new ArrayList<>();
        for (var e : byPattern.entrySet()) {
            Set<String> explicit = new TreeSet<>();
            Set<String> omittedByAll = null;
            for (Entry entry : e.getValue()) {
                if (!entry.methods().isEmpty()) {
                    explicit.addAll(entry.methods());
                } else if (omittedByAll == null) {
                    omittedByAll = new TreeSet<>(entry.omissions());
                } else {
                    omittedByAll.retainAll(entry.omissions());
                }
            }
            if (omittedByAll == null) {
                out.add("url-pattern '" + e.getKey() + "': every HTTP method other than " + explicit + " is uncovered");
            } else {
                omittedByAll.removeAll(explicit);
                if (!omittedByAll.isEmpty()) {
                    out.add("url-pattern '" + e.getKey() + "': HTTP methods " + omittedByAll + " are uncovered");
                }
            }
        }
        return out;
    }

    public static final class Builder {
        private final Map<String, List<Entry>> byPattern = new LinkedHashMap<>();
        private final Set<String> descriptorPatterns = new LinkedHashSet<>();
        private final Set<String> declaredRoles = new LinkedHashSet<>();
        private boolean denyUncovered;

        private Builder() {}

        /** The roles {@code "*"} stands for. */
        public Builder declaredRoles(Collection<String> roles) { declaredRoles.addAll(roles); return this; }

        public Builder denyUncoveredHttpMethods(boolean deny) { this.denyUncovered = deny; return this; }

        /** A descriptor {@code <security-constraint>}: every url-pattern of each collection. Add these first. */
        public Builder descriptor(SecurityConstraintDef def) {
            Decision.Access access = accessOf(def.rolesAllowed());
            Set<String> roles = def.rolesAllowed() == null ? Set.of() : Set.copyOf(def.rolesAllowed());
            boolean confidential = isConfidential(def.transportGuarantee());
            for (WebResourceCollectionDef c : def.collections()) {
                Set<String> methods = Set.copyOf(c.httpMethods());
                Set<String> omissions = methods.isEmpty() ? Set.copyOf(c.httpMethodOmissions()) : Set.of();
                for (String p : c.urlPatterns()) {
                    descriptorPatterns.add(p);
                    add(p, new Entry(methods, omissions, access, roles, false, confidential));
                }
            }
            return this;
        }

        /** A descriptor constraint's access: none ({@code null}) is unchecked, an empty role list excludes. */
        private static Decision.Access accessOf(Collection<String> rolesAllowed) {
            if (rolesAllowed == null) return Decision.Access.UNCHECKED;
            return rolesAllowed.isEmpty() ? Decision.Access.EXCLUDED : Decision.Access.ROLES;
        }

        /**
         * A servlet's {@code ServletSecurityElement} on each of its {@code patterns}. A pattern the
         * descriptor constrains exactly is skipped and returned. Each {@code HttpMethodConstraintElement}
         * covers its method. The {@code HttpConstraintElement} covers every other method, unless it has
         * all default values and method constraints exist: then the other methods stay uncovered.
         */
        public Set<String> servlet(Collection<String> patterns, ServletSecurityElement element) {
            Set<String> skipped = new LinkedHashSet<>();
            for (String p : patterns) {
                if (descriptorPatterns.contains(p)) { skipped.add(p); continue; }
                Set<String> named = new LinkedHashSet<>();
                for (HttpMethodConstraintElement m : element.getHttpMethodConstraints()) {
                    named.add(m.getMethodName());
                    add(p, entry(Set.of(m.getMethodName()), Set.of(), m));
                }
                if (!(isAllDefault(element) && !named.isEmpty())) {
                    add(p, entry(Set.of(), Set.copyOf(named), element));
                }
            }
            return skipped;
        }

        public ConstraintTable build() {
            boolean starStarIsARole = declaredRoles.contains("**");
            Map<String, List<Entry>> resolved = new LinkedHashMap<>();
            for (var e : byPattern.entrySet()) {
                List<Entry> entries = new ArrayList<>();
                for (Entry entry : e.getValue()) entries.add(resolveRoles(entry, starStarIsARole));
                resolved.put(e.getKey(), List.copyOf(entries));
            }
            List<UrlPatternMatcher> matchers = resolved.keySet().stream().map(UrlPatternMatcher::of)
                    .sorted(Comparator.comparingInt(UrlPatternMatcher::precedence)).toList();
            return new ConstraintTable(matchers, Collections.unmodifiableMap(resolved), denyUncovered);
        }

        /**
         * {@code entry} with its role names resolved: {@code "*"} stands for the declared roles and
         * {@code "**"}, unless declared as a role, for any authenticated user. Other entries are kept.
         */
        private Entry resolveRoles(Entry entry, boolean starStarIsARole) {
            if (entry.access() != Decision.Access.ROLES) return entry;
            Set<String> roles = new LinkedHashSet<>();
            boolean any = false;
            for (String r : entry.roles()) {
                if ("*".equals(r)) roles.addAll(declaredRoles);
                else if ("**".equals(r) && !starStarIsARole) any = true;
                else roles.add(r);
            }
            return new Entry(entry.methods(), entry.omissions(), entry.access(), Set.copyOf(roles), any,
                    entry.confidential());
        }

        private void add(String pattern, Entry entry) {
            byPattern.computeIfAbsent(pattern, k -> new ArrayList<>()).add(entry);
        }

        private static Entry entry(Set<String> methods, Set<String> omissions, HttpConstraintElement e) {
            String[] roles = e.getRolesAllowed();
            Decision.Access access = accessOf(roles, e.getEmptyRoleSemantic());
            return new Entry(methods, omissions, access, Set.copyOf(Arrays.asList(roles)), false,
                    e.getTransportGuarantee() == ServletSecurity.TransportGuarantee.CONFIDENTIAL);
        }

        /** An annotation constraint's access: its roles, else its empty-role semantic. */
        private static Decision.Access accessOf(String[] roles, ServletSecurity.EmptyRoleSemantic emptyRoleSemantic) {
            if (roles.length > 0) return Decision.Access.ROLES;
            return emptyRoleSemantic == ServletSecurity.EmptyRoleSemantic.DENY
                    ? Decision.Access.EXCLUDED : Decision.Access.UNCHECKED;
        }

        private static boolean isAllDefault(HttpConstraintElement e) {
            return e.getEmptyRoleSemantic() == ServletSecurity.EmptyRoleSemantic.PERMIT
                    && e.getRolesAllowed().length == 0
                    && e.getTransportGuarantee() == ServletSecurity.TransportGuarantee.NONE;
        }

        private static boolean isConfidential(String guarantee) {
            if (guarantee == null) return false;
            String g = guarantee.trim();
            return g.equalsIgnoreCase("CONFIDENTIAL") || g.equalsIgnoreCase("INTEGRAL");
        }
    }
}
