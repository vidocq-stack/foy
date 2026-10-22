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
package io.vidocq.foy.processor;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compile-time model of one web component, extracted from a {@link TypeElement}.
 *
 * @param packageName      the package of the component, empty for the unnamed package
 * @param binarySimpleName the binary name minus the package ({@code Outer$Inner})
 * @param typeFqcn         the canonical name of the component class
 * @param annotated        whether the class carries a {@code @Web*} component annotation
 * @param kind             the component kind
 * @param name             the component name, or {@code null}
 * @param urlPatterns      the URL patterns
 * @param initParams       alternating init-parameter names and values
 * @param loadOnStartup    the load-on-startup order, {@link Integer#MIN_VALUE} when absent
 * @param asyncSupported   whether asynchronous processing is supported
 * @param dispatcherTypes  the {@code DispatcherType} constant names, each once
 * @param servletNames     the servlet names a filter applies to
 * @param multipart        the Java expression building the multipart configuration, or {@code null}
 * @param security         the Java expression building the servlet security element, or {@code null}
 * @param declaredRoles    the declared security roles
 * @param runAs            the run-as role, or {@code null}
 * @param handlesTypes     the binary names handled by an initializer
 */
record ComponentModel(String packageName,
                      String binarySimpleName,
                      String typeFqcn,
                      boolean annotated,
                      Kind kind,
                      String name,
                      List<String> urlPatterns,
                      List<String[]> initParams,
                      int loadOnStartup,
                      boolean asyncSupported,
                      List<String> dispatcherTypes,
                      List<String> servletNames,
                      String multipart,
                      String security,
                      List<String> declaredRoles,
                      String runAs,
                      List<String> handlesTypes) {

    /** Component kinds, mirroring {@code WebComponentDescriptor.Kind} by constant name. */
    enum Kind { SERVLET, FILTER, LISTENER, INITIALIZER, PLAIN }

    private static final String PKG = "jakarta.servlet.";
    private static final String VALUE = "value";
    static final String WEB_SERVLET = PKG + "annotation.WebServlet";
    static final String WEB_FILTER = PKG + "annotation.WebFilter";
    static final String WEB_LISTENER = PKG + "annotation.WebListener";
    static final String SERVLET = PKG + "Servlet";
    static final String FILTER = PKG + "Filter";
    static final String INITIALIZER = PKG + "ServletContainerInitializer";

    /** The listener interfaces a {@code @WebListener} class must implement at least one of. */
    static final List<String> LISTENERS = List.of(
            PKG + "ServletContextListener", PKG + "ServletContextAttributeListener",
            PKG + "ServletRequestListener", PKG + "ServletRequestAttributeListener",
            PKG + "http.HttpSessionListener", PKG + "http.HttpSessionAttributeListener",
            PKG + "http.HttpSessionIdListener");

    /**
     * Extracts a model from a class: annotated components, then unannotated concrete servlets,
     * filters, listeners and initializers. Misuse of the annotations is reported as a compile error.
     *
     * @return the model, or empty when the class is not a web component or is invalid
     */
    static Optional<ComponentModel> from(TypeElement type, ProcessingEnvironment env) {
        AnnotationMirror main = mainAnnotation(type);
        boolean initializer = is(type, INITIALIZER, env);
        Kind kind = kind(type, main, initializer, env);
        if (kind == null) {
            return Optional.empty();
        }
        String fqcn = env.getElementUtils().getBinaryName(type).toString();
        MainElements e = EnumSet.of(Kind.SERVLET, Kind.FILTER).contains(kind)
                ? mainElements(type, main, kind, fqcn, env) : MainElements.none();
        if (e == null) {
            return Optional.empty();
        }
        AnnotationMirror multipart = AnnotationReader.find(type, PKG + "annotation.MultipartConfig");
        AnnotationMirror security = AnnotationReader.find(type, PKG + "annotation.ServletSecurity");
        AnnotationMirror roles = AnnotationReader.find(type, "jakarta.annotation.security.DeclareRoles");
        AnnotationMirror runAs = AnnotationReader.find(type, "jakarta.annotation.security.RunAs");
        String pkg = env.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        String binarySimple = pkg.isEmpty() ? fqcn : fqcn.substring(pkg.length() + 1);
        return Optional.of(new ComponentModel(pkg, binarySimple, type.getQualifiedName().toString(), main != null,
                kind, e.name(), e.patterns(), e.params(), e.load(), e.async(), e.dispatchers(), e.servletNames(),
                multipart == null ? null : AnnotationReader.multipartExpression(multipart, env),
                security == null ? null : AnnotationReader.securityExpression(security, env),
                roles == null ? List.of() : AnnotationReader.strings(AnnotationReader.values(roles, env).get(VALUE)),
                runAs == null ? null : (String) AnnotationReader.values(runAs, env).get(VALUE).getValue(),
                handlesTypes(type, initializer, env)));
    }

    /** The {@code @WebServlet}, else the {@code @WebFilter}, else the {@code @WebListener} of {@code type}; {@code null} without one. */
    private static AnnotationMirror mainAnnotation(TypeElement type) {
        AnnotationMirror webServlet = AnnotationReader.find(type, WEB_SERVLET);
        if (webServlet != null) {
            return webServlet;
        }
        AnnotationMirror webFilter = AnnotationReader.find(type, WEB_FILTER);
        return webFilter != null ? webFilter : AnnotationReader.find(type, WEB_LISTENER);
    }

    /**
     * The kind of {@code type}, whose component annotation is {@code main} ({@code null} without
     * one); {@code null} when it is not a web component, or when it misuses {@code main}: a compile
     * error is then reported.
     */
    private static Kind kind(TypeElement type, AnnotationMirror main, boolean initializer, ProcessingEnvironment env) {
        boolean servlet = is(type, SERVLET, env);
        boolean filter = is(type, FILTER, env);
        boolean listener = LISTENERS.stream().anyMatch(l -> is(type, l, env));
        if (main == null) {
            return plainKind(type, initializer, servlet || filter || listener);
        }
        String annotation = ((TypeElement) main.getAnnotationType().asElement()).getQualifiedName().toString();
        if (annotation.equals(WEB_SERVLET)) {
            if (!servlet) {
                report(env, type, main, "@WebServlet requires " + type.getQualifiedName() + " to implement " + SERVLET);
                return null;
            }
            return Kind.SERVLET;
        }
        if (annotation.equals(WEB_FILTER)) {
            if (!filter) {
                report(env, type, main, "@WebFilter requires " + type.getQualifiedName() + " to implement " + FILTER);
                return null;
            }
            return Kind.FILTER;
        }
        if (!listener) {
            report(env, type, main, "@WebListener requires " + type.getQualifiedName()
                    + " to implement one of " + String.join(", ", LISTENERS));
            return null;
        }
        return Kind.LISTENER;
    }

    /** The kind of the unannotated {@code type}: only a concrete class is a component. */
    private static Kind plainKind(TypeElement type, boolean initializer, boolean component) {
        if (type.getKind() != ElementKind.CLASS || type.getModifiers().contains(Modifier.ABSTRACT)) {
            return null;
        }
        if (initializer) {
            return Kind.INITIALIZER;
        }
        return component ? Kind.PLAIN : null;
    }

    /** The elements of a {@code @WebServlet} or {@code @WebFilter}, with the annotation defaults. */
    private record MainElements(String name, List<String> patterns, List<String[]> params, int load,
                                boolean async, List<String> dispatchers, List<String> servletNames) {
        /** Neither {@code @WebServlet} nor {@code @WebFilter}. */
        static MainElements none() {
            return new MainElements(null, new ArrayList<>(), new ArrayList<>(), Integer.MIN_VALUE, false,
                    new ArrayList<>(), new ArrayList<>());
        }
    }

    /**
     * Reads {@code main}, the {@code @WebServlet} ({@code kind == SERVLET}) or {@code @WebFilter} of
     * {@code type}; {@code null} when it is misused: a compile error is then reported.
     */
    private static MainElements mainElements(TypeElement type, AnnotationMirror main, Kind kind, String fqcn,
                                             ProcessingEnvironment env) {
        var explicit = AnnotationReader.explicit(main);
        boolean hasValue = !AnnotationReader.list(explicit.get(VALUE)).isEmpty();
        boolean hasPatterns = !AnnotationReader.list(explicit.get("urlPatterns")).isEmpty();
        if (hasValue && hasPatterns) {
            report(env, type, main, "foy: set either 'value' or 'urlPatterns' on "
                    + type.getQualifiedName() + ", not both");
            return null;
        }
        var values = AnnotationReader.values(main, env);
        boolean servlet = Kind.SERVLET.equals(kind);
        String declared = (String) values.get(servlet ? "name" : "filterName").getValue();
        String name = declared.isEmpty() ? fqcn : declared;
        List<String> patterns = new ArrayList<>(
                AnnotationReader.strings(hasValue ? values.get(VALUE) : values.get("urlPatterns")));
        boolean async = (Boolean) values.get("asyncSupported").getValue();
        List<String[]> params = new ArrayList<>();
        for (AnnotationValue o : AnnotationReader.list(values.get("initParams"))) {
            var p = AnnotationReader.values((AnnotationMirror) o.getValue(), env);
            params.add(new String[]{(String) p.get("name").getValue(), (String) p.get(VALUE).getValue()});
        }
        int load = servlet ? loadOnStartup(values) : Integer.MIN_VALUE;
        List<String> servletNames = servlet ? new ArrayList<>()
                : new ArrayList<>(AnnotationReader.strings(values.get("servletNames")));
        List<String> dispatchers = servlet ? new ArrayList<>() : dispatcherTypes(values);
        if (patterns.isEmpty() && servletNames.isEmpty()) {
            report(env, type, main, "foy: " + type.getQualifiedName() + " declares no 'urlPatterns'/'value'"
                    + (servlet ? "" : " and no 'servletNames'"));
            return null;
        }
        return new MainElements(name, patterns, params, load, async, dispatchers, servletNames);
    }

    /** A negative {@code loadOnStartup} means lazy: {@link Integer#MIN_VALUE}. */
    private static int loadOnStartup(Map<String, AnnotationValue> values) {
        int declaredLoad = (Integer) values.get("loadOnStartup").getValue();
        return declaredLoad < 0 ? Integer.MIN_VALUE : declaredLoad;
    }

    /** A filter's dispatcher types, each once; {@code REQUEST} when none is declared. */
    private static List<String> dispatcherTypes(Map<String, AnnotationValue> values) {
        var dispatchers = new LinkedHashSet<>(AnnotationReader.enumNames(values.get("dispatcherTypes")));
        if (dispatchers.isEmpty()) {
            dispatchers.add("REQUEST");
        }
        return new ArrayList<>(dispatchers);
    }

    /** The {@code @HandlesTypes} classes of any initializer, whatever its kind: a {@code @WebListener} may also be the jar's initializer. */
    private static List<String> handlesTypes(TypeElement type, boolean initializer, ProcessingEnvironment env) {
        AnnotationMirror handles = AnnotationReader.find(type, PKG + "annotation.HandlesTypes");
        if (handles == null || !initializer) {
            return List.of();
        }
        return AnnotationReader.binaryNames(AnnotationReader.values(handles, env).get(VALUE), env);
    }

    /** Reports {@code message} as a compile error on {@code type}'s annotation {@code at}. */
    private static void report(ProcessingEnvironment env, TypeElement type, AnnotationMirror at, String message) {
        env.getMessager().printMessage(Diagnostic.Kind.ERROR, message, type, at);
    }

    private static boolean is(TypeElement type, String qualifiedName, ProcessingEnvironment env) {
        TypeElement target = env.getElementUtils().getTypeElement(qualifiedName);
        var types = env.getTypeUtils();
        return target != null && types.isAssignable(types.erasure(type.asType()), types.erasure(target.asType()));
    }

    /**
     * Whether a source file can be generated for the class: concrete, accessible, top-level or
     * static nested, with a non-private no-arg constructor.
     *
     * @return {@code null} when generatable, otherwise the human-readable reason
     */
    static String notGeneratableReason(TypeElement type) {
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            return "abstract class";
        }
        if (type.getModifiers().contains(Modifier.PRIVATE)) {
            return "private class";
        }
        for (var enclosing = type.getEnclosingElement(); enclosing instanceof TypeElement outer;
                enclosing = enclosing.getEnclosingElement()) {
            if (outer.getModifiers().contains(Modifier.PRIVATE)) {
                return "private enclosing class";
            }
        }
        if (type.getEnclosingElement() instanceof TypeElement && !type.getModifiers().contains(Modifier.STATIC)) {
            return "inner (non-static) class";
        }
        boolean hasNoArg = false;
        for (ExecutableElement c : ElementFilter.constructorsIn(type.getEnclosedElements())) {
            if (c.getParameters().isEmpty()) {
                if (!c.getThrownTypes().isEmpty()) {
                    return "no-arg constructor declares checked exceptions";
                }
                hasNoArg = !c.getModifiers().contains(Modifier.PRIVATE);
            }
        }
        return hasNoArg ? null : "no accessible no-arg constructor";
    }
}
