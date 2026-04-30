package io.vidocq.foy.internal.dispatcher;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FilterRegistryTest {

    @Test
    void returnsOnlyFiltersThatMatchPathAndDispatcherType() {
        Filter f1 = (req, res, c) -> {};
        Filter f2 = (req, res, c) -> {};
        Filter f3 = (req, res, c) -> {};
        var reg = new FilterRegistry(List.of(
                FilterMapping.onRequest(UrlPatternMatcher.of("/api/*"), f1, "F1"),
                FilterMapping.onRequest(UrlPatternMatcher.of("/admin/*"), f2, "F2"),
                FilterMapping.onRequest(UrlPatternMatcher.of("/*"), f3, "F3")
        ));
        var chain = reg.chainFor("/api/users", DispatcherType.REQUEST);
        assertEquals(List.of(f1, f3), chain);
    }

    @Test
    void preservesDeclarationOrder() {
        Filter first = (req, res, c) -> {};
        Filter second = (req, res, c) -> {};
        var reg = new FilterRegistry(List.of(
                FilterMapping.onRequest(UrlPatternMatcher.of("/*"), first, "A"),
                FilterMapping.onRequest(UrlPatternMatcher.of("/*"), second, "B")
        ));
        assertEquals(List.of(first, second), reg.chainFor("/x", DispatcherType.REQUEST));
    }

    @Test
    void dispatcherTypeMismatchExcludesFilter() {
        Filter f = (req, res, c) -> {};
        var reg = new FilterRegistry(List.of(
                new FilterMapping(UrlPatternMatcher.of("/*"), f, "F",
                        EnumSet.of(DispatcherType.FORWARD))));
        assertTrue(reg.chainFor("/x", DispatcherType.REQUEST).isEmpty());
        assertEquals(1, reg.chainFor("/x", DispatcherType.FORWARD).size());
    }

    @Test
    void emptyDispatcherTypesDefaultsToRequest() {
        Filter f = (req, res, c) -> {};
        var mapping = new FilterMapping(UrlPatternMatcher.of("/x"), f, "F", EnumSet.noneOf(DispatcherType.class));
        assertEquals(EnumSet.of(DispatcherType.REQUEST), mapping.dispatcherTypes());
    }
}
