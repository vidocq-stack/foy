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

import io.vidocq.foy.internal.webxml.MergeSlots.Keyed;
import io.vidocq.foy.internal.webxml.WebAppDescriptor.FilterDef;
import io.vidocq.foy.internal.webxml.WebAppDescriptor.FilterMappingDef;
import io.vidocq.foy.internal.webxml.WebAppDescriptor.MultipartConfigDef;
import io.vidocq.foy.internal.webxml.WebAppDescriptor.ServletDef;
import io.vidocq.foy.internal.webxml.WebAppDescriptor.ServletMappingDef;
import jakarta.servlet.ServletException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The component part of the fragment merge (Servlet 6.1 §8.2.3): servlets, filters, their
 * mappings and listeners. Package-private helper of {@link FragmentMerger}.
 */
final class ComponentMerger {

    private static final System.Logger LOG = System.getLogger(FragmentMerger.class.getName());

    private ComponentMerger() {}

    /** A servlet or filter being merged; servlet-only fields stay unset for filters. */
    private static final class Component {
        final String kind;
        final String name;
        final boolean inWebXml;
        String className;
        String classFragment;
        final Keyed<String> params;
        Boolean async;
        int loadOnStartup = Integer.MIN_VALUE;
        MultipartConfigDef multipart;
        boolean enabled = true;
        String runAs;
        String jspFile;

        Component(String kind, String name, boolean inWebXml) {
            this.kind = kind;
            this.name = name;
            this.inWebXml = inWebXml;
            this.params = new Keyed<>("<init-param>", " for " + kind + " '" + name + "'");
        }

        void fromWebXml(String cls, Map<String, String> initParams, Boolean asyncSupported) {
            className = cls;
            params.webXml(initParams);
            async = asyncSupported;
        }

        void fromFragment(String fragmentId, String cls, Map<String, String> initParams, Boolean asyncSupported)
                throws ServletException {
            if (!inWebXml && cls != null) {
                if (className == null) {
                    className = cls;
                    classFragment = fragmentId;
                } else if (!className.equals(cls)) {
                    throw MergeSlots.conflict("<" + kind + "-class> for " + kind + " '" + name + "'",
                            classFragment, fragmentId);
                }
            }
            params.fragment(fragmentId, initParams);
            if (async == null) async = asyncSupported;
        }
    }

    static List<ServletDef> servlets(WebAppDescriptor webXml, List<Fragment> ordered) throws ServletException {
        Map<String, Component> byName = new LinkedHashMap<>();
        for (ServletDef d : webXml.servlets()) {
            Component c = byName.computeIfAbsent(d.name(), n -> new Component("servlet", n, true));
            c.fromWebXml(d.className(), d.initParams(), d.asyncSupported());
            c.loadOnStartup = d.loadOnStartup();
            c.multipart = d.multipartConfig();
            c.enabled = d.enabled();
            c.runAs = d.runAs();
            c.jspFile = d.jspFile();
        }
        for (Fragment f : ordered) {
            for (ServletDef d : f.descriptor().servlets()) {
                Component c = byName.computeIfAbsent(d.name(), n -> new Component("servlet", n, false));
                c.fromFragment(f.id(), d.className(), d.initParams(), d.asyncSupported());
                if (c.loadOnStartup == Integer.MIN_VALUE) c.loadOnStartup = d.loadOnStartup();
                if (c.multipart == null) c.multipart = d.multipartConfig();
                if (c.runAs == null) c.runAs = d.runAs();
                if (c.jspFile == null) c.jspFile = d.jspFile();
                // web.xml decides <enabled>; between fragments, one "false" disables the servlet.
                if (!c.inWebXml) c.enabled &= d.enabled();
            }
        }
        var out = new ArrayList<ServletDef>();
        for (Component c : byName.values()) {
            out.add(new ServletDef(c.name, c.className, c.params.values(), c.async, c.loadOnStartup,
                    c.multipart, c.enabled, c.runAs, c.jspFile));
        }
        return out;
    }

    static List<FilterDef> filters(WebAppDescriptor webXml, List<Fragment> ordered) throws ServletException {
        Map<String, Component> byName = new LinkedHashMap<>();
        for (FilterDef d : webXml.filters()) {
            byName.computeIfAbsent(d.name(), n -> new Component("filter", n, true))
                    .fromWebXml(d.className(), d.initParams(), d.asyncSupported());
        }
        for (Fragment f : ordered) {
            for (FilterDef d : f.descriptor().filters()) {
                byName.computeIfAbsent(d.name(), n -> new Component("filter", n, false))
                        .fromFragment(f.id(), d.className(), d.initParams(), d.asyncSupported());
            }
        }
        var out = new ArrayList<FilterDef>();
        for (Component c : byName.values()) out.add(new FilterDef(c.name, c.className, c.params.values(), c.async));
        return out;
    }

    /**
     * §8.2.3 rule 1.d: web.xml's mappings of a servlet replace the fragments' ones; otherwise the
     * union. One pattern on two servlets in two fragments is a conflict; against web.xml, web.xml wins.
     */
    static List<ServletMappingDef> servletMappings(WebAppDescriptor webXml, List<Fragment> ordered)
            throws ServletException {
        var out = new ArrayList<>(webXml.servletMappings());
        Set<String> mappedInXml = new HashSet<>();
        Map<String, String> servletOf = new HashMap<>();
        Map<String, String> fragmentOf = new HashMap<>(); // absent: declared by web.xml
        for (ServletMappingDef m : webXml.servletMappings()) {
            mappedInXml.add(m.servletName());
            servletOf.putIfAbsent(m.urlPattern(), m.servletName());
        }
        for (Fragment f : ordered) {
            for (ServletMappingDef m : f.descriptor().servletMappings()) {
                if (mappedInXml.contains(m.servletName())) continue;
                String owner = servletOf.get(m.urlPattern());
                if (owner == null) {
                    servletOf.put(m.urlPattern(), m.servletName());
                    fragmentOf.put(m.urlPattern(), f.id());
                    out.add(m);
                } else if (owner.equals(m.servletName())) {
                    continue; // same mapping declared twice
                } else if (!fragmentOf.containsKey(m.urlPattern())) {
                    LOG.log(System.Logger.Level.WARNING, () -> "url-pattern '" + m.urlPattern() + "' of servlet '"
                            + m.servletName() + "' in web fragment " + f.id()
                            + " is ignored: web.xml maps it to servlet '" + owner + "'");
                } else {
                    throw MergeSlots.conflict("<servlet-mapping> for url-pattern '" + m.urlPattern()
                                    + "' (servlets '" + owner + "' and '" + m.servletName() + "')",
                            fragmentOf.get(m.urlPattern()), f.id());
                }
            }
        }
        return out;
    }

    /** web.xml mappings first, then the fragments' in merge order; web.xml's mappings of a filter replace the fragments' ones. */
    static List<FilterMappingDef> filterMappings(WebAppDescriptor webXml, List<Fragment> ordered) {
        var out = new ArrayList<>(webXml.filterMappings());
        var seen = new HashSet<>(out);
        Set<String> mappedInXml = new HashSet<>();
        for (FilterMappingDef m : webXml.filterMappings()) mappedInXml.add(m.filterName());
        for (Fragment f : ordered) {
            for (FilterMappingDef m : f.descriptor().filterMappings()) {
                // An identical mapping declared again by a later fragment is kept once.
                if (!mappedInXml.contains(m.filterName()) && seen.add(m)) out.add(m);
            }
        }
        return out;
    }

    static List<String> listeners(WebAppDescriptor webXml, List<Fragment> ordered) {
        var out = new LinkedHashSet<>(webXml.listenerClasses());
        for (Fragment f : ordered) out.addAll(f.descriptor().listenerClasses());
        return List.copyOf(out);
    }
}
