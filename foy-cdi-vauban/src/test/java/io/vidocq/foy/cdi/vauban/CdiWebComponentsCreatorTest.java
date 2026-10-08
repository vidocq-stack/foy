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
package io.vidocq.foy.cdi.vauban;

import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CdiWebComponentsCreator: the synthetic index from its comma-joined class names")
class CdiWebComponentsCreatorTest {

    private static Parameters params(Map<String, Object> values) {
        return new Parameters() {
            @Override
            public <T> T get(String key, Class<T> type) {
                return type.cast(values.get(key));
            }

            @Override
            public <T> T get(String key, Class<T> type, T defaultValue) {
                return values.containsKey(key) ? type.cast(values.get(key)) : defaultValue;
            }
        };
    }

    @Test
    @DisplayName("classes are loaded in the order of the parameter, into an immutable list")
    void keepsTheOrder() {
        var components = new CdiWebComponentsCreator().create(null,
                params(Map.of("classes", "java.lang.String, java.lang.Integer,java.util.List")));
        assertEquals(List.of(String.class, Integer.class, List.class), components.componentClasses());
        assertThrows(UnsupportedOperationException.class, () -> components.componentClasses().clear());
    }

    @Test
    @DisplayName("no parameter gives an empty list")
    void missingParameterIsEmpty() {
        assertEquals(List.of(), new CdiWebComponentsCreator().create(null, params(Map.of())).componentClasses());
    }

    @Test
    @DisplayName("a class visible from neither loader fails with its name")
    void missingClassIsNamed() {
        var ex = assertThrows(IllegalStateException.class, () -> new CdiWebComponentsCreator().create(null,
                params(Map.of("classes", "java.lang.String,no.such.Missing"))));
        assertTrue(ex.getMessage().contains("no.such.Missing"), ex.getMessage());
        assertTrue(ex.getMessage().startsWith("foy: "), ex.getMessage());
        assertTrue(ex.getCause() instanceof ClassNotFoundException, String.valueOf(ex.getCause()));
    }
}
