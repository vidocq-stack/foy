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

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Stereotype;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.servlet.annotation.WebServlet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link FoyWebExtension#defaultScope} against a hand-made language model, for the cases vauban's
 * processor cannot produce in a real compilation: it does not index a class whose only CDI
 * annotation is an application stereotype, and it applies no stereotype scope to the classes it
 * does index, so a stereotyped web component cannot be observed end to end.
 */
@DisplayName("FoyWebExtension.defaultScope: when @Dependent is added")
class FoyWebExtensionScopeTest {

    /** An annotation whose declaration vauban's model cannot resolve. */
    private static AnnotationInfo unresolvable(String name) {
        return annotation(name, null);
    }

    private static AnnotationInfo annotation(String name, ClassInfo declaration) {
        return proxy(AnnotationInfo.class, (method, args) -> switch (method) {
            case "name" -> name;
            case "declaration" -> {
                if (declaration == null) throw new IllegalArgumentException("Class not found in index: " + name);
                yield declaration;
            }
            case "member" -> null;
            default -> throw new UnsupportedOperationException(method);
        });
    }

    /** A class (or annotation type) carrying {@code annotations}. */
    private static ClassInfo classInfo(String name, Set<String> metaAnnotations, AnnotationInfo... annotations) {
        return proxy(ClassInfo.class, (method, args) -> switch (method) {
            case "name" -> name;
            case "annotations" -> List.of(annotations);
            case "isPlainClass" -> true;
            case "isAbstract" -> false;
            case "hasAnnotation" -> metaAnnotations.contains(((Class<?>) args[0]).getName())
                    || List.of(annotations).stream().anyMatch(a -> a.name().equals(((Class<?>) args[0]).getName()));
            case "annotation" -> List.of(annotations).stream()
                    .filter(a -> a.name().equals(((Class<?>) args[0]).getName())).findFirst().orElse(null);
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private static List<Class<?>> added(ClassInfo cls) {
        var added = new ArrayList<Class<?>>();
        ClassConfig config = proxy(ClassConfig.class, (method, args) -> switch (method) {
            case "info" -> cls;
            case "addAnnotation" -> {
                added.add((Class<?>) args[0]);
                yield null;
            }
            default -> throw new UnsupportedOperationException(method);
        });
        new FoyWebExtension().defaultScope(config);
        return added;
    }

    @FunctionalInterface
    private interface Handler {
        Object handle(String method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> switch (m.getName()) {
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            case "toString" -> type.getSimpleName() + "@fake";
            default -> handler.handle(m.getName(), a);
        }));
    }

    private static final AnnotationInfo WEB_SERVLET = unresolvable(WebServlet.class.getName());

    @Test
    @DisplayName("only API annotations, even unresolvable ones: @Dependent is added")
    void unscopedComponentGetsDependent() {
        assertEquals(List.of(Dependent.class), added(classInfo("app.Plain", Set.of(), WEB_SERVLET)));
    }

    @Test
    @DisplayName("an annotation type the model cannot resolve may be a library stereotype or scope: nothing is added")
    void unresolvableApplicationAnnotationBlocksDependent() {
        assertEquals(List.of(), added(classInfo("app.Stereo", Set.of(), unresolvable("lib.Managed"), WEB_SERVLET)));
    }

    @Test
    @DisplayName("a resolvable stereotype: nothing is added")
    void resolvableStereotypeBlocksDependent() {
        var stereotype = classInfo("lib.Managed", Set.of(Stereotype.class.getName()));
        assertEquals(List.of(), added(classInfo("app.Stereo", Set.of(), annotation("lib.Managed", stereotype), WEB_SERVLET)));
    }

    @Test
    @DisplayName("a resolvable application annotation that is no scope nor stereotype: @Dependent is added")
    void resolvablePlainAnnotationDoesNotBlockDependent() {
        var marker = classInfo("lib.Marker", Set.of());
        assertEquals(List.of(Dependent.class),
                added(classInfo("app.Marked", Set.of(), annotation("lib.Marker", marker), WEB_SERVLET)));
    }

    @Test
    @DisplayName("the built-in @Model stereotype is matched by name: nothing is added")
    void builtInModelBlocksDependent() {
        assertEquals(List.of(), added(classInfo("app.M", Set.of(),
                unresolvable(jakarta.enterprise.inject.Model.class.getName()), WEB_SERVLET)));
    }
}
