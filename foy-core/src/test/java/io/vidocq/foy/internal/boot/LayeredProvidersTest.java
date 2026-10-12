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
package io.vidocq.foy.internal.boot;

import jakarta.servlet.ServletContainerInitializer;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.spi.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A provider module present in a parent layer and again in a child layer, as when a runtime
 * re-layers the application over the boot layer: the child layer's loader sees it twice.
 */
class LayeredProvidersTest {
    private static final String MODULE = "test.dup";
    private static final String SCI = MODULE + ".Sci";

    @TempDir Path tmp;
    private ModuleLayer parent;
    private ModuleLayer child;

    @BeforeEach
    void layers() throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("test/dup"));
        Path info = src.resolve("module-info.java");
        Files.writeString(info, "module " + MODULE + " { requires jakarta.servlet;"
                + " provides jakarta.servlet.ServletContainerInitializer with " + SCI + "; }");
        Path sci = src.resolve("test/dup/Sci.java");
        Files.writeString(sci, """
                package test.dup;
                public class Sci implements jakarta.servlet.ServletContainerInitializer {
                    public void onStartup(java.util.Set<Class<?>> c, jakarta.servlet.ServletContext ctx) {}
                }""");
        Path out = tmp.resolve("out");
        int rc = ToolProvider.findFirst("javac").orElseThrow().run(System.out, System.err,
                "--module-path", codeSource(ServletContainerInitializer.class).toString(),
                "-d", out.toString(), info.toString(), sci.toString());
        assertEquals(0, rc, "the test module must compile");

        Configuration parentCfg = ModuleLayer.boot().configuration()
                .resolve(ModuleFinder.of(out), ModuleFinder.of(), Set.of(MODULE));
        parent = ModuleLayer.defineModulesWithOneLoader(parentCfg, List.of(ModuleLayer.boot()),
                getClass().getClassLoader()).layer();
        Configuration childCfg = parentCfg.resolve(ModuleFinder.of(out), ModuleFinder.of(), Set.of(MODULE));
        child = ModuleLayer.defineModulesWithOneLoader(childCfg, List.of(parent),
                parent.findLoader(MODULE)).layer();
    }

    @Test
    void theChildLoaderSeesTheProviderOfBothLayers() {
        var raw = ServiceLoader.load(ServletContainerInitializer.class, childLoader()).stream()
                .filter(p -> p.type().getName().equals(SCI)).toList();
        assertEquals(2, raw.size(), "precondition: the exposure this test guards against");
    }

    @Test
    void providersKeepOnlyTheChildLayersCopy() {
        var found = ApplicationSources.providers(ServletContainerInitializer.class, childLoader()).stream()
                .filter(p -> p.type().getName().equals(SCI)).toList();
        assertEquals(1, found.size());
        assertSame(child, found.getFirst().type().getModule().getLayer());
    }

    @Test
    void anInitializerOfBothLayersRunsOnce() {
        var found = ApplicationSources.initializers(childLoader()).stream()
                .filter(i -> i.sci().getClass().getName().equals(SCI)).toList();
        assertEquals(1, found.size());
        assertSame(child, found.getFirst().sci().getClass().getModule().getLayer());
    }

    private ClassLoader childLoader() {
        return child.findLoader(MODULE);
    }

    private static Path codeSource(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
