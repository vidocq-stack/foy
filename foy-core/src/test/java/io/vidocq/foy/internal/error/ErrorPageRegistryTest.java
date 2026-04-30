package io.vidocq.foy.internal.error;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ErrorPageRegistryTest {

    @Test
    void statusCodeLookupReturnsRegisteredPath() {
        var reg = new ErrorPageRegistry().register(404, "/404.html");
        assertEquals("/404.html", reg.findByStatus(404).orElseThrow());
    }

    @Test
    void statusCodeOutOf4xx5xxIsRejected() {
        var reg = new ErrorPageRegistry();
        assertThrows(IllegalArgumentException.class, () -> reg.register(200, "/ok"));
        assertThrows(IllegalArgumentException.class, () -> reg.register(600, "/high"));
    }

    @Test
    void exceptionLookupReturnsMostSpecific() {
        var reg = new ErrorPageRegistry()
                .register(RuntimeException.class, "/rt.html")
                .register(IllegalArgumentException.class, "/iae.html");
        assertEquals("/iae.html", reg.findByException(new IllegalArgumentException()).orElseThrow());
        assertEquals("/rt.html", reg.findByException(new IllegalStateException()).orElseThrow());
    }

    @Test
    void exceptionLookupWalksSuperclassChain() {
        var reg = new ErrorPageRegistry().register(Throwable.class, "/any.html");
        assertEquals("/any.html", reg.findByException(new IOException()).orElseThrow());
    }

    @Test
    void exceptionLookupInspectsCauseChain() {
        var reg = new ErrorPageRegistry().register(NumberFormatException.class, "/nfe.html");
        var wrapper = new RuntimeException("wrapper", new NumberFormatException("root"));
        assertEquals("/nfe.html", reg.findByException(wrapper).orElseThrow());
    }

    @Test
    void absentMappingReturnsEmpty() {
        var reg = new ErrorPageRegistry();
        assertTrue(reg.findByStatus(500).isEmpty());
        assertTrue(reg.findByException(new RuntimeException()).isEmpty());
    }

    @Test
    void sizeReportsTotal() {
        var reg = new ErrorPageRegistry()
                .register(404, "/404.html")
                .register(500, "/500.html")
                .register(RuntimeException.class, "/rt.html");
        assertEquals(3, reg.size());
    }
}
