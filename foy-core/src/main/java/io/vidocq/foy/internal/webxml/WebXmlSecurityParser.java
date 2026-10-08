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

import jakarta.servlet.SessionTrackingMode;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.vidocq.foy.internal.webxml.WebXmlParser.childrenByTag;
import static io.vidocq.foy.internal.webxml.WebXmlParser.firstText;
import static io.vidocq.foy.internal.webxml.WebXmlParser.parseInt;
import static io.vidocq.foy.internal.webxml.WebXmlParser.text;

/** Parsing of the {@code session-config} and security elements of a descriptor. */
final class WebXmlSecurityParser {

    private static final System.Logger LOG = System.getLogger(WebXmlSecurityParser.class.getName());

    private WebXmlSecurityParser() {}

    /** Cookie configuration of a {@code <session-config>}, or {@code null} when absent. */
    static WebAppDescriptor.CookieConfigDef parseCookieConfig(Element sessionConfig) {
        for (Element cc : childrenByTag(sessionConfig, "cookie-config")) {
            String maxAge = firstText(cc, "max-age");
            Map<String, String> attributes = new LinkedHashMap<>();
            for (Element a : childrenByTag(cc, "attribute")) {
                String n = firstText(a, "attribute-name");
                String v = firstText(a, "attribute-value");
                if (n != null) attributes.putIfAbsent(n, v == null ? "" : v);
            }
            return new WebAppDescriptor.CookieConfigDef(
                    firstText(cc, "name"), firstText(cc, "domain"), firstText(cc, "path"),
                    firstText(cc, "comment"), bool(cc, "http-only"), bool(cc, "secure"),
                    maxAge == null ? null : parseInt(maxAge, "<max-age> in <cookie-config>"),
                    attributes);
        }
        return null;
    }

    static Set<SessionTrackingMode> parseTrackingModes(Element sessionConfig) {
        Set<SessionTrackingMode> modes = EnumSet.noneOf(SessionTrackingMode.class);
        for (Element t : childrenByTag(sessionConfig, "tracking-mode")) {
            String v = text(t);
            try {
                modes.add(SessionTrackingMode.valueOf(v));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("invalid <tracking-mode> value '" + v
                        + "' in <session-config>", ex);
            }
        }
        return modes;
    }

    static SecurityDefs.SecurityConstraintDef parseSecurityConstraint(Element e) {
        var collections = new ArrayList<SecurityDefs.WebResourceCollectionDef>();
        for (Element c : childrenByTag(e, "web-resource-collection")) {
            var col = new SecurityDefs.WebResourceCollectionDef(
                    firstText(c, "web-resource-name"), texts(c, "url-pattern"),
                    texts(c, "http-method"), texts(c, "http-method-omission"));
            if (!col.httpMethods().isEmpty() && !col.httpMethodOmissions().isEmpty()) {
                LOG.log(System.Logger.Level.WARNING, "<web-resource-collection> '" + col.name()
                        + "' mixes <http-method> and <http-method-omission>; both are kept");
            }
            collections.add(col);
        }
        List<String> roles = null;
        for (Element ac : childrenByTag(e, "auth-constraint")) {
            if (roles == null) roles = new ArrayList<>();
            roles.addAll(texts(ac, "role-name"));
        }
        String guarantee = null;
        for (Element udc : childrenByTag(e, "user-data-constraint")) {
            guarantee = firstText(udc, "transport-guarantee");
        }
        return new SecurityDefs.SecurityConstraintDef(collections, roles, guarantee);
    }

    static SecurityDefs.LoginConfigDef parseLoginConfig(Element e) {
        String page = null;
        String error = null;
        for (Element f : childrenByTag(e, "form-login-config")) {
            page = firstText(f, "form-login-page");
            error = firstText(f, "form-error-page");
        }
        return new SecurityDefs.LoginConfigDef(firstText(e, "auth-method"),
                firstText(e, "realm-name"), page, error);
    }

    /** {@code null} when the child is absent. */
    private static Boolean bool(Element parent, String tag) {
        String t = firstText(parent, tag);
        return WebXmlParser.xsdBoolean(t, "<" + tag + "> in <" + parent.getTagName() + ">");
    }

    private static List<String> texts(Element parent, String tag) {
        var out = new ArrayList<String>();
        for (Element c : childrenByTag(parent, tag)) out.add(text(c));
        return out;
    }
}
