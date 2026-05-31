# vidocq-servlet-chappe-extension

Jakarta Servlet 6.1 implementation on the Chappe HTTP engine, integrated with Vauban CDI.

## Current status (Milestone M2a)

Functional end-to-end MVP:

- `HttpServlet#doGet`/`doPost` → real HTTP response via Chappe.
- `HttpServletRequest`: `getMethod`, `getRequestURI`, `getHeader(s)`, `getParameter(s)`, `getInputStream`, `getReader`, `getContextPath`, `getServletPath`, `getPathInfo`, `getServerName/Port`, `getRemoteAddr`, `getScheme`, `isSecure`, attributes.
- `HttpServletResponse`: status, headers, cookies (serialize Set-Cookie), `setContentType`, `getWriter`, `getOutputStream`, `sendRedirect`, `sendError`, body buffering.
- `ServletContext` (minimal, non-dynamic).
- URL-pattern matching per Servlet 6.1 §12.2 (exact / prefix / extension / default / empty) with precedence.
- CDI discovery of `@WebServlet` beans via the Vauban `BeanManager`.
- Mounted on `ChappeMountPoint` (default listener `default`, context-path `/`).

## Not yet implemented (Upcoming milestones)

- Filters (`@WebFilter`, `FilterChain`).
- Sessions (`HttpSession`, JSESSIONID cookie).
- Listeners (`ServletContextListener`, `HttpSessionListener`, ...).
- Async (`startAsync`, `AsyncContext`).
- Multipart (`getParts`, `@MultipartConfig`).
- RequestDispatcher (forward/include).
- `web.xml`, `ServletContainerInitializer`.
- Security (BASIC, FORM, `@ServletSecurity`).
- Non-blocking I/O (`ReadListener`/`WriteListener`).

## Configuration

| Key | Default | Description |
|---|---|---|
| `vidocq.servlet.context-path` | `/` | mount path prefix |
| `vidocq.servlet.listener` | `default` | target Chappe listener |

## Minimal usage

```java
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;

@ApplicationScoped
@WebServlet("/hello")
public class HelloServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain;charset=utf-8");
        String name = req.getParameter("name");
        resp.getWriter().write("Hello, " + (name == null ? "world" : name));
    }
}
```

## Architecture

```
Request Chappe → ChappeServletBridge (Handler)
                  ↓
         ServletDispatcher.find(path)  (precedence exact>prefix>ext>default)
                  ↓
         HttpServletRequestImpl + HttpServletResponseImpl
                  ↓
         Servlet.service() → Servlet.doGet()/doPost()/...
                  ↓
         HttpServletResponseImpl (status + headers + buffer)
                  ↓
         Response Chappe (immutable)
```
