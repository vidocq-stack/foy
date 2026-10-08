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
package io.vidocq.foy.internal.gen;

import io.vidocq.foy.spi.gen.WebComponent;
import io.vidocq.foy.spi.gen.WebComponentDescriptor;
import io.vidocq.foy.spi.gen.WebComponentDescriptor.Kind;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContainerInitializer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.System.Logger.Level;
import java.text.MessageFormat;
import java.util.EventListener;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Resolves any web component class to a {@link WebComponent} (descriptor and factory) through four
 * tiers, in order:
 * <ol>
 *   <li>{@link Tier#SERVICE_LOADER}: a {@code WebComponent} service provider declaring the type;</li>
 *   <li>{@link Tier#GENERATED_CLASS}: the {@code X$$FoyComponent} companion generated at build time;</li>
 *   <li>{@link Tier#CLASS_FILE}: descriptor read from the class bytes, hidden-class factory;</li>
 *   <li>{@link Tier#REFLECTION}: reflective instantiation (last resort, logged as a warning).</li>
 * </ol>
 * Results are cached per {@link Class} object (identity, never by name), so two classes with the
 * same name from different class loaders are resolved independently.
 *
 * <p>Spec-forbidden annotation misuse detected by the Class-File tier is a deployment failure:
 * the {@link IllegalArgumentException} propagates out of {@link #lookup(Class)}.
 */
public final class WebComponentRegistry {

    private static final System.Logger LOG = System.getLogger(WebComponentRegistry.class.getName());
    private static final String COMPANION_SUFFIX = "$$FoyComponent";

    /** The tier that resolved a component. */
    public enum Tier { SERVICE_LOADER, GENERATED_CLASS, CLASS_FILE, REFLECTION }

    /** Number of cached components per tier. */
    public record Stats(int serviceLoader, int generatedClass, int classFile, int reflection) {}

    private record Entry(WebComponent component, Tier tier) {}

    private final Map<Class<?>, WebComponent> providers;
    private final ConcurrentHashMap<Class<?>, Entry> cache = new ConcurrentHashMap<>();

    private WebComponentRegistry(Map<Class<?>, WebComponent> providers) {
        this.providers = providers;
    }

    /**
     * Creates a registry whose tier 1 index is the {@link ServiceLoader} view of {@code loader}.
     *
     * @param loader the web application class loader
     * @return a new registry
     */
    public static WebComponentRegistry forClassLoader(ClassLoader loader) {
        Map<Class<?>, WebComponent> index = new IdentityHashMap<>();
        Iterator<WebComponent> it = ServiceLoader.load(WebComponent.class, loader).iterator();
        while (true) {
            try {
                if (!it.hasNext()) {
                    break;
                }
                WebComponent provider = it.next();
                index.putIfAbsent(provider.type(), provider);
            } catch (ServiceConfigurationError e) {
                LOG.log(Level.WARNING, "foy: skipping a WebComponent provider that cannot be loaded: " + e.getMessage(), e);
            }
        }
        return new WebComponentRegistry(index);
    }

    /**
     * Resolves a component. Never returns null; cached per class.
     *
     * @param type the web component class
     * @return its descriptor and factory
     * @throws IllegalArgumentException if the class misuses the Servlet annotations
     */
    public WebComponent lookup(Class<?> type) {
        return entry(type).component();
    }

    /**
     * The tier that resolved {@code type}, resolving it first if needed.
     *
     * @param type the web component class
     * @return the resolving tier
     */
    public Tier tierOf(Class<?> type) {
        return entry(type).tier();
    }

    /** @return the number of cached components per tier */
    public Stats stats() {
        int[] n = new int[Tier.values().length];
        for (Entry e : cache.values()) {
            n[e.tier().ordinal()]++;
        }
        return new Stats(n[Tier.SERVICE_LOADER.ordinal()], n[Tier.GENERATED_CLASS.ordinal()],
                n[Tier.CLASS_FILE.ordinal()], n[Tier.REFLECTION.ordinal()]);
    }

    private Entry entry(Class<?> type) {
        Entry e = cache.get(type);
        return e != null ? e : cache.computeIfAbsent(type, this::resolve);
    }

    private Entry resolve(Class<?> type) {
        WebComponent provided = providers.get(type);
        if (provided != null) {
            return new Entry(provided, Tier.SERVICE_LOADER);
        }
        WebComponent companion = companion(type);
        if (companion != null) {
            return new Entry(companion, Tier.GENERATED_CLASS);
        }
        // IllegalArgumentException (annotation misuse) propagates: a deployment failure.
        Optional<WebComponentDescriptor> read = ClassFileDescriptorReader.read(type);
        // Readable non-web classes (AsyncListener, HttpUpgradeHandler, helpers) still get a hidden factory.
        WebComponentDescriptor descriptor = read.orElseGet(() -> WebComponentDescriptor.plain().withKind(kindOf(type)));
        String reason;
        try {
            Supplier<Object> factory = HiddenFactoryEmitter.factoryFor(type);
            LOG.log(Level.INFO, MessageFormat.format(
                    "foy: {0} resolved through the Class-File tier (not processed at build time)", type.getName()));
            return new Entry(new FactoryComponent(type, descriptor, factory), Tier.CLASS_FILE);
        } catch (IllegalAccessException e) {
            reason = String.valueOf(e.getMessage());
        }
        LOG.log(Level.WARNING, MessageFormat.format(
                "foy: {0} resolved by reflection ({1}); add foy-processor to the annotation processor path "
                        + "or open the package to io.vidocq.foy.core", type.getName(), reason));
        return new Entry(new ReflectiveComponent(type, descriptor), Tier.REFLECTION);
    }

    private static WebComponent companion(Class<?> type) {
        String name = type.getName() + COMPANION_SUFFIX;
        try {
            Class<?> generated = Class.forName(name, true, type.getClassLoader());
            if (!WebComponent.class.isAssignableFrom(generated)) {
                return null;
            }
            MethodHandle ctor = MethodHandles.publicLookup().findConstructor(generated, MethodType.methodType(void.class));
            WebComponent component = (WebComponent) ctor.invoke();
            if (component.type() != type) {
                LOG.log(Level.WARNING, "foy: ignoring {0}: it describes {1}, not {2}", name, component.type(), type);
                return null;
            }
            return component;
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception | LinkageError e) {
            LOG.log(Level.WARNING, "foy: cannot use the generated companion " + name + ": " + e, e);
            return null;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /** Unannotated classes are PLAIN, except container initializers. */
    private static Kind kindOf(Class<?> type) {
        return ServletContainerInitializer.class.isAssignableFrom(type) ? Kind.INITIALIZER : Kind.PLAIN;
    }

    /** Tier 3: descriptor read from the class bytes, hidden-class factory. */
    private record FactoryComponent(Class<?> type, WebComponentDescriptor descriptor, Supplier<Object> factory)
            implements WebComponent {
        @Override
        public Object newInstance() {
            return factory.get();
        }
    }

    /** Tier 4: plain reflection. */
    private record ReflectiveComponent(Class<?> type, WebComponentDescriptor descriptor) implements WebComponent {
        @Override
        public Object newInstance() {
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(type.getName() + " has no no-arg constructor", e);
            } catch (IllegalAccessException | RuntimeException e) {
                Module self = WebComponentRegistry.class.getModule();
                String pkg = type.getPackageName();
                if (type.getModule().isNamed() && !type.getModule().isOpen(pkg, self)) {
                    throw new IllegalStateException("cannot instantiate " + type.getName() + ": package " + pkg
                            + " is not open to the container; add 'opens " + pkg
                            + " to io.vidocq.foy.core' to its module descriptor", e);
                }
                throw new IllegalStateException("cannot instantiate " + type.getName()
                        + ": it has no accessible no-arg constructor (" + e + ")", e);
            } catch (InstantiationException | java.lang.reflect.InvocationTargetException e) {
                throw new IllegalStateException("cannot instantiate " + type.getName() + ": " + e, e);
            }
        }
    }
}
