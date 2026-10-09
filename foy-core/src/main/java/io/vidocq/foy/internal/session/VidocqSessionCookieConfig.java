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
package io.vidocq.foy.internal.session;

import io.vidocq.foy.internal.container.VidocqServletContext;
import jakarta.servlet.SessionCookieConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration of session cookies (Servlet 6.1 §7.1.2). Setters are not
 * valid only during the initialization phase of {@link VidocqServletContext}
 * (§4.4) — subsequent calls throw {@link IllegalStateException}.
 */
public final class VidocqSessionCookieConfig implements SessionCookieConfig {

    private final VidocqServletContext ctx;
    private String name = "JSESSIONID";
    private String domain;
    private String path;
    private int maxAge = -1;
    private final Map<String, String> attributes = new LinkedHashMap<>();

    public VidocqSessionCookieConfig(VidocqServletContext ctx) {
        this.ctx = ctx;
    }

    private void checkNotInitialized() {
        if (ctx.isInitializedInternal()) {
            throw new IllegalStateException("ServletContext already initialized");
        }
    }

    @Override public String getName() { return name; }
    @Override public void setName(String name) { checkNotInitialized(); this.name = name; }
    @Override public String getDomain() { return domain; }
    @Override public void setDomain(String domain) { checkNotInitialized(); this.domain = domain; }
    @Override public String getPath() { return path; }
    @Override public void setPath(String path) { checkNotInitialized(); this.path = path; }
    @Override public int getMaxAge() { return maxAge; }
    @Override public void setMaxAge(int maxAge) { checkNotInitialized(); this.maxAge = maxAge; }

    private boolean secure;
    private boolean secureExplicit;
    private boolean httpOnly = true;
    @Override public boolean isSecure() { return secure; }
    @Override public void setSecure(boolean secure) {
        checkNotInitialized();
        this.secure = secure;
        this.secureExplicit = true;
    }

    /**
     * {@code true} once the application chose the {@code Secure} flag ({@link #setSecure},
     * {@code <secure>} in the descriptor, or a {@code Secure} attribute); otherwise the container
     * marks the session cookie {@code Secure} exactly when the request is secure (section 7.1.1).
     */
    public boolean isSecureExplicit() { return secureExplicit; }
    @Override public boolean isHttpOnly() { return httpOnly; }
    @Override public void setHttpOnly(boolean httpOnly) { checkNotInitialized(); this.httpOnly = httpOnly; }

    // Methods deprecated in Servlet 6.0 mais toujours dans l'API.
    @SuppressWarnings("deprecation")
    @Override public String getComment() { return null; }
    @SuppressWarnings("deprecation")
    @Override public void setComment(String comment) { checkNotInitialized(); /* no-op — deprecated */ }

    @Override public String getAttribute(String name) {
        if ("Comment".equalsIgnoreCase(name)) return null;
        return attributes.get(name);
    }
    @Override public Map<String, String> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }
    @Override public void setAttribute(String name, String value) {
        checkNotInitialized();
        if (name == null) throw new IllegalArgumentException("name is null");
        if ("Name".equalsIgnoreCase(name)) { this.name = value; return; }
        if ("Domain".equalsIgnoreCase(name)) { this.domain = value; return; }
        if ("Path".equalsIgnoreCase(name)) { this.path = value; return; }
        if ("Max-Age".equalsIgnoreCase(name)) {
            this.maxAge = value == null ? -1 : Integer.parseInt(value); return;
        }
        if ("Secure".equalsIgnoreCase(name)) secureExplicit = true;
        attributes.put(name, value);
    }
}
