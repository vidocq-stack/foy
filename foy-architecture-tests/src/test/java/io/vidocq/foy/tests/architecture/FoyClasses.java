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

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/** The classes the architecture rules are applied to, imported once per test run. */
final class FoyClasses {

    /** The root package of each shipped module: foy-api, foy-processor, foy-core, foy-cdi-vauban, foy-chappe. */
    private static final String[] SHIPPED_PACKAGES = {
        "io.vidocq.foy.spi",
        "io.vidocq.foy.processor",
        "io.vidocq.foy.internal",
        "io.vidocq.foy.cdi.vauban",
        "io.vidocq.foy.chappe"
    };

    private static final ImportOption NO_TESTS = new ImportOption.DoNotIncludeTests();

    /** The classes Foy ships, test classes left out. */
    static final JavaClasses SHIPPED =
            new ClassFileImporter().withImportOption(NO_TESTS).importPackages(SHIPPED_PACKAGES);

    /**
     * The shipped classes plus the fixtures of this module's {@code archfixture} packages, each of which sits in
     * a shipped package and breaks one rule there.
     */
    static final JavaClasses SHIPPED_AND_FIXTURES = new ClassFileImporter()
            .withImportOption(location -> NO_TESTS.includes(location) || location.contains("/archfixture/"))
            .importPackages(SHIPPED_PACKAGES);

    private FoyClasses() {
        // Constants only.
    }
}
