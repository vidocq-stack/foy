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
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads annotation values at compile time and renders the Java expressions that rebuild the
 * servlet-API metadata objects ({@code MultipartConfigElement}, {@code ServletSecurityElement}).
 */
final class AnnotationReader {

    private static final String SERVLET_PKG = "jakarta.servlet.";
    private static final String SECURITY = SERVLET_PKG + "annotation.ServletSecurity";

    private AnnotationReader() {
    }

    /** Finds an annotation on the element by qualified name, or {@code null}. */
    static AnnotationMirror find(Element element, String annotationName) {
        for (AnnotationMirror m : element.getAnnotationMirrors()) {
            if (((TypeElement) m.getAnnotationType().asElement()).getQualifiedName().contentEquals(annotationName)) {
                return m;
            }
        }
        return null;
    }

    /** The annotation values by member simple name, defaults included. */
    static Map<String, AnnotationValue> values(AnnotationMirror m, ProcessingEnvironment env) {
        return byName(env.getElementUtils().getElementValuesWithDefaults(m));
    }

    /** The annotation values by member simple name, only those written explicitly. */
    static Map<String, AnnotationValue> explicit(AnnotationMirror m) {
        return byName(m.getElementValues());
    }

    private static Map<String, AnnotationValue> byName(Map<? extends ExecutableElement, ? extends AnnotationValue> in) {
        var out = new LinkedHashMap<String, AnnotationValue>();
        in.forEach((k, v) -> out.put(k.getSimpleName().toString(), v));
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<? extends AnnotationValue> list(AnnotationValue v) {
        return v == null ? List.of() : (List<? extends AnnotationValue>) v.getValue();
    }

    static List<String> strings(AnnotationValue v) {
        List<String> out = new ArrayList<>();
        for (AnnotationValue e : list(v)) {
            out.add((String) e.getValue());
        }
        return out;
    }

    /** The constant names of an enum-array member. */
    static List<String> enumNames(AnnotationValue v) {
        List<String> out = new ArrayList<>();
        for (AnnotationValue e : list(v)) {
            out.add(enumName(e));
        }
        return out;
    }

    static String enumName(AnnotationValue v) {
        return ((VariableElement) v.getValue()).getSimpleName().toString();
    }

    /** The binary names of the classes of a {@code Class[]} member. */
    static List<String> binaryNames(AnnotationValue v, ProcessingEnvironment env) {
        List<String> out = new ArrayList<>();
        for (AnnotationValue e : list(v)) {
            TypeMirror t = (TypeMirror) e.getValue();
            out.add(t instanceof DeclaredType d
                    ? env.getElementUtils().getBinaryName((TypeElement) d.asElement()).toString()
                    : t.toString());
        }
        return out;
    }

    /** The Java expression building the {@code MultipartConfigElement} of the annotation. */
    static String multipartExpression(AnnotationMirror m, ProcessingEnvironment env) {
        var v = values(m, env);
        return "new " + SERVLET_PKG + "MultipartConfigElement(" + ComponentSourceWriter.literal((String) v.get("location").getValue())
                + ", " + v.get("maxFileSize").getValue() + "L, " + v.get("maxRequestSize").getValue() + "L, "
                + v.get("fileSizeThreshold").getValue() + ")";
    }

    /** The Java expression building the {@code ServletSecurityElement} of the annotation. */
    static String securityExpression(AnnotationMirror m, ProcessingEnvironment env) {
        var v = values(m, env);
        var sb = new StringBuilder("new " + SERVLET_PKG + "ServletSecurityElement(")
                .append(constraint((AnnotationMirror) v.get("value").getValue(), "value", env))
                .append(", java.util.List.of(");
        boolean first = true;
        for (AnnotationValue c : list(v.get("httpMethodConstraints"))) {
            var mc = (AnnotationMirror) c.getValue();
            sb.append(first ? "" : ", ").append("new " + SERVLET_PKG + "HttpMethodConstraintElement(")
                    .append(ComponentSourceWriter.literal((String) values(mc, env).get("value").getValue()))
                    .append(", ").append(constraint(mc, "emptyRoleSemantic", env)).append(")");
            first = false;
        }
        return sb.append("))").toString();
    }

    /** Rebuilds an {@code HttpConstraintElement}; {@code @HttpConstraint} names its semantic {@code value}. */
    private static String constraint(AnnotationMirror m, String semanticKey, ProcessingEnvironment env) {
        var v = values(m, env);
        var sb = new StringBuilder("new " + SERVLET_PKG + "HttpConstraintElement(")
                .append(SECURITY).append(".EmptyRoleSemantic.").append(enumName(v.get(semanticKey)))
                .append(", ").append(SECURITY).append(".TransportGuarantee.").append(enumName(v.get("transportGuarantee")));
        for (String role : strings(v.get("rolesAllowed"))) {
            sb.append(", ").append(ComponentSourceWriter.literal(role));
        }
        return sb.append(")").toString();
    }
}
