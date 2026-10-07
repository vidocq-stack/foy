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

import jakarta.servlet.DispatcherType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal parser of descriptor {@code web.xml} according to Servlet 6.1 §14.
 *
 * <p>Supported elements: {@code context-param}, {@code servlet},
 * {@code servlet-mapping}, {@code filter}, {@code filter-mapping}, {@code listener},
 * {@code error-page}, {@code session-config/session-timeout}.</p>
 */
public final class WebXmlParser {

    private WebXmlParser() {}

    public static WebAppDescriptor parse(InputStream in) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(in);
            doc.getDocumentElement().normalize();
            return parse(doc.getDocumentElement());
        } catch (Exception e) {
            throw new IOException("invalid web.xml", e);
        }
    }

    private static WebAppDescriptor parse(Element root) {
        Map<String, String> contextParams = new LinkedHashMap<>();
        var servlets = new ArrayList<WebAppDescriptor.ServletDef>();
        var servletMappings = new ArrayList<WebAppDescriptor.ServletMappingDef>();
        var filters = new ArrayList<WebAppDescriptor.FilterDef>();
        var filterMappings = new ArrayList<WebAppDescriptor.FilterMappingDef>();
        var listenerClasses = new ArrayList<String>();
        var errorPages = new ArrayList<WebAppDescriptor.ErrorPageDef>();
        int sessionTimeoutMinutes = -1;
        var localeEncodingMappings = new LinkedHashMap<String, String>();
        String displayName = null;

        for (Element e : children(root)) {
            switch (e.getTagName()) {
                case "display-name" -> displayName = text(e);
                case "context-param" -> {
                    String name = firstText(e, "param-name");
                    String value = firstText(e, "param-value");
                    if (name != null) contextParams.put(name, value == null ? "" : value);
                }
                case "servlet" -> servlets.add(parseServlet(e));
                case "servlet-mapping" -> {
                    String sname = firstText(e, "servlet-name");
                    for (Element url : childrenByTag(e, "url-pattern")) {
                        servletMappings.add(new WebAppDescriptor.ServletMappingDef(sname, text(url)));
                    }
                }
                case "filter" -> filters.add(parseFilter(e));
                case "filter-mapping" -> filterMappings.addAll(parseFilterMapping(e));
                case "listener" -> {
                    String cls = firstText(e, "listener-class");
                    if (cls != null) listenerClasses.add(cls);
                }
                case "error-page" -> errorPages.add(parseErrorPage(e));
                case "session-config" -> {
                    String t = firstText(e, "session-timeout");
                    if (t != null) sessionTimeoutMinutes = Integer.parseInt(t.trim());
                }
                case "locale-encoding-mapping-list" -> {
                    for (Element m : childrenByTag(e, "locale-encoding-mapping")) {
                        String loc = firstText(m, "locale");
                        String enc = firstText(m, "encoding");
                        if (loc != null && enc != null) localeEncodingMappings.put(loc, enc);
                    }
                }
                default -> { /* ignore unrecognized elements */ }
            }
        }
        return new WebAppDescriptor(contextParams, servlets, servletMappings, filters,
                filterMappings, listenerClasses, errorPages, sessionTimeoutMinutes,
                localeEncodingMappings).withVersion(root.getAttribute("version"))
                .withDisplayName(displayName)
                .withMetadataComplete(Boolean.parseBoolean(root.getAttribute("metadata-complete").trim()));
    }

    private static WebAppDescriptor.ServletDef parseServlet(Element e) {
        String async = firstText(e, "async-supported");
        boolean asyncSupported = async != null && Boolean.parseBoolean(async.trim());
        return new WebAppDescriptor.ServletDef(
                firstText(e, "servlet-name"),
                firstText(e, "servlet-class"),
                parseInitParams(e),
                asyncSupported,
                parseLoadOnStartup(e));
    }

    /** Absent element: {@code Integer.MIN_VALUE}; empty element: {@code 0}. */
    private static int parseLoadOnStartup(Element e) {
        String t = firstText(e, "load-on-startup");
        if (t == null) return Integer.MIN_VALUE;
        return t.isEmpty() ? 0 : Integer.parseInt(t);
    }

    private static WebAppDescriptor.FilterDef parseFilter(Element e) {
        String async = firstText(e, "async-supported");
        return new WebAppDescriptor.FilterDef(
                firstText(e, "filter-name"),
                firstText(e, "filter-class"),
                parseInitParams(e),
                async != null && Boolean.parseBoolean(async.trim()));
    }

    private static List<WebAppDescriptor.FilterMappingDef> parseFilterMapping(Element e) {
        String filterName = firstText(e, "filter-name");
        Set<DispatcherType> types = EnumSet.noneOf(DispatcherType.class);
        for (Element d : childrenByTag(e, "dispatcher")) {
            types.add(DispatcherType.valueOf(text(d).trim()));
        }
        if (types.isEmpty()) types = EnumSet.of(DispatcherType.REQUEST);
        var out = new ArrayList<WebAppDescriptor.FilterMappingDef>();
        for (Element url : childrenByTag(e, "url-pattern")) {
            out.add(new WebAppDescriptor.FilterMappingDef(filterName, text(url), null, types));
        }
        for (Element sn : childrenByTag(e, "servlet-name")) {
            out.add(new WebAppDescriptor.FilterMappingDef(filterName, null, text(sn), types));
        }
        return out;
    }

    private static WebAppDescriptor.ErrorPageDef parseErrorPage(Element e) {
        String codeText = firstText(e, "error-code");
        Integer code = codeText == null ? null : Integer.parseInt(codeText.trim());
        String exceptionType = firstText(e, "exception-type");
        String location = firstText(e, "location");
        return new WebAppDescriptor.ErrorPageDef(code, exceptionType, location);
    }

    private static Map<String, String> parseInitParams(Element e) {
        Map<String, String> params = new LinkedHashMap<>();
        for (Element ip : childrenByTag(e, "init-param")) {
            String name = firstText(ip, "param-name");
            String value = firstText(ip, "param-value");
            if (name != null) params.put(name, value == null ? "" : value);
        }
        return params;
    }

    // ---- helpers DOM ----

    private static List<Element> children(Element parent) {
        NodeList kids = parent.getChildNodes();
        var out = new ArrayList<Element>();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n instanceof Element el) out.add(el);
        }
        return out;
    }

    private static List<Element> childrenByTag(Element parent, String tag) {
        var out = new ArrayList<Element>();
        for (Element e : children(parent)) {
            if (e.getTagName().equals(tag)) out.add(e);
        }
        return out;
    }

    private static String firstText(Element parent, String tag) {
        for (Element e : childrenByTag(parent, tag)) return text(e);
        return null;
    }

    private static String text(Element e) {
        String t = e.getTextContent();
        return t == null ? null : t.trim();
    }
}
