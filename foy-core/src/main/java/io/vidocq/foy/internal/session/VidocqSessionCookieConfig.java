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
    private boolean httpOnly = true;
    @Override public boolean isSecure() { return secure; }
    @Override public void setSecure(boolean secure) { checkNotInitialized(); this.secure = secure; }
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
        attributes.put(name, value);
    }
}
