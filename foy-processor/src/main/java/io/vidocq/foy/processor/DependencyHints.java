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

import java.util.List;

/**
 * What {@code vidocq:generate} needs to know to process a dependency jar with
 * {@link FoyWebComponentProcessor}. The values live here, in exactly one place, and the Foy codegen
 * bundle reads them.
 */
public final class DependencyHints {

    /**
     * Resource paths whose presence in a dependency jar suggests web components. {@code vidocq:generate}
     * selects a jar for processing when it contains any of them.
     */
    public static final List<String> RESOURCE_HINTS = List.of(
            "META-INF/services/jakarta.servlet.ServletContainerInitializer",
            "META-INF/web-fragment.xml");

    /**
     * Byte sequences, as internal class names, searched for in the class files of a dependency jar.
     * {@code "jakarta/servlet/annotation/Web"} matches {@code WebServlet}, {@code WebFilter},
     * {@code WebListener} and {@code WebInitParam}; the second entry matches {@code HandlesTypes}.
     * A jar whose classes contain any of them is selected for processing.
     */
    public static final List<String> CLASS_BYTE_HINTS = List.of(
            "jakarta/servlet/annotation/Web",
            "jakarta/servlet/annotation/HandlesTypes");

    /**
     * Resource the processor writes in its final round whenever the processor processed at least one type.
     * Its content is {@code key=value} lines, so that a caller can extend it later:
     * <pre>
     * processor=foy-processor
     * version=&lt;version or unknown&gt;
     * </pre>
     * Under {@code --processor-module-path} the version reads {@code unknown}, because a named module's
     * {@code Package} has no implementation version. A jar that
     * already carries it was processed, at its own build or by an earlier {@code vidocq:generate}, and
     * is skipped, so a Foy-built library never gets a second set of components.
     */
    public static final String MARKER = "META-INF/io.vidocq.foy/generated";

    private DependencyHints() {
    }
}
