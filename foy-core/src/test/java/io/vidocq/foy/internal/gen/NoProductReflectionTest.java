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
package io.vidocq.foy.internal.gen;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Reflection is allowed only in the registry's last tier. */
class NoProductReflectionTest {

    private static final Pattern REFLECTION =
            Pattern.compile("getAnnotation\\(|getDeclaredConstructor\\(|Class\\.forName\\(");
    private static final List<String> ALLOWED = List.of(
            "internal/gen/WebComponentRegistry.java",
            "internal/gen/IndexedHandlesTypesResolver.java",
            "internal/boot/ComponentFactory.java");

    @Test
    void noReflectionOutsideTheRegistry() throws IOException {
        Path root = Path.of("src/main/java/io/vidocq/foy");
        try (Stream<Path> files = Files.walk(root)) {
            var offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> ALLOWED.stream().noneMatch(a -> p.toString().replace('\\', '/').endsWith(a)))
                    .filter(p -> {
                        try { return REFLECTION.matcher(Files.readString(p)).find(); }
                        catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                    })
                    .map(Path::toString).toList();
            assertEquals(List.of(), offenders);
        }
    }
}
