module io.vidocq.foy.cdi.vauban {
    requires transitive io.vidocq.foy.api;
    requires jakarta.cdi;
    requires io.vidocq.vauban.core;

    exports io.vidocq.foy.cdi.vauban;
}
