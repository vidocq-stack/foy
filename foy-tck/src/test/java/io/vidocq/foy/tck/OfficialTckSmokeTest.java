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
package io.vidocq.foy.tck;

import org.junit.jupiter.api.Disabled;

/**
 * Unique hooking point to validate that the Arquillian adapter boots correctly
 * on an official TCK test. Disabled by default because it depends on the {@code tck-official} profile.
 *
 * <p>Activate via: {@code mvn -Ptck-official -Dtest.official.tck=true -Dtest=OfficialTckSmokeTest verify}.
 * Without the profile, the TCK classes are not on the classpath and compilation skips.</p>
 */
@Disabled("Activation manuelle — voir README M3 TCK officiel")
public class OfficialTckSmokeTest {
    // Les tests sont les classes *Tests du jar jakarta.tck:servlet-tck-runtime.
    // Surefire les découvre automatiquement avec <includes>**/*Tests.class</includes>.
    // Ce fichier sert de sentinelle pour documenter l'entrée.
}
