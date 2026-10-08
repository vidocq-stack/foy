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
import java.util.LinkedHashSet;
import java.util.List;
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
        AnnotationMirror webServlet = AnnotationReader.find(type, WEB_SERVLET);
        AnnotationMirror webFilter = AnnotationReader.find(type, WEB_FILTER);
        AnnotationMirror webListener = AnnotationReader.find(type, WEB_LISTENER);
        boolean servlet = is(type, SERVLET, env);
        boolean filter = is(type, FILTER, env);
        boolean listener = LISTENERS.stream().anyMatch(l -> is(type, l, env));
        boolean initializer = is(type, INITIALIZER, env);
        Kind kind;
        AnnotationMirror main = webServlet != null ? webServlet : webFilter != null ? webFilter : webListener;
        if (webServlet != null) {
            if (!servlet) {
                return error(env, type, main, "@WebServlet requires " + type.getQualifiedName() + " to implement " + SERVLET);
            }
            kind = Kind.SERVLET;
        } else if (webFilter != null) {
            if (!filter) {
                return error(env, type, main, "@WebFilter requires " + type.getQualifiedName() + " to implement " + FILTER);
            }
            kind = Kind.FILTER;
        } else if (main != null) {
            if (!listener) {
                return error(env, type, main, "@WebListener requires " + type.getQualifiedName()
                        + " to implement one of " + String.join(", ", LISTENERS));
            }
            kind = Kind.LISTENER;
        } else if (type.getKind() != ElementKind.CLASS || type.getModifiers().contains(Modifier.ABSTRACT)) {
            return Optional.empty();
        } else if (initializer) {
            kind = Kind.INITIALIZER;
        } else if (servlet || filter || listener) {
            kind = Kind.PLAIN;
        } else {
            return Optional.empty();
        }

        String name = null;
        List<String> patterns = new ArrayList<>();
        List<String[]> params = new ArrayList<>();
        int load = Integer.MIN_VALUE;
        boolean async = false;
        var dispatchers = new LinkedHashSet<String>();
        List<String> servletNames = new ArrayList<>();
        String fqcn = env.getElementUtils().getBinaryName(type).toString();
        if (kind == Kind.SERVLET || kind == Kind.FILTER) {
            var explicit = AnnotationReader.explicit(main);
            boolean hasValue = !AnnotationReader.list(explicit.get("value")).isEmpty();
            boolean hasPatterns = !AnnotationReader.list(explicit.get("urlPatterns")).isEmpty();
            if (hasValue && hasPatterns) {
                return error(env, type, main, "foy: set either 'value' or 'urlPatterns' on "
                        + type.getQualifiedName() + ", not both");
            }
            var values = AnnotationReader.values(main, env);
            String declared = (String) values.get(kind == Kind.SERVLET ? "name" : "filterName").getValue();
            name = declared.isEmpty() ? fqcn : declared;
            patterns.addAll(AnnotationReader.strings(hasValue ? values.get("value") : values.get("urlPatterns")));
            async = (Boolean) values.get("asyncSupported").getValue();
            for (AnnotationValue o : AnnotationReader.list(values.get("initParams"))) {
                var p = AnnotationReader.values((AnnotationMirror) o.getValue(), env);
                params.add(new String[]{(String) p.get("name").getValue(), (String) p.get("value").getValue()});
            }
            if (kind == Kind.SERVLET) {
                int declaredLoad = (Integer) values.get("loadOnStartup").getValue();
                load = declaredLoad < 0 ? Integer.MIN_VALUE : declaredLoad;
            } else {
                servletNames.addAll(AnnotationReader.strings(values.get("servletNames")));
                dispatchers.addAll(AnnotationReader.enumNames(values.get("dispatcherTypes")));
                if (dispatchers.isEmpty()) {
                    dispatchers.add("REQUEST");
                }
            }
            if (patterns.isEmpty() && servletNames.isEmpty()) {
                return error(env, type, main, "foy: " + type.getQualifiedName() + " declares no 'urlPatterns'/'value'"
                        + (kind == Kind.FILTER ? " and no 'servletNames'" : ""));
            }
        }

        AnnotationMirror multipart = AnnotationReader.find(type, PKG + "annotation.MultipartConfig");
        AnnotationMirror security = AnnotationReader.find(type, PKG + "annotation.ServletSecurity");
        AnnotationMirror roles = AnnotationReader.find(type, "jakarta.annotation.security.DeclareRoles");
        AnnotationMirror runAs = AnnotationReader.find(type, "jakarta.annotation.security.RunAs");
        AnnotationMirror handles = AnnotationReader.find(type, PKG + "annotation.HandlesTypes");
        String pkg = env.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        String binarySimple = pkg.isEmpty() ? fqcn : fqcn.substring(pkg.length() + 1);
        return Optional.of(new ComponentModel(pkg, binarySimple, type.getQualifiedName().toString(), main != null,
                kind, name, patterns, params, load, async, new ArrayList<>(dispatchers), servletNames,
                multipart == null ? null : AnnotationReader.multipartExpression(multipart, env),
                security == null ? null : AnnotationReader.securityExpression(security, env),
                roles == null ? List.of() : AnnotationReader.strings(AnnotationReader.values(roles, env).get("value")),
                runAs == null ? null : (String) AnnotationReader.values(runAs, env).get("value").getValue(),
                handles == null || kind != Kind.INITIALIZER ? List.of()
                        : AnnotationReader.binaryNames(AnnotationReader.values(handles, env).get("value"), env)));
    }

    private static Optional<ComponentModel> error(ProcessingEnvironment env, TypeElement type,
                                                  AnnotationMirror at, String message) {
        env.getMessager().printMessage(Diagnostic.Kind.ERROR, message, type, at);
        return Optional.empty();
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
