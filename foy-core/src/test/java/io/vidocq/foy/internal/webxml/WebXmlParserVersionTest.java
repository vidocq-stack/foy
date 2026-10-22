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
package io.vidocq.foy.internal.webxml;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How the {@code version} attribute decides that a descriptor is older than 2.4, observed through
 * the lenient url-pattern (a leading {@code '/'} added) and the implied metadata-complete: the
 * version is split around its dots, trailing empty parts dropped, and a malformed number means a
 * current descriptor.
 */
class WebXmlParserVersionTest {

    @ParameterizedTest(name = "version \"{0}\"")
    @CsvSource(delimiter = '|', value = {
            "2.3    | /a | true",
            "2.2    | /a | true",
            "1.0    | /a | true",
            "2      | /a | true",
            "2.     | /a | true",
            "2..    | /a | true",
            "2.3.1  | /a | true",
            "2.3.   | /a | true",
            "2.4    | a  | true",
            "3.0    | a  | false",
            "2.x    | a  | false",
            "x.1    | a  | false",
            ".5     | a  | false",
            "2..3   | a  | false",
            "6.1    | a  | false"
    })
    void aVersionBelow24MakesPatternsLenient(String version, String pattern, boolean metadataComplete)
            throws IOException {
        String xml = "<web-app version=\"" + version + "\"><servlet><servlet-name>S</servlet-name>"
                + "<servlet-class>x.S</servlet-class></servlet><servlet-mapping><servlet-name>S</servlet-name>"
                + "<url-pattern>a</url-pattern></servlet-mapping></web-app>";
        var d = WebXmlParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertEquals(pattern, d.servletMappings().getFirst().urlPattern());
        assertEquals(metadataComplete, d.metadataComplete());
    }
}
