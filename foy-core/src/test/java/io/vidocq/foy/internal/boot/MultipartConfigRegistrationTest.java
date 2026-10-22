package io.vidocq.foy.internal.boot;

import io.vidocq.foy.internal.boot.WebAppModel.ServletDecl;
import io.vidocq.foy.internal.container.VidocqServletContext;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Section 3.2 / 8.1.5: the effective multipart-config of each servlet reaches the context. */
class MultipartConfigRegistrationTest {

    private Deployment deployment;

    @AfterEach
    void tearDown() { if (deployment != null) deployment.close(); }

    public static class Plain extends HttpServlet {}

    @MultipartConfig(location = "annotated", maxFileSize = 10, maxRequestSize = 100, fileSizeThreshold = 1)
    public static class Annotated extends HttpServlet {}

    private static ServletDecl decl(String name, MultipartConfigElement config) {
        return new ServletDecl(name, Plain.class, Plain::new, List.of("/" + name), Map.of(), Integer.MIN_VALUE,
                false, null, config, true);
    }

    private VidocqServletContext deploy(WebAppModel model) {
        deployment = WebAppDeployer.deploy(model, DeployOptions.defaults(getClass().getClassLoader()));
        return deployment.servletContext();
    }

    @Test
    void aDeclaredConfigurationReachesTheContext() {
        var ctx = deploy(WebAppModel.builder("/")
                .servlet(decl("up", new MultipartConfigElement("d", 1, 2, 3)))
                .servlet(decl("plain", null)).build());
        var config = ctx.multipartConfig("up");
        assertEquals("d", config.getLocation());
        assertEquals(1, config.getMaxFileSize());
        assertEquals(2, config.getMaxRequestSize());
        assertEquals(3, config.getFileSizeThreshold());
        assertNull(ctx.multipartConfig("plain"));
        assertNull(ctx.multipartConfig(null));
    }

    @Test
    void aDynamicServletGetsItsAnnotationUnlessSetMultipartConfigOverridesIt() {
        ServletContainerInitializer sci = (classes, c) -> {
            c.addServlet("annotated", Annotated.class).addMapping("/a");
            var overridden = c.addServlet("overridden", Annotated.class);
            overridden.addMapping("/o");
            overridden.setMultipartConfig(new MultipartConfigElement("set", 5, 50, 0));
        };
        var ctx = deploy(WebAppModel.builder("/").initializer(sci).build());
        assertEquals("annotated", ctx.multipartConfig("annotated").getLocation());
        assertEquals(10, ctx.multipartConfig("annotated").getMaxFileSize());
        assertEquals("set", ctx.multipartConfig("overridden").getLocation());
        assertEquals(5, ctx.multipartConfig("overridden").getMaxFileSize());
    }

    @Test
    void setMultipartConfigRefusesNullAndAnInitializedContext() {
        var ctx = deploy(WebAppModel.builder("/").servlet(decl("up", null)).build());
        var registration = (ServletRegistration.Dynamic) ctx.getServletRegistration("up");
        var element = new MultipartConfigElement("");
        assertThrows(IllegalStateException.class, () -> registration.setMultipartConfig(element));
        var fresh = new VidocqServletContext("/").addServlet("x", new Plain());
        assertThrows(IllegalArgumentException.class, () -> fresh.setMultipartConfig(null));
    }
}
