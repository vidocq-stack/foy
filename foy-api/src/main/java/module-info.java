module io.vidocq.foy.api {
    requires transitive jakarta.servlet;
    requires static jakarta.annotation;

    exports io.vidocq.foy.spi.session;
    exports io.vidocq.foy.spi.security;
}
