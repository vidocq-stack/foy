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

import io.vidocq.foy.internal.boot.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JspConfigurationTest {
    private static jakarta.servlet.descriptor.JspConfigDescriptor config(String body) throws Exception {
        return WebXmlParser.parse(new ByteArrayInputStream(("<web-app><jsp-config>" + body
                + "</jsp-config></web-app>").getBytes(StandardCharsets.UTF_8))).jspConfigDescriptor();
    }

    @Test
    void fragmentGroupsAreAdditiveAndRootTaglibsOverrideFragmentLocations() throws Exception {
        var root = config("<taglib><taglib-uri>urn:test</taglib-uri><taglib-location>/root.tld</taglib-location></taglib>"
                + "<jsp-property-group><url-pattern>/root/*</url-pattern></jsp-property-group>");
        var fragment = config("<taglib><taglib-uri>urn:test</taglib-uri><taglib-location>/fragment.tld</taglib-location></taglib>"
                + "<jsp-property-group><url-pattern>*.jsp</url-pattern></jsp-property-group>");
        var merged = JspDescriptors.merge(root, List.of(fragment));
        assertEquals("/root.tld", merged.getTaglibs().iterator().next().getTaglibLocation());
        assertEquals(List.of("/root/*", "*.jsp"), merged.getJspPropertyGroups().stream()
                .flatMap(group -> group.getUrlPatterns().stream()).toList());
        assertThrows(UnsupportedOperationException.class, () -> merged.getTaglibs().clear());
        var other = config("<taglib><taglib-uri>urn:test</taglib-uri><taglib-location>/other.tld</taglib-location></taglib>");
        assertThrows(jakarta.servlet.ServletException.class, () -> JspDescriptors.merge(null, List.of(fragment, other)));
    }

    @Test
    void duplicateTaglibFieldsAreRejectedInsteadOfSilentlyOverwriting() {
        var error = assertThrows(java.io.IOException.class, () -> config("<taglib><taglib-uri>urn:first</taglib-uri>"
                + "<taglib-uri>urn:second</taglib-uri><taglib-location>/test.tld</taglib-location></taglib>"));
        assertTrue(error.getCause().getMessage().contains("Duplicate taglib field"));
    }

    @Test
    void groupsAndTaglibsReachTheServletContextAsImmutableDescriptors() throws Exception {
        var xml = WebXmlParser.parse(new ByteArrayInputStream(("""
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
                  <jsp-config>
                    <taglib><taglib-uri>urn:test</taglib-uri><taglib-location>/WEB-INF/test.tld</taglib-location></taglib>
                    <jsp-property-group><url-pattern>*.jsp</url-pattern><page-encoding>UTF-8</page-encoding>
                      <error-on-el-not-found>true</error-on-el-not-found><include-prelude>/head.jspf</include-prelude>
                    </jsp-property-group>
                  </jsp-config>
                </web-app>
                """).getBytes(StandardCharsets.UTF_8)));
        var model = WebAppModel.builder("/pages");
        var options = DeployOptions.defaults(getClass().getClassLoader());
        DescriptorMerger.merge(xml, DescriptorMerger.AnnotatedComponents.none(), options.componentFactory(), model);
        try (var deployment = WebAppDeployer.deploy(model.build(), options)) {
            var config = deployment.servletContext().getJspConfigDescriptor();
            assertNotNull(config);
            assertEquals("urn:test", config.getTaglibs().iterator().next().getTaglibURI());
            var group = config.getJspPropertyGroups().iterator().next();
            assertEquals("UTF-8", group.getPageEncoding());
            assertEquals("true", group.getErrorOnELNotFound());
            assertEquals(List.of("/head.jspf"), List.copyOf(group.getIncludePreludes()));
            assertThrows(UnsupportedOperationException.class, () -> group.getUrlPatterns().clear());
        }
    }

    @Test
    void absentConfigurationRemainsNull() throws Exception {
        try (var deployment = WebAppDeployer.deploy(WebAppModel.builder("/pages").build(), DeployOptions.defaults(getClass().getClassLoader()))) {
            assertNull(deployment.servletContext().getJspConfigDescriptor());
        }
    }
}
