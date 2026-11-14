package io.vidocq.foy.internal.gen;

import io.vidocq.foy.internal.boot.DeployOptions;
import io.vidocq.foy.internal.boot.Deployment;
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.spi.gen.ComponentInstantiator;
import jakarta.servlet.ServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The deployed context exposes its component factory to the WebSocket module as an attribute. */
class ContextComponentInstantiatorTest {

    private Deployment deployment;

    @AfterEach
    void tearDown() {
        if (deployment != null) deployment.close();
    }

    public static class Plain {
    }

    @Test
    void aDeployedContextExposesAComponentInstantiator() throws Exception {
        var seenByInitializer = new AtomicReference<Object>();
        ServletContainerInitializer sci = (classes, c) ->
                seenByInitializer.set(c.getAttribute(ComponentInstantiator.ATTRIBUTE));
        deployment = WebAppDeployer.deploy(WebAppModel.builder("/").initializer(sci).build(),
                DeployOptions.defaults(getClass().getClassLoader()));

        var instantiator = (ComponentInstantiator) deployment.servletContext()
                .getAttribute(ComponentInstantiator.ATTRIBUTE);

        assertNotNull(instantiator);
        assertInstanceOf(Plain.class, instantiator.newInstance(Plain.class));
        assertNotNull(seenByInitializer.get());
    }
}
