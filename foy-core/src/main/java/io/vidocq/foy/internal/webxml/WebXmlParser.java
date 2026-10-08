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

    private static final System.Logger LOG = System.getLogger(WebXmlParser.class.getName());

    /** Parses a web.xml; a {@code <web-fragment>} root is rejected. */
    public static WebAppDescriptor parse(InputStream in) throws IOException {
        return parse(in, "web-app");
    }

    /** Parses a web-fragment.xml; a {@code <web-app>} root is rejected. */
    public static WebAppDescriptor parseFragment(InputStream in) throws IOException {
        return parse(in, "web-fragment");
    }

    private static WebAppDescriptor parse(InputStream in, String expectedRoot) throws IOException {
        Document doc;
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            doc = builder.parse(in);
        } catch (Exception e) {
            throw new IOException("invalid " + (expectedRoot.equals("web-app") ? "web.xml" : "web-fragment.xml"), e);
        }
        Element root = doc.getDocumentElement();
        root.normalize();
        String found = localName(root);
        if (!found.equals(expectedRoot)) {
            throw new IOException("expected <" + expectedRoot + "> root, found <" + found + ">");
        }
        try {
            return parse(root, expectedRoot.equals("web-fragment"));
        } catch (RuntimeException e) {
            throw new IOException("invalid " + (expectedRoot.equals("web-app") ? "web.xml" : "web-fragment.xml")
                    + ": " + e.getMessage(), e);
        }
    }

    private static String localName(Element e) {
        String n = e.getTagName();
        int i = n.indexOf(':');
        return i < 0 ? n : n.substring(i + 1);
    }

    private static WebAppDescriptor parse(Element root, boolean fragment) {
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
        String fragmentName = null;
        Ordering ordering = Ordering.NONE;
        List<String> absoluteOrdering = null;

        for (Element e : children(root)) {
            switch (localName(e)) {
                case "name" -> { if (fragment) fragmentName = text(e); }
                case "ordering" -> {
                    if (fragment) ordering = parseOrdering(e);
                    else LOG.log(System.Logger.Level.WARNING,
                            "<ordering> in web.xml is ignored (use <absolute-ordering>), §8.2.2");
                }
                case "absolute-ordering" -> {
                    if (!fragment) absoluteOrdering = parseAbsoluteOrdering(e);
                    else LOG.log(System.Logger.Level.WARNING,
                            "<absolute-ordering> in a web-fragment.xml is ignored, §8.2.2");
                }
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
                .withKind(fragment ? WebAppDescriptor.Kind.WEB_FRAGMENT : WebAppDescriptor.Kind.WEB_APP)
                .withFragmentName(fragmentName)
                .withOrdering(ordering)
                .withAbsoluteOrdering(absoluteOrdering)
                .withMetadataComplete(Boolean.parseBoolean(root.getAttribute("metadata-complete").trim()));
    }

    private static WebAppDescriptor.ServletDef parseServlet(Element e) {
        String name = firstText(e, "servlet-name");
        return new WebAppDescriptor.ServletDef(
                name,
                firstText(e, "servlet-class"),
                parseInitParams(e),
                parseAsync(e),
                parseLoadOnStartup(e, name));
    }

    /** {@code null} when the element is absent (tri-state, §8.2.3). */
    private static Boolean parseAsync(Element e) {
        String async = firstText(e, "async-supported");
        return async == null ? null : Boolean.parseBoolean(async.trim());
    }

    /**
     * Absent, empty or malformed element: {@code Integer.MIN_VALUE} (lazy; the schema allows an
     * empty element, meaning the container loads the servlet whenever it chooses).
     */
    private static int parseLoadOnStartup(Element e, String servletName) {
        String t = firstText(e, "load-on-startup");
        if (t == null || t.isEmpty()) return Integer.MIN_VALUE;
        try {
            return Integer.parseInt(t.trim());
        } catch (NumberFormatException nfe) {
            LOG.log(System.Logger.Level.WARNING, "servlet '" + servletName
                    + "': invalid <load-on-startup> value '" + t + "', treated as lazy");
            return Integer.MIN_VALUE;
        }
    }

    private static Ordering parseOrdering(Element e) {
        var before = new ArrayList<String>();
        var after = new ArrayList<String>();
        boolean beforeOthers = false;
        boolean afterOthers = false;
        for (Element c : children(e)) {
            String n = localName(c);
            if (n.equals("before") || n.equals("after")) {
                boolean isBefore = n.equals("before");
                boolean others = false;
                for (Element k : children(c)) {
                    switch (localName(k)) {
                        case "name" -> (isBefore ? before : after).add(text(k));
                        case "others" -> others = true;
                        default -> { }
                    }
                }
                if (isBefore) beforeOthers |= others; else afterOthers |= others;
            }
        }
        return new Ordering(before, beforeOthers, after, afterOthers);
    }

    private static List<String> parseAbsoluteOrdering(Element e) {
        var out = new ArrayList<String>();
        boolean seenOthers = false;
        for (Element c : children(e)) {
            switch (localName(c)) {
                case "name" -> out.add(text(c));
                case "others" -> {
                    if (seenOthers) LOG.log(System.Logger.Level.WARNING,
                            "duplicate <others/> in <absolute-ordering> ignored");
                    else { seenOthers = true; out.add(WebAppDescriptor.OTHERS); }
                }
                default -> { }
            }
        }
        return out;
    }

    private static WebAppDescriptor.FilterDef parseFilter(Element e) {
        return new WebAppDescriptor.FilterDef(
                firstText(e, "filter-name"),
                firstText(e, "filter-class"),
                parseInitParams(e),
                parseAsync(e));
    }

    private static List<WebAppDescriptor.FilterMappingDef> parseFilterMapping(Element e) {
        String filterName = firstText(e, "filter-name");
        Set<DispatcherType> types = EnumSet.noneOf(DispatcherType.class);
        for (Element d : childrenByTag(e, "dispatcher")) {
            String v = text(d).trim();
            try {
                types.add(DispatcherType.valueOf(v));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("unknown <dispatcher> value '" + v + "'", ex);
            }
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
            if (name != null) params.putIfAbsent(name, value == null ? "" : value);
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
