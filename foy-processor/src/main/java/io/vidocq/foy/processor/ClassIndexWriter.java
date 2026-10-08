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
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Collects every compiled type with its transitive supertypes and its {@code RUNTIME}-retained
 * annotations (on the type, its fields, methods and constructors), and
 * writes {@code META-INF/foy/class-index.list} (consumed by the runtime {@code @HandlesTypes}
 * resolver). Line format:
 * {@code <binary name>|<supertype binary names>|<annotation binary names>}, sorted by binary name.
 * Every column uses binary names (nested types as {@code Outer$Inner}). Annotations retained
 * {@code CLASS} or {@code SOURCE} are left out, matching what the runtime class-bytes scanner reads.
 */
final class ClassIndexWriter {

    static final String RESOURCE = "META-INF/foy/class-index.list";
    static final String HEADER = "# foy class index v1";
    private static final String COMPANION_SUFFIX = "$$FoyComponent";

    private final ProcessingEnvironment env;
    private final TreeMap<String, String> lines = new TreeMap<>();
    private TypeElement origin;

    ClassIndexWriter(ProcessingEnvironment env) {
        this.env = env;
    }

    /** Records one type; Foy's own generated companions are skipped. */
    void add(TypeElement type) {
        String binary = env.getElementUtils().getBinaryName(type).toString();
        if (binary.endsWith(COMPANION_SUFFIX)) {
            return;
        }
        var supers = new TreeSet<String>();
        var seen = new TreeSet<String>();
        var queue = new ArrayDeque<TypeMirror>();
        queue.add(type.asType());
        while (!queue.isEmpty()) {
            for (TypeMirror s : env.getTypeUtils().directSupertypes(queue.poll())) {
                if (s instanceof DeclaredType d && d.asElement() instanceof TypeElement te) {
                    String name = env.getElementUtils().getBinaryName(te).toString();
                    if (seen.add(name)) {
                        supers.add(name);
                        queue.add(env.getTypeUtils().erasure(s));
                    }
                }
            }
        }
        var annotations = new TreeSet<String>();
        collectRuntimeAnnotations(type, annotations);
        for (Element member : type.getEnclosedElements()) {
            switch (member.getKind()) {
                case FIELD, ENUM_CONSTANT, METHOD, CONSTRUCTOR -> collectRuntimeAnnotations(member, annotations);
                default -> { }
            }
        }
        lines.put(binary, binary + "|" + String.join(",", supers) + "|" + String.join(",", annotations));
        if (origin == null) {
            origin = type;
        }
    }

    /** Adds the binary names of the {@code RUNTIME}-retained annotations on {@code element}. */
    private void collectRuntimeAnnotations(Element element, TreeSet<String> into) {
        for (AnnotationMirror m : element.getAnnotationMirrors()) {
            if (m.getAnnotationType().asElement() instanceof TypeElement te && isRuntimeRetained(te)) {
                into.add(env.getElementUtils().getBinaryName(te).toString());
            }
        }
    }

    /** {@code true} when the annotation type is {@code @Retention(RUNTIME)}; the default, CLASS, is not. */
    private static boolean isRuntimeRetained(TypeElement annotationType) {
        for (AnnotationMirror m : annotationType.getAnnotationMirrors()) {
            if (m.getAnnotationType().asElement() instanceof TypeElement te
                    && te.getQualifiedName().contentEquals("java.lang.annotation.Retention")) {
                for (var value : m.getElementValues().values()) {
                    if (value.getValue() instanceof VariableElement constant) {
                        return constant.getSimpleName().contentEquals("RUNTIME");
                    }
                }
            }
        }
        return false;
    }

    /** Writes the index; does nothing when no type was seen. */
    void write() throws IOException {
        if (lines.isEmpty()) {
            return;
        }
        FileObject f = env.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", RESOURCE, origin);
        try (Writer w = f.openWriter()) {
            w.write(HEADER + "\n");
            for (String line : lines.values()) {
                w.write(line + "\n");
            }
        }
    }
}
