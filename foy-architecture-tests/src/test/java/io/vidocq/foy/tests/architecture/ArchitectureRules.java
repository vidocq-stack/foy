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
package io.vidocq.foy.tests.architecture;

import java.util.Map;

import io.vidocq.archrules.Jdk;
import io.vidocq.archrules.Layer;
import io.vidocq.archrules.Packages;
import io.vidocq.archrules.PlatformThreads;
import io.vidocq.archrules.VidocqArchRules;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Foy's own rules, which no scanner knows, as ArchUnit rules (Vidocq/vidocq#289). They come from the shared
 * {@code vidocq-archrules} library of {@code vidocq-parent}, set to Foy's packages; each names the document it
 * comes from. A rule holds for whatever classes it is given: {@link ArchitectureRulesTest} gives it the shipped
 * classes, {@link ArchitectureRulesSelfTest} fixtures that break it.
 *
 * <p>The root package of each shipped module: {@code io.vidocq.foy.spi} (foy-api), {@code io.vidocq.foy.processor}
 * (foy-processor), {@code io.vidocq.foy.internal} (foy-core), {@code io.vidocq.foy.cdi.vauban} (foy-cdi-vauban)
 * and {@code io.vidocq.foy.chappe} (foy-chappe).
 */
final class ArchitectureRules {

    private static final String README_MODULES = "README, Modules";

    /**
     * README, Modules: foy-api holds the SPI interfaces, with no dependency beyond the Servlet API. Its module
     * descriptor also reads the annotation and CDI APIs, statically.
     */
    static final ArchRule API_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.foy.spi.."},
            Packages.of(
                    Jdk.RUNTIME, "jakarta.servlet..", "jakarta.annotation..", "jakarta.enterprise..", "io.vidocq.foy.spi.."),
            README_MODULES + "; module-info.java of foy-api");

    /**
     * README, Modules: foy-processor is a build-time tool with zero dependency, {@code java.compiler} only, never
     * shipped in the application.
     */
    static final ArchRule PROCESSOR_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.foy.processor.."},
            Packages.of(Jdk.COMPILER, "io.vidocq.foy.processor.."),
            README_MODULES);

    /**
     * Workspace AGENTS.md, "Zero external dependency for what ships", and the module descriptor of foy-core: the
     * Servlet API, the CDI and annotation APIs read statically, foy-api, and chappe-api for the HTTP transport
     * (README, "transport decoupling"). Never Chappe's engine, never Vauban.
     */
    static final ArchRule CORE_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.foy.internal.."},
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.servlet..",
                    "jakarta.annotation..",
                    "jakarta.enterprise..",
                    "io.vidocq.chappe.api..",
                    "io.vidocq.foy.spi..",
                    "io.vidocq.foy.internal.."),
            "workspace AGENTS.md, zero external dependency; module-info.java of foy-core");

    /**
     * README, Modules: foy-cdi-vauban is the bridge to Vauban, the one module that uses it. Its module descriptor
     * reads foy-api, the CDI API, vauban-core and vauban-webcontexts, not foy-core.
     */
    static final ArchRule CDI_VAUBAN_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.foy.cdi.vauban.."},
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.servlet..",
                    "jakarta.enterprise..",
                    "jakarta.inject..",
                    "io.vidocq.vauban.core..",
                    "io.vidocq.vauban.webcontexts..",
                    "io.vidocq.foy.spi..",
                    "io.vidocq.foy.cdi.vauban.."),
            README_MODULES + "; module-info.java of foy-cdi-vauban");

    /**
     * README, Modules: foy-chappe is the Chappe HTTP adapter. Its module descriptor reads foy-core and
     * chappe-api: Chappe's engine (chappe-core, chappe-http) is the application's choice, never Foy's.
     */
    static final ArchRule CHAPPE_ADAPTER_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.foy.chappe.."},
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.servlet..",
                    "jakarta.enterprise..",
                    "io.vidocq.chappe.api..",
                    "io.vidocq.foy.spi..",
                    "io.vidocq.foy.internal..",
                    "io.vidocq.foy.chappe.."),
            README_MODULES + "; module-info.java of foy-chappe");

    /**
     * README, Modules, and the module descriptors: foy-api is the bottom layer and reads no other Foy module;
     * foy-core builds on it; foy-cdi-vauban builds on foy-api alone, foy-chappe on foy-core; nothing builds on
     * the last two. foy-processor stands alone, a build-time tool.
     */
    static final ArchRule LAYERS = VidocqArchRules.layers(
            README_MODULES + "; the module-info.java files",
            Layer.named("API").in("io.vidocq.foy.spi..").accessesNoLayer(),
            Layer.named("Processor").in("io.vidocq.foy.processor..").accessesNoLayer().accessibleByNone(),
            Layer.named("Core").in("io.vidocq.foy.internal..").accessibleOnlyBy("Chappe adapter"),
            Layer.named("CDI Vauban").in("io.vidocq.foy.cdi.vauban..").accessibleByNone(),
            Layer.named("Chappe adapter").in("io.vidocq.foy.chappe..").accessibleByNone());

    /**
     * internals.adoc, Component resolution: the documented reflective fallbacks, each with its reason. The same
     * list as {@code NoProductReflectionTest} in foy-core, plus the class of foy-cdi-vauban that page names.
     * Nested classes share the entry of their top-level class.
     */
    static final Map<String, String> REFLECTIVE_FALLBACKS = Map.of(
            "io.vidocq.foy.internal.gen.WebComponentRegistry",
            "the registry tiers: a class loaded by name, the generated companion, the last and logged reflective tier",
            "io.vidocq.foy.internal.boot.ComponentFactory",
            "its Reflective factory, used only on explicit opt-out and in tests",
            "io.vidocq.foy.internal.gen.IndexedHandlesTypesResolver",
            "loads the entries of META-INF/foy/class-index.list by name, without initialising them",
            "io.vidocq.foy.cdi.vauban.CdiWebComponentsCreator",
            "loads the class names recorded at build time, as a synthetic bean parameter cannot carry a Class");

    /**
     * Workspace AGENTS.md, "Maximum static code generation", and internals.adoc: reflection remains only as the
     * documented fallbacks above.
     */
    static final ArchRule NO_REFLECTION = VidocqArchRules.noReflection(
            REFLECTIVE_FALLBACKS, "workspace AGENTS.md, static code generation; internals.adoc, Component resolution");

    /**
     * Workspace AGENTS.md, "Virtual Threads everywhere for I/O": no platform-thread pool without a documented
     * reason. Foy runs each request, async task and I/O callback on a virtual thread (concepts.adoc). An executor
     * given a virtual-thread factory, as the session reaper is, keeps the rule.
     */
    static final ArchRule NO_PLATFORM_THREADS = VidocqArchRules.noPlatformThreads(
            PlatformThreads.UNLESS_THREAD_FACTORY_GIVEN, "workspace AGENTS.md, Virtual Threads; concepts.adoc");

    /**
     * migration.adoc and usage.adoc: servlet code runs on a Foy virtual thread, not on Chappe's connection
     * thread, and async work on further virtual threads, so a value kept in a ThreadLocal is not seen where the
     * request continues; each virtual thread also has thread-locals of its own, so a ThreadLocal caches nothing.
     */
    static final ArchRule NO_THREAD_LOCAL = VidocqArchRules.noThreadLocal(
            "a request moves across virtual threads, so a ThreadLocal neither follows it nor caches anything",
            "migration.adoc; usage.adoc");

    private ArchitectureRules() {
        // Rules only.
    }
}
