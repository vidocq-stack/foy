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
package io.vidocq.foy.spi.gen;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WebComponentDescriptorTest {

    @Test
    void plainHasEmptyDefaults() {
        var d = WebComponentDescriptor.plain();
        assertEquals(WebComponentDescriptor.Kind.PLAIN, d.kind());
        assertNull(d.name());
        assertEquals(List.of(), d.urlPatterns());
        assertEquals(Map.of(), d.initParams());
        assertEquals(Integer.MIN_VALUE, d.loadOnStartup());
        assertFalse(d.asyncSupported());
        assertEquals(Set.of(), d.dispatcherTypes());
        assertNull(d.multipartConfig());
    }

    @Test
    void initParamsKeepDeclarationOrder() {
        var d = WebComponentDescriptor.plain().withInitParams("z", "1", "a", "2");
        assertEquals(List.of("z", "a"), List.copyOf(d.initParams().keySet()));
    }

    @Test
    void oddInitParamsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> WebComponentDescriptor.plain().withInitParams("k"));
    }

    @Test
    void withersReturnModifiedCopies() {
        var base = WebComponentDescriptor.plain();
        var d = base.withKind(WebComponentDescriptor.Kind.FILTER).withName("f")
                .withUrlPatterns("/a", "/b").withDispatcherTypes(DispatcherType.FORWARD);
        assertEquals(WebComponentDescriptor.Kind.PLAIN, base.kind());
        assertEquals(List.of("/a", "/b"), d.urlPatterns());
        assertEquals(Set.of(DispatcherType.FORWARD), d.dispatcherTypes());
    }

    @Test
    void duplicateDispatcherTypesAreTolerated() {
        var d = WebComponentDescriptor.plain()
                .withDispatcherTypes(DispatcherType.REQUEST, DispatcherType.REQUEST);
        assertEquals(Set.of(DispatcherType.REQUEST), d.dispatcherTypes());
    }
}
