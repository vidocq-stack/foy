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
import jakarta.servlet.SessionTrackingMode;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Minimal parser of descriptor {@code web.xml} according to Servlet 6.1 §14.
 *
 * <p>Supported elements: {@code context-param}, {@code servlet},
 * {@code servlet-mapping}, {@code filter}, {@code filter-mapping}, {@code listener},
 * {@code error-page}, {@code session-config} (timeout, cookie-config, tracking-mode),
 * {@code welcome-file-list}, {@code mime-mapping}, encodings, {@code multipart-config} and the
 * security elements (see {@link WebXmlSecurityParser}).</p>
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
        String what = expectedRoot.equals("web-app") ? "web.xml" : "web-fragment.xml";
        Document doc;
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // Legacy 2.2 / 2.3 descriptors carry a DOCTYPE. Accept it, but never resolve or expand
            // anything: no external DTD, no external entities, no entity expansion.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.ErrorHandler() {
                public void warning(org.xml.sax.SAXParseException x) { /* ignored */ }
                public void error(org.xml.sax.SAXParseException x) throws org.xml.sax.SAXException { throw x; }
                public void fatalError(org.xml.sax.SAXParseException x) throws org.xml.sax.SAXException { throw x; }
            });
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            doc = builder.parse(in);
        } catch (Exception e) {
            throw new IOException("invalid " + what, e);
        }
        Element root = doc.getDocumentElement();
        root.normalize();
        String found = localName(root);
        if (!found.equals(expectedRoot)) {
            throw new IOException("expected <" + expectedRoot + "> root, found <" + found + ">");
        }
        try {
            return parse(root, expectedRoot.equals("web-fragment"), doctypeVersion(doc));
        } catch (RuntimeException e) {
            throw new IOException("invalid " + what + ": " + e.getMessage(), e);
        }
    }

    /** Version implied by a legacy DOCTYPE public id ("2.2", "2.3"), or {@code null}. */
    private static String doctypeVersion(Document doc) {
        DocumentType dt = doc.getDoctype();
        String id = dt == null ? null : dt.getPublicId();
        if (id == null) return null;
        String marker = "DTD Web Application ";
        int i = id.indexOf(marker);
        if (i < 0) return null;
        String rest = id.substring(i + marker.length());
        int end = rest.indexOf("//");
        String v = (end < 0 ? rest : rest.substring(0, end)).trim();
        return v.matches("\\d+\\.\\d+") ? v : null;
    }

    /** Descriptors older than 2.5 imply metadata-complete (Servlet §8.1): annotations are ignored. */
    private static boolean isPre25(String version) {
        if (isPre24(version)) return true;
        return version != null && version.trim().equals("2.4");
    }

    /** {@code true} for a descriptor version below 2.4 (lenient url-patterns, Tomcat compatibility). */
    private static boolean isPre24(String version) {
        if (version == null || version.isBlank()) return false;
        String[] parts = version.trim().split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major < 2 || (major == 2 && minor < 4);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * A pre-2.4 descriptor tolerated a url-pattern without leading slash: prepend it, except for
     * extension patterns and the empty pattern. Newer descriptors keep the pattern untouched.
     */
    static String lenientPattern(String pattern, boolean legacy) {
        if (!legacy || pattern == null || pattern.isEmpty() || pattern.startsWith("/")
                || pattern.startsWith("*.")) {
            return pattern;
        }
        return "/" + pattern;
    }

    static String localName(Element e) {
        String n = e.getTagName();
        int i = n.indexOf(':');
        return i < 0 ? n : n.substring(i + 1);
    }

    private static WebAppDescriptor parse(Element root, boolean fragment, String doctypeVersion) {
        String declared = root.getAttribute("version");
        String version = declared.isBlank() ? doctypeVersion : declared;
        boolean legacy = isPre24(version);
        Map<String, String> contextParams = new LinkedHashMap<>();
        var servlets = new ArrayList<WebAppDescriptor.ServletDef>();
        var servletMappings = new ArrayList<WebAppDescriptor.ServletMappingDef>();
        var filters = new ArrayList<WebAppDescriptor.FilterDef>();
        var filterMappings = new ArrayList<WebAppDescriptor.FilterMappingDef>();
        var listenerClasses = new ArrayList<String>();
        var errorPages = new ArrayList<WebAppDescriptor.ErrorPageDef>();
        int sessionTimeoutMinutes = -1;
        var welcomeFiles = new ArrayList<String>();
        var mimeMappings = new LinkedHashMap<String, String>();
        String requestEncoding = null;
        String responseEncoding = null;
        String defaultContextPath = null;
        boolean denyUncovered = false;
        WebAppDescriptor.CookieConfigDef cookieConfig = null;
        Set<SessionTrackingMode> trackingModes = EnumSet.noneOf(SessionTrackingMode.class);
        var securityConstraints = new ArrayList<SecurityDefs.SecurityConstraintDef>();
        SecurityDefs.LoginConfigDef loginConfig = null;
        var securityRoles = new ArrayList<String>();
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
                        servletMappings.add(new WebAppDescriptor.ServletMappingDef(sname, lenientPattern(text(url), legacy)));
                    }
                }
                case "filter" -> filters.add(parseFilter(e));
                case "filter-mapping" -> filterMappings.addAll(parseFilterMapping(e, legacy));
                case "listener" -> {
                    String cls = firstText(e, "listener-class");
                    if (cls != null) listenerClasses.add(cls);
                }
                case "error-page" -> errorPages.add(parseErrorPage(e));
                case "session-config" -> {
                    String t = firstText(e, "session-timeout");
                    // Zero or less: sessions never time out. Normalised to 0, as -1 means "absent".
                    if (t != null) {
                        sessionTimeoutMinutes = Math.max(0, parseInt(t, "<session-timeout> in <session-config>"));
                    }
                    var cc = WebXmlSecurityParser.parseCookieConfig(e);
                    if (cc != null) cookieConfig = cc;
                    trackingModes.addAll(WebXmlSecurityParser.parseTrackingModes(e));
                }
                case "welcome-file-list" -> {
                    for (Element w : childrenByTag(e, "welcome-file")) {
                        if (!text(w).isEmpty()) welcomeFiles.add(text(w));
                    }
                }
                case "mime-mapping" -> {
                    String ext = firstText(e, "extension");
                    String type = firstText(e, "mime-type");
                    if (ext != null && type != null) {
                        if (ext.startsWith(".")) ext = ext.substring(1);
                        mimeMappings.put(ext.toLowerCase(Locale.ROOT), type);
                    }
                }
                case "request-character-encoding" -> requestEncoding = text(e);
                case "response-character-encoding" -> responseEncoding = text(e);
                case "default-context-path" -> defaultContextPath = text(e);
                case "deny-uncovered-http-methods" -> denyUncovered = true;
                case "security-constraint" ->
                        securityConstraints.add(WebXmlSecurityParser.parseSecurityConstraint(e, legacy));
                case "login-config" -> loginConfig = WebXmlSecurityParser.parseLoginConfig(e);
                case "security-role" -> {
                    String r = firstText(e, "role-name");
                    if (r != null) securityRoles.add(r);
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
                localeEncodingMappings).withVersion(version)
                .withDisplayName(displayName)
                .withKind(fragment ? WebAppDescriptor.Kind.WEB_FRAGMENT : WebAppDescriptor.Kind.WEB_APP)
                .withFragmentName(fragmentName)
                .withOrdering(ordering)
                .withWelcomeFiles(welcomeFiles)
                .withMimeMappings(mimeMappings)
                .withRequestCharacterEncoding(requestEncoding)
                .withResponseCharacterEncoding(responseEncoding)
                .withDefaultContextPath(defaultContextPath)
                .withDenyUncoveredHttpMethods(denyUncovered)
                .withCookieConfig(cookieConfig)
                .withTrackingModes(trackingModes)
                .withSecurityConstraints(securityConstraints)
                .withLoginConfig(loginConfig)
                .withSecurityRoles(securityRoles)
                .withAbsoluteOrdering(absoluteOrdering)
                .withMetadataComplete(isPre25(version) || Boolean.TRUE.equals(xsdBoolean(
                        root.hasAttribute("metadata-complete") ? root.getAttribute("metadata-complete") : null,
                        "metadata-complete attribute")));
    }

    private static WebAppDescriptor.ServletDef parseServlet(Element e) {
        String name = firstText(e, "servlet-name");
        return new WebAppDescriptor.ServletDef(
                name,
                firstText(e, "servlet-class"),
                parseInitParams(e),
                parseAsync(e),
                parseLoadOnStartup(e, name),
                parseMultipart(e),
                !Boolean.FALSE.equals(xsdBoolean(firstText(e, "enabled"), "<enabled> of servlet " + name)),
                runAs(e),
                firstText(e, "jsp-file"));
    }

    private static String runAs(Element e) {
        for (Element r : childrenByTag(e, "run-as")) return firstText(r, "role-name");
        return null;
    }

    private static WebAppDescriptor.MultipartConfigDef parseMultipart(Element e) {
        for (Element m : childrenByTag(e, "multipart-config")) {
            return new WebAppDescriptor.MultipartConfigDef(
                    firstText(m, "location"),
                    parseLong(firstText(m, "max-file-size"), -1L, "<max-file-size> in <multipart-config>"),
                    parseLong(firstText(m, "max-request-size"), -1L,
                            "<max-request-size> in <multipart-config>"),
                    (int) parseLong(firstText(m, "file-size-threshold"), 0L,
                            "<file-size-threshold> in <multipart-config>"));
        }
        return null;
    }

    private static long parseLong(String t, long dflt, String where) {
        if (t == null || t.isBlank()) return dflt;
        try {
            return Long.parseLong(t.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid " + where + " value '" + t + "'", ex);
        }
    }

    /** {@code null} when the element is absent (tri-state, §8.2.3). */
    private static Boolean parseAsync(Element e) {
        return xsdBoolean(firstText(e, "async-supported"), "<async-supported>");
    }

    /**
     * An {@code xsd:boolean} value ({@code true-falseType}): {@code true}, {@code false}, {@code 1}
     * or {@code 0}, surrounding whitespace ignored. {@code null} when {@code text} is {@code null}.
     *
     * @throws IllegalArgumentException for any other value, naming {@code where}
     */
    static Boolean xsdBoolean(String text, String where) {
        if (text == null) return null;
        return switch (text.strip()) {
            case "true", "1" -> Boolean.TRUE;
            case "false", "0" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("invalid " + where + " value '" + text
                    + "': expected true, false, 1 or 0");
        };
    }

    /**
     * Absent or malformed element: {@code Integer.MIN_VALUE} (lazy). Empty element: {@code 0}
     * (present, so load at startup with order 0).
     */
    private static int parseLoadOnStartup(Element e, String servletName) {
        String t = firstText(e, "load-on-startup");
        if (t == null) return Integer.MIN_VALUE;
        if (t.isEmpty()) return 0; // present but empty: load at startup, order 0
        try {
            return Integer.parseInt(t.trim());
        } catch (NumberFormatException nfe) {
            LOG.log(System.Logger.Level.WARNING, "servlet '" + servletName
                    + "': invalid <load-on-startup> value '" + t + "', treated as lazy");
            return Integer.MIN_VALUE;
        }
    }

    static int parseInt(String text, String where) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + where + " value '" + text + "'", e);
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

    private static List<WebAppDescriptor.FilterMappingDef> parseFilterMapping(Element e, boolean legacy) {
        String filterName = firstText(e, "filter-name");
        Set<DispatcherType> types = EnumSet.noneOf(DispatcherType.class);
        for (Element d : childrenByTag(e, "dispatcher")) {
            String v = text(d).trim();
            try {
                types.add(DispatcherType.valueOf(v));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("invalid <dispatcher> value '" + v
                        + "' in <filter-mapping> of filter '" + filterName + "'", ex);
            }
        }
        if (types.isEmpty()) types = EnumSet.of(DispatcherType.REQUEST);
        var out = new ArrayList<WebAppDescriptor.FilterMappingDef>();
        for (Element url : childrenByTag(e, "url-pattern")) {
            out.add(new WebAppDescriptor.FilterMappingDef(filterName, lenientPattern(text(url), legacy), null, types));
        }
        for (Element sn : childrenByTag(e, "servlet-name")) {
            out.add(new WebAppDescriptor.FilterMappingDef(filterName, null, text(sn), types));
        }
        return out;
    }

    private static WebAppDescriptor.ErrorPageDef parseErrorPage(Element e) {
        String codeText = firstText(e, "error-code");
        Integer code = codeText == null ? null : parseInt(codeText, "<error-code> in <error-page>");
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

    static List<Element> children(Element parent) {
        NodeList kids = parent.getChildNodes();
        var out = new ArrayList<Element>();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n instanceof Element el) out.add(el);
        }
        return out;
    }

    static List<Element> childrenByTag(Element parent, String tag) {
        var out = new ArrayList<Element>();
        for (Element e : children(parent)) {
            if (e.getTagName().equals(tag)) out.add(e);
        }
        return out;
    }

    static String firstText(Element parent, String tag) {
        for (Element e : childrenByTag(parent, tag)) return text(e);
        return null;
    }

    static String text(Element e) {
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ENTITY_REFERENCE_NODE) {
                throw new IllegalArgumentException("entity references are not supported in web.xml (<"
                        + e.getTagName() + ">)");
            }
        }
        String t = e.getTextContent();
        return t == null ? null : t.trim();
    }
}
