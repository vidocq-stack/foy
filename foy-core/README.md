# vidocq-servlet-chappe-extension

Implémentation Jakarta Servlet 6.1 sur le moteur HTTP Chappe, intégrée à Vauban CDI.

## Statut actuel (jalon M2a)

MVP fonctionnel end-to-end :

- `HttpServlet#doGet`/`doPost` → réponse HTTP réelle via Chappe.
- `HttpServletRequest` : `getMethod`, `getRequestURI`, `getHeader(s)`, `getParameter(s)`, `getInputStream`, `getReader`, `getContextPath`, `getServletPath`, `getPathInfo`, `getServerName/Port`, `getRemoteAddr`, `getScheme`, `isSecure`, attributes.
- `HttpServletResponse` : status, headers, cookies (serialize Set-Cookie), `setContentType`, `getWriter`, `getOutputStream`, `sendRedirect`, `sendError`, body buffering.
- `ServletContext` (minimal, non-dynamique).
- Url-pattern matching selon Servlet 6.1 §12.2 (exact / prefix / extension / default / empty) avec précédence.
- Découverte CDI des beans `@WebServlet` via `BeanManager` Vauban.
- Montage sur `ChappeMountPoint` (par défaut listener `default`, context-path `/`).

## Non implémenté (jalons suivants)

- Filtres (`@WebFilter`, `FilterChain`).
- Sessions (`HttpSession`, cookie JSESSIONID).
- Listeners (`ServletContextListener`, `HttpSessionListener`, ...).
- Async (`startAsync`, `AsyncContext`).
- Multipart (`getParts`, `@MultipartConfig`).
- RequestDispatcher (forward/include).
- `web.xml`, `ServletContainerInitializer`.
- Security (BASIC, FORM, `@ServletSecurity`).
- Non-blocking I/O (`ReadListener`/`WriteListener`).

## Configuration

| Clé | Défaut | Description |
|---|---|---|
| `vidocq.servlet.context-path` | `/` | préfixe de montage |
| `vidocq.servlet.listener` | `default` | listener Chappe cible |

## Usage minimal

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
         ServletDispatcher.find(path)  (précédence exact>prefix>ext>default)
                  ↓
         HttpServletRequestImpl + HttpServletResponseImpl
                  ↓
         Servlet.service() → Servlet.doGet()/doPost()/...
                  ↓
         HttpServletResponseImpl (status + headers + buffer)
                  ↓
         Response Chappe (immuable)
```
