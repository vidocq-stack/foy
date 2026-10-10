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

import io.vidocq.foy.internal.security.ConstraintTable.Decision;
import io.vidocq.foy.internal.security.ConstraintTable.Decision.Access;
import io.vidocq.foy.internal.webxml.SecurityDefs.SecurityConstraintDef;
import io.vidocq.foy.spi.security.Identity;
import jakarta.servlet.HttpConstraintElement;
import jakarta.servlet.HttpMethodConstraintElement;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.annotation.ServletSecurity.EmptyRoleSemantic;
import jakarta.servlet.annotation.ServletSecurity.TransportGuarantee;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConstraintTableTest {

    private static SecurityConstraintDef dd(List<String> patterns, List<String> methods, List<String> omissions,
                                            List<String> roles, String guarantee) {
        return SecureApp.constraint(patterns, methods, omissions, roles, guarantee);
    }

    private static SecurityConstraintDef dd(String pattern, List<String> roles) {
        return dd(List.of(pattern), List.of(), List.of(), roles, null);
    }

    @Test
    void anUnconstrainedPathIsUnchecked() {
        var t = ConstraintTable.builder().descriptor(dd("/a/*", List.of("A"))).build();
        assertEquals(Access.UNCHECKED, t.decide("/b", "GET").access());
        assertEquals(Access.UNCHECKED, ConstraintTable.EMPTY.decide("/a", "GET").access());
    }

    @Test
    void onlyTheBestMatchingPatternApplies() {
        var t = ConstraintTable.builder()
                .descriptor(dd("/a/*", List.of("A")))
                .descriptor(dd("/a/b", List.of("B")))
                .descriptor(dd("/a/b/c/*", null))
                .descriptor(dd("*.jsp", List.of("C")))
                .descriptor(dd("/", List.of()))
                .build();
        assertEquals(Set.of("B"), t.decide("/a/b", "GET").roles());
        assertEquals(Set.of("A"), t.decide("/a", "GET").roles());
        assertEquals(Set.of("A"), t.decide("/a/x.jsp", "GET").roles(), "prefix beats extension");
        assertEquals(Set.of("C"), t.decide("/x.jsp", "GET").roles());
        assertEquals(Access.UNCHECKED, t.decide("/a/b/c/d", "GET").access(), "longest prefix only");
        assertEquals(Access.EXCLUDED, t.decide("/elsewhere", "GET").access(), "default pattern");
    }

    @Test
    void theEmptyPatternIsTheContextRoot() {
        var t = ConstraintTable.builder().descriptor(dd("", List.of())).build();
        assertEquals(Access.EXCLUDED, t.decide("/", "GET").access());
        assertEquals(Access.UNCHECKED, t.decide("/x", "GET").access());
    }

    @Test
    void combinationAtOnePatternAndMethod() {
        var union = ConstraintTable.builder().descriptor(dd("/x", List.of("A"))).descriptor(dd("/x", List.of("B"))).build();
        assertEquals(Set.of("A", "B"), union.decide("/x", "GET").roles());
        var excluded = ConstraintTable.builder().descriptor(dd("/x", List.of("A"))).descriptor(dd("/x", List.of())).build();
        assertEquals(Access.EXCLUDED, excluded.decide("/x", "GET").access());
        var unchecked = ConstraintTable.builder().descriptor(dd("/x", List.of("A"))).descriptor(dd("/x", null)).build();
        assertEquals(Access.UNCHECKED, unchecked.decide("/x", "GET").access());
    }

    @Test
    void methodsAndOmissions() {
        var t = ConstraintTable.builder()
                .descriptor(dd(List.of("/m"), List.of("GET"), List.of(), List.of("A"), null))
                .descriptor(dd(List.of("/o"), List.of(), List.of("GET"), List.of("A"), null))
                .build();
        assertEquals(Access.ROLES, t.decide("/m", "GET").access());
        assertEquals(Access.UNCHECKED, t.decide("/m", "POST").access(), "uncovered, not denied");
        assertEquals(Access.UNCHECKED, t.decide("/m", "HEAD").access(), "HEAD is its own method");
        assertEquals(Access.ROLES, t.decide("/o", "POST").access());
        assertEquals(Access.UNCHECKED, t.decide("/o", "GET").access());
        var denied = ConstraintTable.builder().denyUncoveredHttpMethods(true)
                .descriptor(dd(List.of("/m"), List.of("GET"), List.of(), List.of("A"), null)).build();
        assertEquals(Access.EXCLUDED, denied.decide("/m", "POST").access());
        assertEquals(Access.EXCLUDED, denied.decide("/m", "HEAD").access());
        assertEquals(Access.UNCHECKED, denied.decide("/unconstrained", "PUT").access());
    }

    @Test
    void transportIsConfidentialOnlyWhenEveryConstraintRequiresIt() {
        var both = ConstraintTable.builder()
                .descriptor(dd(List.of("/c"), List.of(), List.of(), List.of("A"), "CONFIDENTIAL"))
                .descriptor(dd(List.of("/c"), List.of(), List.of(), List.of("B"), "INTEGRAL")).build();
        assertTrue(both.decide("/c", "GET").confidential());
        var mixed = ConstraintTable.builder()
                .descriptor(dd(List.of("/c"), List.of(), List.of(), List.of("A"), "CONFIDENTIAL"))
                .descriptor(dd(List.of("/c"), List.of(), List.of(), List.of("B"), "NONE")).build();
        assertFalse(mixed.decide("/c", "GET").confidential());
    }

    @Test
    void starAndDoubleStar() {
        var t = ConstraintTable.builder().declaredRoles(List.of("A", "B"))
                .descriptor(dd("/star", List.of("*")))
                .descriptor(dd("/any", List.of("**"))).build();
        assertEquals(Set.of("A", "B"), t.decide("/star", "GET").roles());
        Decision any = t.decide("/any", "GET");
        assertTrue(any.anyAuthenticated());
        assertTrue(any.permits(Identity.of("x", Set.of())));
        assertFalse(any.permits(null));
        var declared = ConstraintTable.builder().declaredRoles(List.of("**")).descriptor(dd("/any", List.of("**"))).build();
        assertFalse(declared.decide("/any", "GET").anyAuthenticated());
        assertEquals(Set.of("**"), declared.decide("/any", "GET").roles());
    }

    @Test
    void theDescriptorBeatsTheAnnotationOnAnExactPattern() {
        var b = ConstraintTable.builder().descriptor(dd("/a", null));
        var element = new ServletSecurityElement(new HttpConstraintElement(TransportGuarantee.NONE, "admin"));
        assertEquals(Set.of("/a"), b.servlet(List.of("/a", "/b"), element));
        var t = b.build();
        assertEquals(Access.UNCHECKED, t.decide("/a", "GET").access());
        assertEquals(Set.of("admin"), t.decide("/b", "DELETE").roles(), "an HttpConstraint covers every method");
    }

    @Test
    void allDefaultHttpConstraintLeavesTheOtherMethodsUncovered() {
        var element = new ServletSecurityElement(new HttpConstraintElement(),
                List.of(new HttpMethodConstraintElement("GET", new HttpConstraintElement(TransportGuarantee.NONE, "A")),
                        new HttpMethodConstraintElement("POST", new HttpConstraintElement(TransportGuarantee.NONE, "A"))));
        var b = ConstraintTable.builder().denyUncoveredHttpMethods(true);
        b.servlet(List.of("/p"), element);
        var t = b.build();
        assertEquals(Access.ROLES, t.decide("/p", "GET").access());
        assertEquals(Access.EXCLUDED, t.decide("/p", "PUT").access());
    }

    @Test
    void annotationDenyAndTransport() {
        var element = new ServletSecurityElement(new HttpConstraintElement(EmptyRoleSemantic.DENY),
                List.of(new HttpMethodConstraintElement("GET",
                        new HttpConstraintElement(TransportGuarantee.CONFIDENTIAL, "A"))));
        var b = ConstraintTable.builder();
        b.servlet(List.of("/d"), element);
        var t = b.build();
        assertEquals(Access.EXCLUDED, t.decide("/d", "POST").access());
        assertTrue(t.decide("/d", "GET").confidential());
    }

    @Test
    void uncoveredMethodsAreReported() {
        var t = ConstraintTable.builder()
                .descriptor(dd(List.of("/m"), List.of("GET", "POST"), List.of(), List.of("A"), null))
                .descriptor(dd(List.of("/o"), List.of(), List.of("GET"), List.of("A"), null))
                .descriptor(dd("/all", List.of("A"))).build();
        var w = t.uncoveredMethodWarnings();
        assertEquals(2, w.size(), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.contains("'/m'") && s.contains("every HTTP method other than [GET, POST]")));
        assertTrue(w.stream().anyMatch(s -> s.contains("'/o'") && s.contains("[GET]")));
    }
}
