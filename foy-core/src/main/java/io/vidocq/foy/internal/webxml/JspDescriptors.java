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

import jakarta.servlet.ServletException;
import jakarta.servlet.descriptor.*;
import java.util.*;
import org.w3c.dom.*;

/** Immutable Servlet API views of JSP configuration; preserves root/fragment ordering. */
public final class JspDescriptors {
    private static final String EL_IGNORED = "el-ignored";
    private static final String ERROR_ON_EL_NOT_FOUND = "error-on-el-not-found";
    private static final String PAGE_ENCODING = "page-encoding";
    private static final String SCRIPTING_INVALID = "scripting-invalid";
    private static final String IS_XML = "is-xml";
    private static final String DEFERRED_SYNTAX_ALLOWED_AS_LITERAL = "deferred-syntax-allowed-as-literal";
    private static final String TRIM_DIRECTIVE_WHITESPACES = "trim-directive-whitespaces";
    private static final String DEFAULT_CONTENT_TYPE = "default-content-type";
    private static final String BUFFER = "buffer";
    private static final String ERROR_ON_UNDECLARED_NAMESPACE = "error-on-undeclared-namespace";
    private static final Set<String> PROPERTIES = Set.of(EL_IGNORED, ERROR_ON_EL_NOT_FOUND, PAGE_ENCODING,
            SCRIPTING_INVALID, IS_XML, DEFERRED_SYNTAX_ALLOWED_AS_LITERAL, TRIM_DIRECTIVE_WHITESPACES,
            DEFAULT_CONTENT_TYPE, BUFFER, ERROR_ON_UNDECLARED_NAMESPACE);
    private JspDescriptors() { }
    private record Taglib(String uri, String location) implements TaglibDescriptor {
        @Override public String getTaglibURI() { return uri; }
        @Override public String getTaglibLocation() { return location; }
    }
    private record Group(List<String> patterns, List<String> preludes, List<String> codas,
                         Map<String, String> properties) implements JspPropertyGroupDescriptor {
        Group { patterns = List.copyOf(patterns); preludes = List.copyOf(preludes); codas = List.copyOf(codas); properties = Map.copyOf(properties); }
        @Override public Collection<String> getUrlPatterns() { return patterns; }
        @Override public Collection<String> getIncludePreludes() { return preludes; }
        @Override public Collection<String> getIncludeCodas() { return codas; }
        @Override public String getElIgnored() { return properties.get(EL_IGNORED); }
        @Override public String getErrorOnELNotFound() { return properties.get(ERROR_ON_EL_NOT_FOUND); }
        @Override public String getPageEncoding() { return properties.get(PAGE_ENCODING); }
        @Override public String getScriptingInvalid() { return properties.get(SCRIPTING_INVALID); }
        @Override public String getIsXml() { return properties.get(IS_XML); }
        @Override public String getDeferredSyntaxAllowedAsLiteral() { return properties.get(DEFERRED_SYNTAX_ALLOWED_AS_LITERAL); }
        @Override public String getTrimDirectiveWhitespaces() { return properties.get(TRIM_DIRECTIVE_WHITESPACES); }
        @Override public String getDefaultContentType() { return properties.get(DEFAULT_CONTENT_TYPE); }
        @Override public String getBuffer() { return properties.get(BUFFER); }
        @Override public String getErrorOnUndeclaredNamespace() { return properties.get(ERROR_ON_UNDECLARED_NAMESPACE); }
    }
    private record Config(List<TaglibDescriptor> tags, List<JspPropertyGroupDescriptor> groups) implements JspConfigDescriptor {
        Config { tags = List.copyOf(tags); groups = List.copyOf(groups); }
        @Override public Collection<TaglibDescriptor> getTaglibs() { return tags; }
        @Override public Collection<JspPropertyGroupDescriptor> getJspPropertyGroups() { return groups; }
    }
    static JspConfigDescriptor parse(Element root) {
        var tags = new ArrayList<TaglibDescriptor>(); var groups = new ArrayList<JspPropertyGroupDescriptor>(); boolean found = false;
        for (var config : children(root)) if (WebXmlParser.localName(config).equals("jsp-config")) {
            if (found) throw new IllegalArgumentException("Duplicate jsp-config");
            found = true;
            for (var entry : children(config)) {
                String name = WebXmlParser.localName(entry);
                if (name.equals("taglib")) {
                    var fields = fields(entry);
                    tags.add(new Taglib(required(fields, "taglib-uri"), required(fields, "taglib-location")));
                } else if (name.equals("jsp-property-group")) {
                    var patterns = new ArrayList<String>(); var preludes = new ArrayList<String>(); var codas = new ArrayList<String>();
                    var properties = new LinkedHashMap<String, String>();
                    for (var field : children(entry)) {
                        String key = WebXmlParser.localName(field), value = field.getTextContent().trim();
                        switch (key) {
                            case "url-pattern" -> patterns.add(value);
                            case "include-prelude" -> preludes.add(value);
                            case "include-coda" -> codas.add(value);
                            case "description", "display-name", "icon" -> { }
                            default -> {
                                if (!PROPERTIES.contains(key)) throw new IllegalArgumentException("Unknown JSP property: " + key);
                                if (properties.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate JSP property: " + key);
                            }
                        }
                    }
                    if (patterns.isEmpty()) throw new IllegalArgumentException("JSP property group needs a URL pattern");
                    groups.add(new Group(patterns, preludes, codas, properties));
                } else throw new IllegalArgumentException("Unknown jsp-config element: " + name);
            }
        }
        return found ? new Config(tags, groups) : null;
    }
    public static JspConfigDescriptor copy(JspConfigDescriptor config) {
        if (config == null || config instanceof Config) return config;
        var tags = new ArrayList<TaglibDescriptor>(); var groups = new ArrayList<JspPropertyGroupDescriptor>();
        for (var tag : config.getTaglibs()) tags.add(new Taglib(tag.getTaglibURI(), tag.getTaglibLocation()));
        for (var group : config.getJspPropertyGroups()) {
            var properties = new HashMap<String, String>();
            String[] values = {group.getElIgnored(), group.getErrorOnELNotFound(), group.getPageEncoding(), group.getScriptingInvalid(),
                    group.getIsXml(), group.getDeferredSyntaxAllowedAsLiteral(), group.getTrimDirectiveWhitespaces(),
                    group.getDefaultContentType(), group.getBuffer(), group.getErrorOnUndeclaredNamespace()};
            String[] keys = {EL_IGNORED, ERROR_ON_EL_NOT_FOUND, PAGE_ENCODING, SCRIPTING_INVALID, IS_XML,
                    DEFERRED_SYNTAX_ALLOWED_AS_LITERAL, TRIM_DIRECTIVE_WHITESPACES, DEFAULT_CONTENT_TYPE, BUFFER, ERROR_ON_UNDECLARED_NAMESPACE};
            for (int i = 0; i < keys.length; i++) if (values[i] != null) properties.put(keys[i], values[i]);
            groups.add(new Group(List.copyOf(group.getUrlPatterns()), List.copyOf(group.getIncludePreludes()), List.copyOf(group.getIncludeCodas()), properties));
        }
        return new Config(tags, groups);
    }
    static JspConfigDescriptor merge(JspConfigDescriptor root, List<JspConfigDescriptor> fragments) throws ServletException {
        var tags = new LinkedHashMap<String, TaglibDescriptor>(); var rootUris = new HashSet<String>();
        var groups = new ArrayList<JspPropertyGroupDescriptor>(); boolean present = root != null;
        if (root != null) {
            for (var tag : root.getTaglibs()) { tags.put(tag.getTaglibURI(), tag); rootUris.add(tag.getTaglibURI()); }
            groups.addAll(root.getJspPropertyGroups());
        }
        for (var config : fragments) if (config != null) {
            present = true; groups.addAll(config.getJspPropertyGroups());
            for (var tag : config.getTaglibs()) {
                var previous = tags.putIfAbsent(tag.getTaglibURI(), tag);
                if (previous != null && !rootUris.contains(tag.getTaglibURI()) && !previous.getTaglibLocation().equals(tag.getTaglibLocation()))
                    throw new ServletException("Conflicting fragment taglib URI: " + tag.getTaglibURI());
            }
        }
        return present ? new Config(List.copyOf(tags.values()), groups) : null;
    }
    private static List<Element> children(Element parent) {
        var result = new ArrayList<Element>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) if (node instanceof Element e) result.add(e);
        return result;
    }
    private static Map<String, String> fields(Element element) {
        var result = new HashMap<String, String>();
        for (var field : children(element)) {
            String key = WebXmlParser.localName(field);
            if (!key.equals("taglib-uri") && !key.equals("taglib-location"))
                throw new IllegalArgumentException("Unknown taglib field: " + key);
            if (result.putIfAbsent(key, field.getTextContent().trim()) != null)
                throw new IllegalArgumentException("Duplicate taglib field: " + key);
        }
        return result;
    }
    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
}
