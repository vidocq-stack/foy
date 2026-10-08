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

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Generates one {@code X$$FoyComponent} class per annotated web component, plus the
 * {@code META-INF/services/io.vidocq.foy.spi.gen.WebComponent} registration file.
 */
@SupportedAnnotationTypes("*")
public final class FoyWebComponentProcessor extends AbstractProcessor {

    private static final String SERVICE = "io.vidocq.foy.spi.gen.WebComponent";

    private final Set<String> generated = new LinkedHashSet<>();
    private Element anyOrigin;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            finish();
            return false;
        }
        for (Element root : roundEnv.getRootElements()) {
            visit(root);
        }
        return false;
    }

    private void visit(Element element) {
        if (!(element instanceof TypeElement type)) {
            return;
        }
        var model = ComponentModel.from(type, processingEnv);
        if (model.isPresent()) {
            String reason = ComponentModel.notGeneratableReason(type);
            if (reason == null) {
                try {
                    generated.add(ComponentSourceWriter.write(model.get(), processingEnv.getFiler(), type));
                    anyOrigin = type;
                } catch (IOException e) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "foy: cannot generate component for " + model.get().typeFqcn() + ": " + e, type);
                }
            } else {
                note(type, reason);
            }
        } else if (ComponentModel.hasWebAnnotation(type)) {
            note(type, "not a supported web component");
        }
        for (Element member : type.getEnclosedElements()) {
            visit(member);
        }
    }

    private void note(TypeElement type, String reason) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "foy: no generated component for " + type.getQualifiedName() + " (" + reason
                        + "); it will be resolved at runtime");
    }

    private void finish() {
        if (generated.isEmpty()) {
            return;
        }
        try {
            FileObject f = processingEnv.getFiler()
                    .createResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/services/" + SERVICE, anyOrigin);
            try (Writer w = f.openWriter()) {
                for (String g : generated) {
                    w.write(g + "\n");
                }
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "foy: cannot write the services file: " + e);
        }
        if (anyOrigin != null && !processingEnv.getElementUtils().getModuleOf(anyOrigin).isUnnamed()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "foy: add to module-info: provides " + SERVICE + " with " + String.join(", ", generated) + ";");
        }
    }
}
