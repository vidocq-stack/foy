/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.foy.internal.boot;

import io.vidocq.foy.internal.Identity;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** {@link CdiComponentFactory}: the bean of the component class when there is exactly one, the delegate otherwise. */
class CdiComponentFactoryTest {

    public static class Component extends HttpServlet {}

    public static final class Sub extends Component {}

    private final ComponentFactory reflective = ComponentFactory.reflective(getClass().getClassLoader());

    @Test
    void theBeanOfTheClassCreatesTheComponent() throws Exception {
        Component fromTheContainer = new Component();
        var factory = new CdiComponentFactory(new Beans().bean(Component.class, fromTheContainer).manager(), reflective);

        assertSame(fromTheContainer, factory.newInstance(Component.class));
    }

    @Test
    void aClassWithoutABeanIsCreatedByTheDelegate() throws Exception {
        var factory = new CdiComponentFactory(new Beans().manager(), reflective);

        assertInstanceOf(Component.class, factory.newInstance(Component.class));
    }

    @Test
    void theBeanOfASubclassIsNotTheBeanOfTheClass() throws Exception {
        var factory = new CdiComponentFactory(new Beans().bean(Sub.class, new Sub(), Component.class).manager(),
                reflective);

        assertEquals(Component.class, factory.newInstance(Component.class).getClass());
    }

    @Test
    void twoBeansOfTheClassLeaveItToTheDelegate() throws Exception {
        Component first = new Component();
        Component second = new Component();
        var factory = new CdiComponentFactory(new Beans().bean(Component.class, first).bean(Component.class, second)
                .manager(), reflective);

        Component created = factory.newInstance(Component.class);

        assertNotSame(first, created);
        assertNotSame(second, created);
    }

    @Test
    void aContainerFailureNamesTheClass() {
        var factory = new CdiComponentFactory(new Beans().bean(Component.class, null).manager(), reflective);

        ServletException e = assertThrows(ServletException.class, () -> factory.newInstance(Component.class));

        assertTrue(e.getMessage().contains(Component.class.getName()), e.getMessage());
        assertInstanceOf(IllegalStateException.class, e.getCause());
    }

    @Test
    void loadingVisibilityAndDescriptorsAreTheDelegates() throws Exception {
        ComponentFactory hidingAll = ComponentFactory.reflective(getClass().getClassLoader(), Set.of());
        var factory = new CdiComponentFactory(new Beans().manager(), hidingAll);

        assertEquals(Component.class, factory.load(Component.class.getName()));
        assertFalse(factory.isVisible(Component.class));
        assertEquals(hidingAll.descriptor(Component.class), factory.descriptor(Component.class));
    }

    /** Beans by lookup type; a bean without instance fails its creation, as a container whose bean throws. */
    private static final class Beans {
        private final Map<Bean<?>, Object> instances = new LinkedHashMap<>();
        private final Map<Class<?>, Set<Bean<?>>> byType = new LinkedHashMap<>();

        Beans bean(Class<?> beanClass, Object instance, Class<?>... alsoFoundAs) {
            Bean<?> bean = (Bean<?>) Proxy.newProxyInstance(Bean.class.getClassLoader(), new Class<?>[]{Bean.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "getBeanClass" -> beanClass;
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> Identity.isSame(p, a[0]);
                        case "toString" -> "Bean[" + beanClass.getName() + "]";
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
            instances.put(bean, instance);
            byType.computeIfAbsent(beanClass, k -> new LinkedHashSet<>()).add(bean);
            for (Class<?> type : alsoFoundAs) byType.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(bean);
            return this;
        }

        BeanManager manager() {
            return (BeanManager) Proxy.newProxyInstance(BeanManager.class.getClassLoader(),
                    new Class<?>[]{BeanManager.class}, (p, m, a) -> switch (m.getName()) {
                        case "getBeans" -> byType.getOrDefault((Class<?>) a[0], Set.of());
                        case "getReference" -> {
                            Object instance = instances.get(a[0]);
                            if (instance == null) throw new IllegalStateException("the bean failed");
                            yield instance;
                        }
                        case "createCreationalContext" -> null;
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> Identity.isSame(p, a[0]);
                        case "toString" -> "Beans";
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
        }
    }
}
