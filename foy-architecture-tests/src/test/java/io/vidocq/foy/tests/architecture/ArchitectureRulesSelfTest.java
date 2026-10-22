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

import static io.vidocq.foy.tests.architecture.FoyClasses.SHIPPED;
import static io.vidocq.foy.tests.architecture.FoyClasses.SHIPPED_AND_FIXTURES;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;

import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.vidocq.foy.cdi.vauban.archfixture.CdiReachingIntoCore;
import io.vidocq.foy.chappe.archfixture.AdapterReachingIntoTheChappeEngine;
import io.vidocq.foy.internal.archfixture.CoreReachingIntoTheAdapter;
import io.vidocq.foy.internal.archfixture.CoreReachingIntoVauban;
import io.vidocq.foy.processor.archfixture.ProcessorReachingIntoTheSpi;
import io.vidocq.foy.spi.archfixture.SpiReachingIntoCore;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Each architecture rule catches what it forbids: a rule that passes on the shipped classes proves nothing unless
 * it fails on a class that breaks it. A violation must name the fixture, so that a rule failing for another
 * reason (an empty layer, a class the rule does not select) does not count.
 */
class ArchitectureRulesSelfTest {

    /** The module and layer rules, each with a fixture of an {@code archfixture} package that breaks it. */
    static Stream<Arguments> moduleRuleBreaches() {
        return Stream.of(
                ruleWith("API_DEPENDENCIES", ArchitectureRules.API_DEPENDENCIES, SpiReachingIntoCore.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, SpiReachingIntoCore.class),
                ruleWith(
                        "PROCESSOR_DEPENDENCIES",
                        ArchitectureRules.PROCESSOR_DEPENDENCIES,
                        ProcessorReachingIntoTheSpi.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, ProcessorReachingIntoTheSpi.class),
                ruleWith("CORE_DEPENDENCIES", ArchitectureRules.CORE_DEPENDENCIES, CoreReachingIntoTheAdapter.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, CoreReachingIntoTheAdapter.class),
                ruleWith("CORE_DEPENDENCIES", ArchitectureRules.CORE_DEPENDENCIES, CoreReachingIntoVauban.class),
                ruleWith(
                        "CDI_VAUBAN_DEPENDENCIES",
                        ArchitectureRules.CDI_VAUBAN_DEPENDENCIES,
                        CdiReachingIntoCore.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, CdiReachingIntoCore.class),
                ruleWith(
                        "CHAPPE_ADAPTER_DEPENDENCIES",
                        ArchitectureRules.CHAPPE_ADAPTER_DEPENDENCIES,
                        AdapterReachingIntoTheChappeEngine.class));
    }

    @ParameterizedTest(name = "{0} catches {1}")
    @MethodSource("moduleRuleBreaches")
    void eachModuleRuleCatchesAFixtureAmongTheShippedClasses(ArchRule rule, Class<?> fixture) {
        assertNamesTheFixture(rule, SHIPPED_AND_FIXTURES, fixture);
    }

    /** The code rules, each with a fixture below that breaks it. */
    static Stream<Arguments> codeRuleBreaches() {
        return Stream.of(
                ruleWith("NO_REFLECTION", ArchitectureRules.NO_REFLECTION, LooksUpAClassByName.class),
                ruleWith("NO_REFLECTION", ArchitectureRules.NO_REFLECTION, ReadsAMethodReflectively.class),
                ruleWith("NO_REFLECTION", ArchitectureRules.NO_REFLECTION, ReadsAnAnnotationReflectively.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, UsesAFixedThreadPool.class),
                ruleWith(
                        "NO_PLATFORM_THREADS",
                        ArchitectureRules.NO_PLATFORM_THREADS,
                        SchedulesOnAPlatformThread.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, BuildsAThreadPoolExecutor.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, StartsAPlatformThread.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, CreatesAThread.class),
                ruleWith("NO_THREAD_LOCAL", ArchitectureRules.NO_THREAD_LOCAL, KeepsAThreadLocal.class));
    }

    @ParameterizedTest(name = "{0} catches {1}")
    @MethodSource("codeRuleBreaches")
    void eachCodeRuleCatchesAFixture(ArchRule rule, Class<?> fixture) {
        assertNamesTheFixture(rule, new ClassFileImporter().importClasses(fixture), fixture);
    }

    /** What the code rules let through: look-alikes of the breaches that keep the rule. */
    static Stream<Arguments> codeRuleLookAlikes() {
        return Stream.of(
                ruleWith("NO_REFLECTION", ArchitectureRules.NO_REFLECTION, LooksUpACharsetByName.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, UsesVirtualThreads.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, SchedulesOnAVirtualThread.class),
                ruleWith("NO_THREAD_LOCAL", ArchitectureRules.NO_THREAD_LOCAL, UsesVirtualThreads.class));
    }

    @ParameterizedTest(name = "{0} keeps {1}")
    @MethodSource("codeRuleLookAlikes")
    void eachCodeRuleKeepsALookAlike(ArchRule rule, Class<?> fixture) {
        var result = rule.evaluate(new ClassFileImporter().importClasses(fixture));
        assertFalse(result.hasViolation(), () -> result.getFailureReport().toString());
    }

    @Test
    void theReflectionAllowlistNamesShippedClasses() {
        // A stale entry would exempt a class that no longer exists, or let a new class take its name.
        for (String allowed : ArchitectureRules.REFLECTIVE_FALLBACKS.keySet()) {
            assertTrue(SHIPPED.contain(allowed), () -> allowed + " is not a shipped class");
        }
    }

    private static Arguments ruleWith(String name, ArchRule rule, Class<?> fixture) {
        return Arguments.of(named(name, rule), fixture);
    }

    private static void assertNamesTheFixture(ArchRule rule, JavaClasses classes, Class<?> fixture) {
        List<String> details = rule.evaluate(classes).getFailureReport().getDetails();
        assertTrue(
                details.stream().anyMatch(detail -> detail.contains(fixture.getName())),
                () -> "expected a violation by " + fixture.getName() + " of: " + rule.getDescription() + ", got "
                        + details);
    }

    static final class LooksUpAClassByName {
        Class<?> find(String name) throws ClassNotFoundException {
            return Class.forName(name);
        }
    }

    static final class ReadsAMethodReflectively {
        Object method() throws NoSuchMethodException {
            return String.class.getDeclaredMethod("length");
        }
    }

    static final class ReadsAnAnnotationReflectively {
        boolean deprecated(Class<?> type) {
            return type.isAnnotationPresent(Deprecated.class);
        }
    }

    static final class LooksUpACharsetByName {
        Charset charset(String name) {
            return Charset.forName(name);
        }
    }

    static final class UsesAFixedThreadPool {
        ExecutorService pool() {
            return Executors.newFixedThreadPool(4);
        }
    }

    static final class SchedulesOnAPlatformThread {
        ScheduledExecutorService reaper() {
            return Executors.newSingleThreadScheduledExecutor();
        }
    }

    static final class BuildsAThreadPoolExecutor {
        ExecutorService pool() {
            return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        }
    }

    static final class StartsAPlatformThread {
        Thread start(Runnable task) {
            return Thread.ofPlatform().start(task);
        }
    }

    static final class CreatesAThread {
        Thread create(Runnable task) {
            return new Thread(task);
        }
    }

    static final class UsesVirtualThreads {
        ExecutorService executor() {
            return Executors.newVirtualThreadPerTaskExecutor();
        }
    }

    static final class SchedulesOnAVirtualThread {
        ScheduledExecutorService reaper() {
            return Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        }
    }

    static final class KeepsAThreadLocal {
        private static final ThreadLocal<StringBuilder> BUFFER = ThreadLocal.withInitial(StringBuilder::new);

        StringBuilder buffer() {
            return BUFFER.get();
        }
    }
}
