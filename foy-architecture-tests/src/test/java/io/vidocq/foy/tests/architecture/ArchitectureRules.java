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

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Stream;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitCallTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Foy's own rules, which no scanner knows, as ArchUnit rules (Vidocq/vidocq#289). Each one names the document it
 * comes from. A rule holds for whatever classes it is given: {@link ArchitectureRulesTest} gives it the shipped
 * classes, {@link ArchitectureRulesSelfTest} fixtures that break it.
 *
 * <p>The root package of each shipped module: {@code io.vidocq.foy.spi} (foy-api), {@code io.vidocq.foy.processor}
 * (foy-processor), {@code io.vidocq.foy.internal} (foy-core), {@code io.vidocq.foy.cdi.vauban} (foy-cdi-vauban)
 * and {@code io.vidocq.foy.chappe} (foy-chappe).
 */
final class ArchitectureRules {

    /**
     * The JDK packages a runtime module may use: java.base, and java.xml for the web.xml parser. A
     * {@code javax} package outside the JDK (the old {@code javax.servlet}, {@code javax.inject}) is not one.
     */
    private static final String[] JDK = {
        "java..", "javax.crypto..", "javax.net..", "javax.security..", "javax.xml..", "org.w3c.dom..", "org.xml.sax.."
    };

    /** The JDK packages of an annotation processor: java.base and java.compiler. */
    private static final String[] JDK_COMPILER = {
        "java..", "javax.annotation.processing..", "javax.lang.model..", "javax.tools.."
    };

    /**
     * README, Modules: foy-api holds the SPI interfaces, with no dependency beyond the Servlet API. Its module
     * descriptor also reads the annotation and CDI APIs, statically.
     */
    static final ArchRule API_DEPENDENCIES = classes()
            .that()
            .resideInAPackage("io.vidocq.foy.spi..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(packages(
                    JDK, "jakarta.servlet..", "jakarta.annotation..", "jakarta.enterprise..", "io.vidocq.foy.spi.."))
            .because("foy-api depends on the Servlet API alone, plus the annotation and CDI APIs read statically"
                    + " (README, Modules; module-info.java of foy-api)");

    /**
     * README, Modules: foy-processor is a build-time tool with zero dependency, {@code java.compiler} only, never
     * shipped in the application.
     */
    static final ArchRule PROCESSOR_DEPENDENCIES = classes()
            .that()
            .resideInAPackage("io.vidocq.foy.processor..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(packages(JDK_COMPILER, "io.vidocq.foy.processor.."))
            .because("foy-processor has zero dependency, java.compiler only (README, Modules)");

    /**
     * Workspace AGENTS.md, "Zero external dependency for what ships", and the module descriptor of foy-core: the
     * Servlet API, the CDI and annotation APIs read statically, foy-api, and chappe-api for the HTTP transport
     * (README, "transport decoupling"). Never Chappe's engine, never Vauban.
     */
    static final ArchRule CORE_DEPENDENCIES = classes()
            .that()
            .resideInAPackage("io.vidocq.foy.internal..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(packages(
                    JDK,
                    "jakarta.servlet..",
                    "jakarta.annotation..",
                    "jakarta.enterprise..",
                    "io.vidocq.chappe.api..",
                    "io.vidocq.foy.spi..",
                    "io.vidocq.foy.internal.."))
            .because("foy-core depends on the Jakarta APIs it implements or reads, foy-api and chappe-api only"
                    + " (workspace AGENTS.md, zero external dependency; module-info.java of foy-core)");

    /**
     * README, Modules: foy-cdi-vauban is the bridge to Vauban, the one module that uses it. Its module descriptor
     * reads foy-api, the CDI API, vauban-core and vauban-webcontexts, not foy-core.
     */
    static final ArchRule CDI_VAUBAN_DEPENDENCIES = classes()
            .that()
            .resideInAPackage("io.vidocq.foy.cdi.vauban..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(packages(
                    JDK,
                    "jakarta.servlet..",
                    "jakarta.enterprise..",
                    "jakarta.inject..",
                    "io.vidocq.vauban.core..",
                    "io.vidocq.vauban.webcontexts..",
                    "io.vidocq.foy.spi..",
                    "io.vidocq.foy.cdi.vauban.."))
            .because("foy-cdi-vauban bridges foy-api to Vauban, the only module that uses Vauban"
                    + " (README, Modules; module-info.java of foy-cdi-vauban)");

    /**
     * README, Modules: foy-chappe is the Chappe HTTP adapter. Its module descriptor reads foy-core and
     * chappe-api: Chappe's engine (chappe-core, chappe-http) is the application's choice, never Foy's.
     */
    static final ArchRule CHAPPE_ADAPTER_DEPENDENCIES = classes()
            .that()
            .resideInAPackage("io.vidocq.foy.chappe..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(packages(
                    JDK,
                    "jakarta.servlet..",
                    "jakarta.enterprise..",
                    "io.vidocq.chappe.api..",
                    "io.vidocq.foy.spi..",
                    "io.vidocq.foy.internal..",
                    "io.vidocq.foy.chappe.."))
            .because("foy-chappe adapts foy-core to chappe-api, never to Chappe's engine"
                    + " (README, Modules; module-info.java of foy-chappe)");

    /**
     * README, Modules, and the module descriptors: foy-api is the bottom layer and reads no other Foy module;
     * foy-core builds on it; foy-cdi-vauban builds on foy-api alone, foy-chappe on foy-core; nothing builds on
     * the last two. foy-processor stands alone, a build-time tool.
     */
    static final ArchRule LAYERS = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("API")
            .definedBy("io.vidocq.foy.spi..")
            .layer("Processor")
            .definedBy("io.vidocq.foy.processor..")
            .layer("Core")
            .definedBy("io.vidocq.foy.internal..")
            .layer("CDI Vauban")
            .definedBy("io.vidocq.foy.cdi.vauban..")
            .layer("Chappe adapter")
            .definedBy("io.vidocq.foy.chappe..")
            .whereLayer("API")
            .mayNotAccessAnyLayer()
            .whereLayer("Processor")
            .mayNotAccessAnyLayer()
            .whereLayer("Processor")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Core")
            .mayOnlyBeAccessedByLayers("Chappe adapter")
            .whereLayer("CDI Vauban")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Chappe adapter")
            .mayNotBeAccessedByAnyLayer()
            .because("foy-api, foy-core and its adapters are layers, and foy-processor stands alone"
                    + " (README, Modules; the module-info.java files)");

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

    private static final DescribedPredicate<JavaClass> NOT_A_REFLECTIVE_FALLBACK = DescribedPredicate.describe(
            "are not a documented reflective fallback", type -> !REFLECTIVE_FALLBACKS.containsKey(topLevel(type)));

    /**
     * Workspace AGENTS.md, "Maximum static code generation": what would be reflection at run time is generated at
     * build time. internals.adoc: reflection remains only as the documented fallbacks above. A class looked up
     * by name, a member or an annotation read reflectively, is what this rule catches.
     */
    static final ArchRule NO_REFLECTION = noClasses()
            .that(NOT_A_REFLECTIVE_FALLBACK)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("java.lang.reflect..")
            .orShould()
            .callMethodWhere(target(owner(type(Class.class)))
                    .and(target(nameMatching("forName|newInstance|getDeclared.*|getMethods?|getFields?"
                            + "|getConstructors?|getRecordComponents|getAnnotations?|getAnnotationsByType"
                            + "|isAnnotationPresent"))))
            .because("Foy uses no reflection outside its documented fallbacks"
                    + " (workspace AGENTS.md, static code generation; internals.adoc, Component resolution)");

    private static final DescribedPredicate<CodeUnitCallTarget> WITHOUT_A_THREAD_FACTORY = DescribedPredicate.describe(
            "takes no ThreadFactory",
            call -> call.getRawParameterTypes().stream().noneMatch(p -> p.isEquivalentTo(ThreadFactory.class)));

    /**
     * Workspace AGENTS.md, "Virtual Threads everywhere for I/O": no platform-thread pool without a documented
     * reason. Foy runs each request, async task and I/O callback on a virtual thread (concepts.adoc). An executor
     * given a virtual-thread factory, as the session reaper is, keeps the rule.
     */
    static final ArchRule NO_PLATFORM_THREADS = noClasses()
            .should()
            .callMethodWhere(target(owner(type(Executors.class)))
                    .and(target(nameMatching("newFixedThreadPool|newCachedThreadPool|newSingleThreadExecutor"
                            + "|newScheduledThreadPool|newSingleThreadScheduledExecutor|newWorkStealingPool"
                            + "|defaultThreadFactory|privilegedThreadFactory")))
                    .and(target(WITHOUT_A_THREAD_FACTORY)))
            .orShould()
            .callConstructorWhere(
                    target(owner(assignableTo(ThreadPoolExecutor.class))).and(target(WITHOUT_A_THREAD_FACTORY)))
            .orShould()
            .callConstructorWhere(target(owner(assignableTo(ForkJoinPool.class))))
            .orShould()
            .callMethod(Thread.class, "ofPlatform")
            .orShould()
            .callConstructorWhere(target(owner(type(Thread.class))))
            .because("Foy runs every task on a virtual thread (workspace AGENTS.md, Virtual Threads; concepts.adoc)");

    /**
     * migration.adoc and usage.adoc: servlet code runs on a Foy virtual thread, not on Chappe's connection
     * thread, and async work on further virtual threads, so a value kept in a ThreadLocal is not seen where the
     * request continues; each virtual thread also has thread-locals of its own, so a ThreadLocal caches nothing.
     */
    static final ArchRule NO_THREAD_LOCAL = noClasses()
            .should()
            .dependOnClassesThat()
            .areAssignableTo(ThreadLocal.class)
            .because("a request moves across virtual threads, so a ThreadLocal neither follows it nor caches"
                    + " anything (migration.adoc; usage.adoc)");

    private ArchitectureRules() {
        // Rules only.
    }

    private static String[] packages(String[] jdk, String... others) {
        return Stream.concat(Stream.of(jdk), Stream.of(others)).toArray(String[]::new);
    }

    private static String topLevel(JavaClass type) {
        JavaClass top = type;
        while (top.getEnclosingClass().isPresent()) {
            top = top.getEnclosingClass().get();
        }
        return top.getName();
    }
}
