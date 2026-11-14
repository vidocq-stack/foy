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
package io.vidocq.foy.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.websocket.DeploymentException;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class PathTemplateTest {

    @Test
    void vaadinPushMatchesTwoVariables() throws DeploymentException {
        PathTemplate template = PathTemplate.parse("/{path0}/{path1}");
        assertEquals(Optional.of(Map.of("path0", "VAADIN", "path1", "push")), template.match("/VAADIN/push"));
    }

    @Test
    void fiveLevelsMatch() throws DeploymentException {
        PathTemplate template = PathTemplate.parse("/{path0}/{path1}/{path2}/{path3}/{path4}");
        assertEquals(Optional.of(Map.of("path0", "a", "path1", "b", "path2", "c", "path3", "d", "path4", "e")),
                template.match("/a/b/c/d/e"));
    }

    @Test
    void segmentCountsMustBeEqual() throws DeploymentException {
        assertTrue(PathTemplate.parse("/{x}").match("/a/b").isEmpty());
    }

    @Test
    void anEmptySegmentMatchesNoVariable() throws DeploymentException {
        assertTrue(PathTemplate.parse("/{x}").match("/").isEmpty());
        assertTrue(PathTemplate.parse("/a/{x}").match("/a/").isEmpty());
    }

    @Test
    void theRootPathMatchesOnlyTheRoot() throws DeploymentException {
        PathTemplate root = PathTemplate.parse("/");
        assertEquals(Optional.of(Map.of()), root.match("/"));
        assertTrue(root.match("/a").isEmpty());
    }

    @Test
    void aLiteralMustBeEqual() throws DeploymentException {
        assertTrue(PathTemplate.parse("/a/{x}").match("/b/c").isEmpty());
    }

    @Test
    void aPathWithoutLeadingSlashIsRefused() {
        assertThrows(DeploymentException.class, () -> PathTemplate.parse("a/b"));
    }

    @Test
    void aVariableThatIsNotAWholeSegmentIsRefused() {
        assertThrows(DeploymentException.class, () -> PathTemplate.parse("/a{x}"));
        assertThrows(DeploymentException.class, () -> PathTemplate.parse("/{x}b"));
        assertThrows(DeploymentException.class, () -> PathTemplate.parse("/{}"));
    }

    @Test
    void aRepeatedVariableNameIsRefused() {
        assertThrows(DeploymentException.class, () -> PathTemplate.parse("/{x}/{x}"));
    }

    @Test
    void anEmptySegmentInATemplateIsRefused() {
        assertThrows(DeploymentException.class, () -> PathTemplate.parse("/a//b"));
        assertThrows(DeploymentException.class, () -> PathTemplate.parse("//"));
    }

    /** Atmosphere registers {@code /VAADIN/push/} as well as {@code /VAADIN/push}; Tomcat accepts both. */
    @Test
    void aTemplateEndingInASlashMatchesOnlyAPathEndingInASlash() throws DeploymentException {
        PathTemplate template = PathTemplate.parse("/a/b/");
        assertEquals(Optional.of(Map.of()), template.match("/a/b/"));
        assertTrue(template.match("/a/b").isEmpty());
        assertTrue(template.match("/a/b/c").isEmpty());
    }

    @Test
    void aTemplateWithoutTheSlashDoesNotMatchAPathEndingInOne() throws DeploymentException {
        assertTrue(PathTemplate.parse("/a/b").match("/a/b/").isEmpty());
    }

    @Test
    void aTemplateWithAVariableDoesNotEndInASlash() {
        assertThrows(DeploymentException.class, () -> PathTemplate.parse("/a/{x}/"));
        assertThrows(DeploymentException.class, () -> PathTemplate.parse("/{x}/b/"));
    }

    @Test
    void theTrailingSlashIsALiteralSegmentForPrecedence() throws DeploymentException {
        PathTemplate literal = PathTemplate.parse("/a/b/");
        PathTemplate variable = PathTemplate.parse("/a/b/{x}");
        assertTrue(PathTemplate.PRECEDENCE.compare(literal, variable) < 0);
        assertTrue(PathTemplate.PRECEDENCE.compare(PathTemplate.parse("/a/b"), literal) < 0);
        assertTrue(variable.match("/a/b/").isEmpty());
    }
}
