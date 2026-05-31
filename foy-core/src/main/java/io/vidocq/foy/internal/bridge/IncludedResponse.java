package io.vidocq.foy.internal.bridge;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.util.Locale;

/**
 * {@link HttpServletResponseWrapper} used during an include (Servlet spec 6.1 §9.3).
 * <p>
 * The status setters (status, headers, cookies, content-type, buffer, redirect/error) are
 * no-op: only the primary response controls the HTTP header. Only {@code getWriter}/
 * {@code getOutputStream} remain connected so included content is appended to the body.
 * </p>
 */
public final class IncludedResponse extends HttpServletResponseWrapper {

    public IncludedResponse(HttpServletResponse primary) {
        super(primary);
    }

    @Override public void setStatus(int sc) {}
    @Override public void sendError(int sc) {}
    @Override public void sendError(int sc, String msg) {}
    @Override public void sendRedirect(String location) {}
    @Override public void sendRedirect(String location, int sc, boolean clearBuffer) {}
    @Override public void setHeader(String name, String value) {}
    @Override public void addHeader(String name, String value) {}
    @Override public void setDateHeader(String name, long date) {}
    @Override public void addDateHeader(String name, long date) {}
    @Override public void setIntHeader(String name, int value) {}
    @Override public void addIntHeader(String name, int value) {}
    @Override public void addCookie(Cookie cookie) {}
    @Override public void setContentType(String type) {}
    @Override public void setContentLength(int len) {}
    @Override public void setContentLengthLong(long len) {}
    @Override public void setCharacterEncoding(String charset) {}
    @Override public void setLocale(Locale loc) {}
    @Override public void setBufferSize(int size) {}
    @Override public void resetBuffer() {}
    @Override public void reset() {}
}
