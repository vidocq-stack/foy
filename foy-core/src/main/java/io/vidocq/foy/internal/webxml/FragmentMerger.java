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
import io.vidocq.foy.internal.webxml.MergeSlots.Single;
import io.vidocq.foy.internal.webxml.WebAppDescriptor.CookieConfigDef;
import io.vidocq.foy.internal.webxml.WebAppDescriptor.ErrorPageDef;
import jakarta.servlet.ServletException;
import jakarta.servlet.SessionTrackingMode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Folds the ordered web fragments into web.xml, producing the one effective descriptor of the
 * application (Servlet 6.1 §8.2.3).
 *
 * <p>web.xml always wins. Between fragments, a value for the same key that differs fails the
 * deployment, unless web.xml declares that key. Collections (security constraints, roles,
 * listeners, filter mappings) are additive, web.xml first and then the fragments in the given
 * order.</p>
 *
 * <p><b>metadata-complete.</b> When web.xml is {@code metadata-complete="true"}, the fragments are
 * not merged: web.xml is returned as is, and the caller ignores every annotation; the ordering
 * still decides which jars' container initializers run (§8.2.4). Source: the
 * {@code metadata-complete} documentation of {@code web-common_6_1.xsd} shipped in
 * {@code jakarta.servlet-api} 6.1.0 ("this deployment descriptor and other related deployment
 * descriptors for this module ... are complete"; annotations "must" then be ignored); the spec
 * text of §8.2.1 was not reachable from the build machine, so the fragment part follows Apache
 * Tomcat ({@code ContextConfig.webConfig}: no fragment merge when web.xml is metadata-complete,
 * container initializers still run). A fragment's own {@code metadata-complete="true"} keeps its
 * descriptor in the merge and only drops the annotations of its jar, which is the caller's job
 * ({@code DescriptorMerger}).</p>
 *
 * <p>Fragments excluded by an {@code <absolute-ordering>} are expected to be absent from
 * {@code ordered} already ({@link FragmentOrderer#order}); this class neither filters nor re-orders.</p>
 */
public final class FragmentMerger {

    private FragmentMerger() {}

    /**
     * Folds the ordered fragments into web.xml. web.xml always wins; between fragments, a conflicting
     * value for the same key fails unless web.xml declares that key.
     *
     * @param webXml  the application's web.xml ({@link WebAppDescriptor#empty()} when absent)
     * @param ordered the fragments to merge, in merge order (output of {@link FragmentOrderer#order})
     * @return the effective descriptor; its {@link WebAppDescriptor#kind()} is {@code WEB_APP} and it
     *         keeps web.xml's version, display name, metadata-complete flag and absolute ordering
     * @throws ServletException on a conflict, naming the element, the key and both fragment ids
     */
    public static WebAppDescriptor merge(WebAppDescriptor webXml, List<Fragment> ordered)
            throws ServletException {
        if (webXml.metadataComplete() || ordered.isEmpty()) return webXml;

        var contextParams = new Keyed<String>("<context-param>", "");
        contextParams.webXml(webXml.contextParams());
        var mimeMappings = new Keyed<String>("<mime-mapping>", "");
        mimeMappings.webXml(webXml.mimeMappings());
        var localeEncodings = new Keyed<String>("<locale-encoding-mapping>", "");
        localeEncodings.webXml(webXml.localeEncodingMappings());
        var errorPages = new Keyed<ErrorPageDef>("<error-page>", "");
        for (ErrorPageDef p : webXml.errorPages()) errorPages.webXml(errorPageKey(p), p);

        var timeout = new Single<Integer>("<session-timeout>").webXml(timeout(webXml));
        var cookieConfig = new Single<CookieConfigDef>("<cookie-config>").webXml(webXml.cookieConfig());
        var trackingModes = new Single<Set<SessionTrackingMode>>("<tracking-mode>")
                .webXml(modes(webXml));
        var requestEncoding = new Single<String>("<request-character-encoding>")
                .webXml(webXml.requestCharacterEncoding());
        var responseEncoding = new Single<String>("<response-character-encoding>")
                .webXml(webXml.responseCharacterEncoding());
        var defaultContextPath = new Single<String>("<default-context-path>")
                .webXml(webXml.defaultContextPath());
        var loginConfig = new Single<SecurityDefs.LoginConfigDef>("<login-config>")
                .webXml(webXml.loginConfig());
        boolean denyUncovered = webXml.denyUncoveredHttpMethods();
        var welcomeFiles = new LinkedHashSet<String>();
        var securityConstraints = new ArrayList<>(webXml.securityConstraints());
        var securityRoles = new LinkedHashSet<>(webXml.securityRoles());

        for (Fragment f : ordered) {
            WebAppDescriptor d = f.descriptor();
            String id = f.id();
            contextParams.fragment(id, d.contextParams());
            mimeMappings.fragment(id, d.mimeMappings());
            localeEncodings.fragment(id, d.localeEncodingMappings());
            for (ErrorPageDef p : d.errorPages()) errorPages.fragment(id, errorPageKey(p), p);
            timeout.fragment(id, timeout(d));
            cookieConfig.fragment(id, d.cookieConfig());
            trackingModes.fragment(id, modes(d));
            requestEncoding.fragment(id, d.requestCharacterEncoding());
            responseEncoding.fragment(id, d.responseCharacterEncoding());
            defaultContextPath.fragment(id, d.defaultContextPath());
            loginConfig.fragment(id, d.loginConfig());
            // A boolean flag: declared means true, so any declaring descriptor turns it on.
            denyUncovered |= d.denyUncoveredHttpMethods();
            welcomeFiles.addAll(d.welcomeFiles());
            securityConstraints.addAll(d.securityConstraints());
            securityRoles.addAll(d.securityRoles());
        }

        Integer sessionTimeout = timeout.value();
        Set<SessionTrackingMode> modes = trackingModes.value();
        return new WebAppDescriptor(contextParams.values(),
                ComponentMerger.servlets(webXml, ordered),
                ComponentMerger.servletMappings(webXml, ordered),
                ComponentMerger.filters(webXml, ordered),
                ComponentMerger.filterMappings(webXml, ordered),
                ComponentMerger.listeners(webXml, ordered),
                List.copyOf(errorPages.values().values()),
                sessionTimeout == null ? -1 : sessionTimeout,
                localeEncodings.values())
                .withVersion(webXml.version())
                .withDisplayName(webXml.displayName())
                .withMetadataComplete(webXml.metadataComplete())
                .withAbsoluteOrdering(webXml.absoluteOrdering())
                .withWelcomeFiles(webXml.welcomeFiles().isEmpty() ? List.copyOf(welcomeFiles) : webXml.welcomeFiles())
                .withMimeMappings(mimeMappings.values())
                .withRequestCharacterEncoding(requestEncoding.value())
                .withResponseCharacterEncoding(responseEncoding.value())
                .withDefaultContextPath(defaultContextPath.value())
                .withDenyUncoveredHttpMethods(denyUncovered)
                .withCookieConfig(cookieConfig.value())
                .withTrackingModes(modes == null ? Set.of() : modes)
                .withSecurityConstraints(securityConstraints)
                .withLoginConfig(loginConfig.value())
                .withSecurityRoles(List.copyOf(securityRoles));
    }

    /** Error pages are keyed by status code, by exception type, or are the default page. */
    private static String errorPageKey(ErrorPageDef p) {
        if (p.statusCode() != null) return "error-code " + p.statusCode();
        if (p.exceptionType() != null) return "exception-type " + p.exceptionType();
        return "default";
    }

    private static Integer timeout(WebAppDescriptor d) {
        return d.sessionTimeoutMinutes() == -1 ? null : d.sessionTimeoutMinutes();
    }

    private static Set<SessionTrackingMode> modes(WebAppDescriptor d) {
        return d.trackingModes().isEmpty() ? null : d.trackingModes();
    }
}
