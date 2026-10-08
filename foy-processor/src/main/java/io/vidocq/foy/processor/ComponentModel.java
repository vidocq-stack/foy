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
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Compile-time model of one web component, extracted from a {@link TypeElement}.
 *
 * <p>Only {@code @WebServlet} is understood for now; the record is shaped so that filters,
 * listeners, initializers and the remaining descriptor fields slot in without changing the writer API.
 *
 * @param packageName     the package of the component, empty for the unnamed package
 * @param binarySimpleName the binary name minus the package ({@code Outer$Inner})
 * @param typeFqcn        the canonical name of the component class
 * @param kind            the component kind
 * @param name            the component name, or {@code null}
 * @param urlPatterns     the URL patterns
 * @param initParams      alternating init-parameter names and values
 * @param loadOnStartup   the load-on-startup order, {@link Integer#MIN_VALUE} when absent
 * @param asyncSupported  whether asynchronous processing is supported
 * @param dispatcherTypes the dispatcher type constant names
 * @param servletNames    the servlet names a filter applies to
 */
record ComponentModel(String packageName,
                      String binarySimpleName,
                      String typeFqcn,
                      Kind kind,
                      String name,
                      List<String> urlPatterns,
                      List<String[]> initParams,
                      int loadOnStartup,
                      boolean asyncSupported,
                      List<String> dispatcherTypes,
                      List<String> servletNames) {

    /** Component kinds, mirroring {@code WebComponentDescriptor.Kind} by constant name. */
    enum Kind { SERVLET, FILTER, LISTENER, INITIALIZER, PLAIN }

    static final String WEB_SERVLET = "jakarta.servlet.annotation.WebServlet";
    static final String SERVLET = "jakarta.servlet.Servlet";

    /**
     * Extracts a model from an annotated class.
     *
     * @return the model, or empty when the class carries no supported annotation
     *         or the servlet API is not on the compile path
     */
    static Optional<ComponentModel> from(TypeElement type, ProcessingEnvironment env) {
        AnnotationMirror webServlet = find(type, WEB_SERVLET);
        if (webServlet == null) {
            return Optional.empty();
        }
        TypeElement servlet = env.getElementUtils().getTypeElement(SERVLET);
        if (servlet == null || !env.getTypeUtils().isAssignable(
                env.getTypeUtils().erasure(type.asType()), env.getTypeUtils().erasure(servlet.asType()))) {
            return Optional.empty();
        }
        var values = env.getElementUtils().getElementValuesWithDefaults(webServlet);
        List<String> patterns = new ArrayList<>();
        String name = "";
        int load = -1;
        boolean async = false;
        List<String[]> params = new ArrayList<>();
        for (var e : values.entrySet()) {
            String key = e.getKey().getSimpleName().toString();
            AnnotationValue v = e.getValue();
            switch (key) {
                case "name" -> name = (String) v.getValue();
                case "value", "urlPatterns" -> patterns.addAll(strings(v));
                case "loadOnStartup" -> load = (Integer) v.getValue();
                case "asyncSupported" -> async = (Boolean) v.getValue();
                case "initParams" -> {
                    for (Object o : list(v)) {
                        var initParam = env.getElementUtils()
                                .getElementValuesWithDefaults((AnnotationMirror) ((AnnotationValue) o).getValue());
                        String n = "";
                        String val = "";
                        for (var p : initParam.entrySet()) {
                            switch (p.getKey().getSimpleName().toString()) {
                                case "name" -> n = (String) p.getValue().getValue();
                                case "value" -> val = (String) p.getValue().getValue();
                                default -> { }
                            }
                        }
                        params.add(new String[]{n, val});
                    }
                }
                default -> { }
            }
        }
        String fqcn = env.getElementUtils().getBinaryName(type).toString();
        String pkg = env.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        String binarySimple = pkg.isEmpty() ? fqcn : fqcn.substring(pkg.length() + 1);
        String canonical = type.getQualifiedName().toString();
        return Optional.of(new ComponentModel(pkg, binarySimple, canonical, Kind.SERVLET,
                name.isEmpty() ? canonical : name, patterns, params,
                load < 0 ? Integer.MIN_VALUE : load, async, List.of(), List.of()));
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
                hasNoArg = !c.getModifiers().contains(Modifier.PRIVATE);
            }
        }
        return hasNoArg ? null : "no accessible no-arg constructor";
    }

    /** Finds an annotation on the element by qualified name. */
    static AnnotationMirror find(TypeElement type, String annotationName) {
        for (AnnotationMirror m : type.getAnnotationMirrors()) {
            if (((TypeElement) m.getAnnotationType().asElement()).getQualifiedName().contentEquals(annotationName)) {
                return m;
            }
        }
        return null;
    }

    /** Whether the class carries any {@code jakarta.servlet.annotation.Web*} annotation. */
    static boolean hasWebAnnotation(TypeElement type) {
        for (AnnotationMirror m : type.getAnnotationMirrors()) {
            String n = ((TypeElement) m.getAnnotationType().asElement()).getQualifiedName().toString();
            if (n.startsWith("jakarta.servlet.annotation.Web") && !n.equals("jakarta.servlet.annotation.WebInitParam")) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<? extends AnnotationValue> list(AnnotationValue v) {
        return (List<? extends AnnotationValue>) v.getValue();
    }

    private static List<String> strings(AnnotationValue v) {
        List<String> out = new ArrayList<>();
        for (AnnotationValue e : list(v)) {
            out.add((String) e.getValue());
        }
        return out;
    }
}
