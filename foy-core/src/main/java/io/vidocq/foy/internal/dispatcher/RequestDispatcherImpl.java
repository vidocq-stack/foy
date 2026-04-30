package io.vidocq.foy.internal.dispatcher;

import io.vidocq.foy.internal.bridge.ForwardedRequest;
import io.vidocq.foy.internal.bridge.IncludedRequest;
import io.vidocq.foy.internal.bridge.IncludedResponse;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * {@link RequestDispatcher} pour forward/include.
 *
 * <p>Contrat Servlet 6.1 §9.4 (forward) : la réponse doit être non committed, le buffer
 * est vidé avant le dispatch, les attributs {@code jakarta.servlet.forward.*} sont positionnés
 * avec les valeurs originales.</p>
 *
 * <p>Contrat §9.3 (include) : la réponse primaire conserve ses headers/status (le wrapper
 * ignore les setters), les attributs {@code jakarta.servlet.include.*} exposent le chemin inclus.</p>
 */
public final class RequestDispatcherImpl implements RequestDispatcher {

    public interface Invoker {
        /** Exécute le dispatch : filter chain applicable + service() sur le servlet cible. */
        void invoke(DispatchTarget target, HttpServletRequest req, HttpServletResponse res,
                    DispatcherType type)
                throws IOException, ServletException;
    }

    private final DispatchTarget target;
    private final Invoker invoker;

    public RequestDispatcherImpl(DispatchTarget target, Invoker invoker) {
        this.target = target;
        this.invoker = invoker;
    }

    /** Dispatcher stub pour un chemin sans ressource — forward/include émettent 404. */
    public static RequestDispatcher notFound(String path) {
        return new RequestDispatcher() {
            @Override public void forward(ServletRequest request, ServletResponse response)
                    throws IOException {
                if (response instanceof HttpServletResponse res) {
                    if (res.isCommitted()) throw new IllegalStateException("response already committed");
                    res.resetBuffer();
                    res.sendError(HttpServletResponse.SC_NOT_FOUND, "No resource at " + path);
                }
            }
            @Override public void include(ServletRequest request, ServletResponse response) {
                // Pas de contenu inclus — la cible n'existe pas.
            }
        };
    }

    @Override
    public void forward(ServletRequest request, ServletResponse response)
            throws ServletException, IOException {
        HttpServletRequest req = unwrapHttp(request);
        HttpServletResponse res = unwrapHttpResponse(response);
        if (req == null || res == null) {
            throw new ServletException("non-HTTP dispatch");
        }
        if (res.isCommitted()) {
            throw new IllegalStateException("response already committed");
        }
        res.resetBuffer();

        String fullUri = req.getContextPath().equals("/") ? target.path()
                : req.getContextPath() + target.path();
        req.setAttribute("jakarta.servlet.forward.request_uri", req.getRequestURI());
        req.setAttribute("jakarta.servlet.forward.context_path", req.getContextPath());
        req.setAttribute("jakarta.servlet.forward.servlet_path", req.getServletPath());
        req.setAttribute("jakarta.servlet.forward.path_info", req.getPathInfo());
        req.setAttribute("jakarta.servlet.forward.query_string", req.getQueryString());

        var wrappedReq = new ForwardedRequest(req, target);
        invoker.invoke(target, wrappedReq, res, DispatcherType.FORWARD);
        // Note : fullUri n'est pas exposé directement ; il est reconstituable via getRequestURI() du wrapper.
        assert fullUri != null;
    }

    @Override
    public void include(ServletRequest request, ServletResponse response)
            throws ServletException, IOException {
        HttpServletRequest req = unwrapHttp(request);
        HttpServletResponse res = unwrapHttpResponse(response);
        if (req == null || res == null) {
            throw new ServletException("non-HTTP dispatch");
        }
        String fullUri = req.getContextPath().equals("/") ? target.path()
                : req.getContextPath() + target.path();
        req.setAttribute("jakarta.servlet.include.request_uri", fullUri);
        req.setAttribute("jakarta.servlet.include.context_path", req.getContextPath());
        req.setAttribute("jakarta.servlet.include.servlet_path", target.servletPath());
        req.setAttribute("jakarta.servlet.include.path_info", target.pathInfo());
        req.setAttribute("jakarta.servlet.include.query_string", target.queryString());

        var wrappedReq = new IncludedRequest(req, target);
        var wrappedRes = new IncludedResponse(res);
        invoker.invoke(target, wrappedReq, wrappedRes, DispatcherType.INCLUDE);
    }

    /** Unwrap via {@link jakarta.servlet.ServletRequestWrapper#getRequest()} jusqu'à
     *  trouver un {@link HttpServletRequest}. Permet à un ServletRequestWrapper de
     *  base (non-Http) de déclencher un forward/include. */
    private static HttpServletRequest unwrapHttp(ServletRequest r) {
        while (r != null) {
            if (r instanceof HttpServletRequest h) return h;
            if (r instanceof jakarta.servlet.ServletRequestWrapper w) r = w.getRequest();
            else return null;
        }
        return null;
    }

    private static HttpServletResponse unwrapHttpResponse(ServletResponse r) {
        while (r != null) {
            if (r instanceof HttpServletResponse h) return h;
            if (r instanceof jakarta.servlet.ServletResponseWrapper w) r = w.getResponse();
            else return null;
        }
        return null;
    }
}
