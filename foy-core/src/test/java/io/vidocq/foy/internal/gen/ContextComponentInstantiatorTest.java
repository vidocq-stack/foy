package io.vidocq.foy.internal.gen;

import io.vidocq.foy.internal.boot.DeployOptions;
import io.vidocq.foy.internal.boot.Deployment;
import io.vidocq.foy.internal.boot.WebAppDeployer;
import io.vidocq.foy.internal.boot.WebAppModel;
import io.vidocq.foy.spi.gen.ComponentInstantiator;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContextAttributeEvent;
import jakarta.servlet.ServletContextAttributeListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** The attribute is the container's: it is set before the application's listeners hear attributes. */
    @Test
    void anApplicationAttributeListenerDoesNotHearTheAttribute() {
        var added = new CopyOnWriteArrayList<String>();
        ServletContextAttributeListener listener = new ServletContextAttributeListener() {
            @Override
            public void attributeAdded(ServletContextAttributeEvent event) {
                added.add(event.getName());
            }
        };
        ServletContainerInitializer sci = (classes, c) -> c.setAttribute("app.attribute", "set by the application");
        deployment = WebAppDeployer.deploy(WebAppModel.builder("/")
                        .listener(new WebAppModel.ListenerDecl(ServletContextAttributeListener.class, () -> listener))
                        .initializer(sci).build(),
                DeployOptions.defaults(getClass().getClassLoader()));

        assertNotNull(deployment.servletContext().getAttribute(ComponentInstantiator.ATTRIBUTE));
        assertEquals(List.of("app.attribute"), added);
    }
}
