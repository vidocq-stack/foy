module io.vidocq.foy.chappe {
    requires transitive io.vidocq.foy.api;
    requires io.vidocq.foy.core;
    requires io.vidocq.chappe.api;
    requires jakarta.servlet;
    requires jakarta.cdi;

    exports io.vidocq.foy.chappe;
}
