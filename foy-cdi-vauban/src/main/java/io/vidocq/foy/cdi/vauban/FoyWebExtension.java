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

import io.vidocq.foy.spi.cdi.CdiWebComponents;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ConversationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.NormalScope;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.inject.Model;
import jakarta.enterprise.inject.Stereotype;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Validation;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.inject.Scope;
import jakarta.inject.Singleton;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.annotation.WebServlet;

import java.lang.annotation.Annotation;
import java.util.EventListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Build compatible extension making {@code @WebServlet}, {@code @WebFilter} and
 * {@code @WebListener} classes CDI beans and indexing them for Foy.
 *
 * <p>Run at build time by vauban's annotation processor (found through
 * {@code ServiceLoader} when foy-cdi-vauban is on the processor path):</p>
 * <ul>
 *   <li>{@link #defaultScope} gives a web component without a bean-defining annotation the
 *       {@code @Dependent} scope, so CDI injects it; Foy calls each factory once, so servlets
 *       and filters stay singletons per declaration (Servlet 6.1 §2.2).</li>
 *   <li>{@link #collect} records the beans whose class carries a Web* annotation. A CDI bean that
 *       merely implements {@code Servlet} is not a web component. Vauban's processor runs
 *       {@code @Registration} over the beans found <em>before</em> {@code @Enhancement}, so a class
 *       that only became a bean through {@link #defaultScope} never reaches it: {@link #defaultScope}
 *       records the concrete classes it sees as well. The index may therefore name a class that
 *       ends up not being a bean; Foy's discovery skips any class without a bean.</li>
 *   <li>{@link #validate} fails the build on duplicate servlet names or duplicate filter names.
 *       A name left out defaults to the binary class name, as in foy-processor and the runtime
 *       registry.</li>
 *   <li>{@link #index} registers a {@link CdiWebComponents} singleton listing the components, so
 *       Foy's discovery never walks the bean set.</li>
 * </ul>
 *
 * <p>Annotation values are read through the CDI language model, never through reflection: at build
 * time the application classes are not loadable.</p>
 */
public class FoyWebExtension implements BuildCompatibleExtension {

    /** Name of the synthetic bean parameter holding the comma-joined binary class names. */
    static final String CLASSES_PARAM = "classes";

    /** Built-in scopes, pseudo-scopes and the {@code @Model} stereotype, matched by name before the model is asked. */
    private static final Set<String> BUILT_IN_BEAN_DEFINING = Set.of(
            Dependent.class.getName(), ApplicationScoped.class.getName(), RequestScoped.class.getName(),
            SessionScoped.class.getName(), ConversationScoped.class.getName(), Singleton.class.getName(),
            Model.class.getName());

    /**
     * Packages whose annotation types are never scopes or stereotypes (the built-in scopes are
     * matched first, by name), so an unresolvable declaration there does not block {@code @Dependent}.
     */
    private static final List<String> KNOWN_API_PACKAGES = List.of(
            "java.", "javax.", "jdk.", "jakarta.servlet.", "jakarta.annotation.", "jakarta.inject.",
            "jakarta.enterprise.", "jakarta.interceptor.", "jakarta.decorator.");

    /**
     * Collected web components by binary class name, sorted: vauban's enhancement and registration
     * order is not a contract, and a sorted index keeps the build output and the error messages
     * reproducible.
     */
    private final Map<String, Component> components = new TreeMap<>();

    /** Public no-arg constructor, required by {@code ServiceLoader}. */
    public FoyWebExtension() {}

    /**
     * Web components without a scope become {@code @Dependent} beans (one instance per declaration,
     * held by Foy). A component that already has a scope or a stereotype keeps it.
     *
     * @param clazz the web component class being enhanced
     */
    @Enhancement(types = Object.class, withAnnotations = {WebServlet.class, WebFilter.class, WebListener.class})
    public void defaultScope(ClassConfig clazz) {
        ClassInfo cls = clazz.info();
        if (!hasBeanDefiningAnnotation(cls)) {
            clazz.addAnnotation(Dependent.class);
        }
        if (cls.isPlainClass() && !cls.isAbstract()) record(cls);
    }

    /**
     * Keeps the class beans whose class carries a Web* annotation.
     *
     * @param bean a bean whose types include {@code Servlet}, {@code Filter} or {@code EventListener}
     */
    @Registration(types = {Servlet.class, Filter.class, EventListener.class})
    public void collect(BeanInfo bean) {
        if (bean.isClassBean()) record(bean.declaringClass());
    }

    /** Records {@code cls} when it carries a Web* annotation; recording a class twice is harmless. */
    private void record(ClassInfo cls) {
        String servletName = nameOf(cls, WebServlet.class, "name");
        String filterName = nameOf(cls, WebFilter.class, "filterName");
        if (servletName == null && filterName == null && !cls.hasAnnotation(WebListener.class)) return;
        components.putIfAbsent(cls.name(), new Component(cls.name(), servletName, filterName));
    }

    /**
     * Registers the {@link CdiWebComponents} index. Nothing is registered when the compilation holds
     * no web component: Foy then falls back to the bean set, which has none either.
     *
     * @param syn the synthetic components builder
     */
    @Synthesis
    public void index(SyntheticComponents syn) {
        if (components.isEmpty()) return;
        // One comma-joined String: vauban's build-time synthetic metadata drops Class<?>[] params.
        syn.addBean(CdiWebComponents.class)
                .type(CdiWebComponents.class)
                .scope(Singleton.class)
                .withParam(CLASSES_PARAM, String.join(",", components.keySet()))
                .createWith(CdiWebComponentsCreator.class);
    }

    /**
     * Fails the build when two components share a servlet name, or two share a filter name.
     *
     * @param messages the build messages
     */
    @Validation
    public void validate(Messages messages) {
        Map<String, String> servletNames = new HashMap<>();
        Map<String, String> filterNames = new HashMap<>();
        for (Component c : components.values()) {
            checkUnique(messages, "servlet", servletNames, c.servletName(), c.className());
            checkUnique(messages, "filter", filterNames, c.filterName(), c.className());
        }
    }

    private static void checkUnique(Messages messages, String kind, Map<String, String> seen,
            String name, String className) {
        if (name == null) return;
        String previous = seen.putIfAbsent(name, className);
        if (previous != null) {
            messages.error("foy: duplicate " + kind + " name '" + name + "' declared by " + previous
                    + " and " + className + "; " + kind + " names must be unique within a web application");
        }
    }

    /**
     * The component name declared by {@code annotation}'s {@code member}, the binary class name when
     * the member is absent or empty, or {@code null} when the class does not carry the annotation.
     */
    private static String nameOf(ClassInfo cls, Class<? extends Annotation> annotation, String member) {
        AnnotationInfo info = cls.annotation(annotation);
        if (info == null) return null;
        AnnotationMember value = info.member(member);
        String name = value == null ? null : value.asString();
        return name == null || name.isEmpty() ? cls.name() : name;
    }

    /**
     * Whether {@code cls} already has a scope or a stereotype, or may have one: an annotation type
     * the model cannot resolve (a library stereotype or scope, say) counts as bean-defining, so the
     * class is left alone rather than given a second scope. Annotation types of the JDK, the Servlet
     * API and the CDI / injection APIs are known not to be scopes or stereotypes unless listed in
     * {@link #BUILT_IN_BEAN_DEFINING} or meta-annotated as such.
     */
    private static boolean hasBeanDefiningAnnotation(ClassInfo cls) {
        for (AnnotationInfo annotation : cls.annotations()) {
            String name = annotation.name();
            if (BUILT_IN_BEAN_DEFINING.contains(name)) return true;
            ClassInfo declaration;
            try {
                declaration = annotation.declaration();
            } catch (IllegalArgumentException e) {
                // vauban's model throws for an annotation type it has not indexed.
                if (isKnownApiAnnotation(name)) continue;
                return true;
            }
            if (declaration.hasAnnotation(NormalScope.class)
                    || declaration.hasAnnotation(Scope.class)
                    || declaration.hasAnnotation(Stereotype.class)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isKnownApiAnnotation(String annotationName) {
        for (String prefix : KNOWN_API_PACKAGES) {
            if (annotationName.startsWith(prefix)) return true;
        }
        return false;
    }

    private record Component(String className, String servletName, String filterName) {}
}
