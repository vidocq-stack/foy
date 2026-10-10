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
package io.vidocq.foy.internal.cdi;

import io.vidocq.foy.internal.session.SessionManager;
import io.vidocq.foy.nocdi.NoCdiSmoke;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * foy-core declares Foy's portable extension (foy#21) while CDI stays optional
 * ({@code requires static jakarta.cdi}): a module path without any CDI module still resolves
 * foy-core and runs its session code.
 */
class OptionalCdiModuleTest {

    private static final String EXTENSION = "jakarta.enterprise.inject.spi.Extension";

    @Test
    void moduleDescriptorProvidesThePortableExtension() {
        ModuleDescriptor descriptor = SessionManager.class.getModule().getDescriptor();
        assertNotNull(descriptor, "foy-core tests run on the module path");
        var providers = descriptor.provides().stream()
                .filter(p -> p.service().equals(EXTENSION))
                .flatMap(p -> p.providers().stream())
                .toList();
        assertEquals(List.of(FoySessionScopeExtension.class.getName()), providers);
    }

    @Test
    void classPathServiceFileNamesThePortableExtension() throws Exception {
        try (var in = SessionManager.class.getModule().getResourceAsStream("META-INF/services/" + EXTENSION)) {
            assertNotNull(in, "META-INF/services/" + EXTENSION);
            var names = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(l -> l.replaceFirst("#.*", "").strip())
                    .filter(l -> !l.isEmpty())
                    .toList();
            assertEquals(List.of(FoySessionScopeExtension.class.getName()), names);
        }
    }

    @Test
    void foyCoreResolvesAndRunsWithoutCdiOnTheModulePath() throws Exception {
        List<String> modulePath = new ArrayList<>();
        for (String module : List.of("io.vidocq.foy.core", "io.vidocq.foy.api", "jakarta.servlet",
                "io.vidocq.chappe.api")) {
            modulePath.add(location(module));
        }
        assertTrue(modulePath.stream().noneMatch(p -> p.contains("cdi-api")), modulePath::toString);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // NoCdiSmoke's package exists only in the test classes, so the child JVM loads it from the
        // class path (unnamed module) while foy-core comes from the module path.
        String testClasses = Path.of("target", "test-classes").toAbsolutePath().toString();
        Process process = new ProcessBuilder(java,
                "--module-path", String.join(File.pathSeparator, modulePath),
                "--add-modules", "io.vidocq.foy.core",
                "-cp", testClasses,
                NoCdiSmoke.class.getName())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the child JVM did not end");
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains(NoCdiSmoke.OK), output);
    }

    /** Where this test run's boot layer found {@code module}. */
    private static String location(String module) {
        return ModuleLayer.boot().configuration().findModule(module)
                .flatMap(m -> m.reference().location())
                .map(uri -> Path.of(uri).toString())
                .orElseThrow(() -> new AssertionError(module + " is not on this test run's module path"));
    }
}
