module io.vidocq.foy.core {
    requires transitive io.vidocq.foy.api;
    requires transitive jakarta.servlet;
    requires static jakarta.cdi;
    requires static jakarta.annotation;
    requires java.xml;
    requires static java.net.http;

    // Couplage transport HTTP temporaire (M1) : HttpServletRequestImpl,
    // HttpServletResponseImpl, ServletOutputStreamImpl et ChappeServletBridge
    // référencent encore directement chappe-api. À découpler en M2 via
    // l'introduction d'une SPI FoyHttpExchange dans foy-api.
    requires io.vidocq.chappe.api;

    exports io.vidocq.foy.internal.async;
    exports io.vidocq.foy.internal.boot;
    exports io.vidocq.foy.internal.bridge;
    exports io.vidocq.foy.internal.container;
    exports io.vidocq.foy.internal.dispatcher;
    exports io.vidocq.foy.internal.error;
    exports io.vidocq.foy.internal.http;
    exports io.vidocq.foy.internal.listener;
    exports io.vidocq.foy.internal.security;
    exports io.vidocq.foy.internal.session;
    exports io.vidocq.foy.internal.webxml;
}
