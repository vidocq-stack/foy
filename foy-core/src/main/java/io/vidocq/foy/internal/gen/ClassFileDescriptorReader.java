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

import io.vidocq.foy.spi.gen.WebComponentDescriptor;
import io.vidocq.foy.spi.gen.WebComponentDescriptor.Kind;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.HttpConstraintElement;
import jakarta.servlet.HttpMethodConstraintElement;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContextAttributeListener;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.annotation.ServletSecurity.EmptyRoleSemantic;
import jakarta.servlet.annotation.ServletSecurity.TransportGuarantee;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decodes Servlet annotations from class bytes ({@code java.lang.classfile}) — no reflection.
 *
 * <p>This is the runtime twin of the build-time {@code foy-processor}: for the same class it
 * produces the same {@link WebComponentDescriptor}. Class files only store the annotation
 * elements that were written explicitly, so the defaults declared by the Servlet 6.1 and
 * Jakarta Annotations definitions are applied here.
 *
 * <p>Failures fall into two categories:
 * <ul>
 *   <li><b>unreadable bytes</b> — unreachable, malformed or stale class bytes (including enum
 *       constant names unknown to the running Servlet API): {@link #read(Class)} returns empty
 *       and logs at {@code DEBUG};</li>
 *   <li><b>annotation misuse</b> forbidden by the specification — both {@code value} and
 *       {@code urlPatterns}, {@code @WebServlet}/{@code @WebFilter}/{@code @WebListener} on a class
 *       of the wrong type, {@code DENY} with {@code rolesAllowed}, invalid or duplicate HTTP method
 *       constraints: an {@link IllegalArgumentException} naming the class and the rule is thrown,
 *       which fails the deployment.</li>
 * </ul>
 */
public final class ClassFileDescriptorReader {

    private static final System.Logger LOG = System.getLogger(ClassFileDescriptorReader.class.getName());

    private static final String ANN = "Ljakarta/servlet/annotation/";
    private static final String WEB_SERVLET = ANN + "WebServlet;";
    private static final String WEB_FILTER = ANN + "WebFilter;";
    private static final String WEB_LISTENER = ANN + "WebListener;";
    private static final String MULTIPART = ANN + "MultipartConfig;";
    private static final String SECURITY = ANN + "ServletSecurity;";
    private static final String HANDLES_TYPES = ANN + "HandlesTypes;";
    private static final String DECLARE_ROLES = "Ljakarta/annotation/security/DeclareRoles;";
    private static final String RUN_AS = "Ljakarta/annotation/security/RunAs;";
    private static final String VALUE = "value";

    /** The listener interfaces a {@code @WebListener} class must implement at least one of. */
    private static final List<Class<?>> LISTENERS = List.of(
            ServletContextListener.class, ServletContextAttributeListener.class,
            ServletRequestListener.class, ServletRequestAttributeListener.class,
            HttpSessionListener.class, HttpSessionAttributeListener.class, HttpSessionIdListener.class);

    private ClassFileDescriptorReader() {
    }

    /**
     * Reads the descriptor of a web component from its class bytes.
     *
     * @param type the component class
     * @return the descriptor; empty if the class bytes are not reachable (e.g. generated/hidden
     *         class), are malformed or stale, or the class is not a web component
     * @throws IllegalArgumentException if the class misuses the Servlet annotations (see the class
     *         documentation)
     */
    public static Optional<WebComponentDescriptor> read(Class<?> type) {
        Optional<byte[]> bytes = bytesOf(type);
        if (bytes.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(decode(parse(bytes.get(), type), type));
        } catch (MalformedBytesException e) {
            LOG.log(System.Logger.Level.DEBUG, e.getMessage(), e.getCause());
            return Optional.empty();
        }
    }

    /**
     * Reads the descriptor of a web component from the given class bytes; also used to read
     * {@code @HandlesTypes}.
     *
     * @param classBytes the bytes of {@code type}'s class file
     * @param type       the class the bytes describe
     * @return the descriptor, or {@code null} when the class is not a web component
     * @throws IllegalArgumentException if the bytes are malformed, stale or do not describe
     *         {@code type}, or if the class misuses the Servlet annotations
     */
    static WebComponentDescriptor read(byte[] classBytes, Class<?> type) {
        try {
            return decode(parse(classBytes, type), type);
        } catch (MalformedBytesException e) {
            throw new IllegalArgumentException(e.getMessage(), e.getCause());
        }
    }

    /** Unreachable, malformed or stale class bytes — as opposed to annotation misuse. */
    private static final class MalformedBytesException extends RuntimeException {
        MalformedBytesException(String message, Throwable cause) {
            super(message, cause, false, false);
        }
    }

    /**
     * Loads the class file bytes of a type through its module, then its class loader.
     *
     * @return the bytes, empty when not reachable (hidden, array or primitive type, or no class
     *         file resource)
     */
    static Optional<byte[]> bytesOf(Class<?> type) {
        if (type.isHidden() || type.isArray() || type.isPrimitive()) {
            return Optional.empty();
        }
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = open(type, resource)) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "cannot read class bytes of " + type.getName(), e);
            return Optional.empty();
        }
    }

    private static InputStream open(Class<?> type, String resource) throws IOException {
        InputStream in = type.getModule().getResourceAsStream(resource);
        if (in == null && type.getClassLoader() != null) {
            in = type.getClassLoader().getResourceAsStream(resource);
        }
        return in;
    }

    // ---------------------------------------------------------------- phase 1: bytes to values

    /**
     * The decoded class: access flags, superclass internal name, and the runtime-visible
     * annotations by descriptor, each as element name to plain value ({@code String},
     * {@code Integer}, {@code Long}, {@code Boolean}, enum constant name, class binary name,
     * {@code List} for arrays, {@code Map} for nested annotations).
     */
    private record Parsed(int flags, String superclass, Map<String, Map<String, Object>> annotations) {
    }

    private static Parsed parse(byte[] bytes, Class<?> type) {
        String expected = type.getName().replace('.', '/');
        try {
            ClassModel model = ClassFile.of().parse(bytes);
            if (!model.thisClass().asInternalName().equals(expected)) {
                throw new MalformedBytesException("class bytes describe " + model.thisClass().asInternalName()
                        + ", not " + expected, null);
            }
            var annotations = new HashMap<String, Map<String, Object>>();
            model.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(attr -> {
                for (Annotation a : attr.annotations()) {
                    annotations.put(a.classSymbol().descriptorString(), elements(a));
                }
            });
            String superclass = model.superclass().map(c -> c.asInternalName()).orElse(null);
            return new Parsed(model.flags().flagsMask(), superclass, annotations);
        } catch (MalformedBytesException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MalformedBytesException("malformed class bytes for " + type.getName(), e);
        }
    }

    /** Resolves an enum constant name; an unknown name means stale or foreign class bytes. */
    private static <E extends Enum<E>> E constant(Class<E> enumType, String name, Class<?> type) {
        try {
            return Enum.valueOf(enumType, name);
        } catch (IllegalArgumentException e) {
            throw new MalformedBytesException("unknown " + enumType.getSimpleName() + " constant '" + name
                    + "' in the class bytes of " + type.getName(), e);
        }
    }

    private static Map<String, Object> elements(Annotation a) {
        var out = new LinkedHashMap<String, Object>();
        for (AnnotationElement e : a.elements()) {
            out.put(e.name().stringValue(), value(e.value()));
        }
        return out;
    }

    private static Object value(AnnotationValue v) {
        return switch (v) {
            case AnnotationValue.OfString s -> s.stringValue();
            case AnnotationValue.OfInt i -> i.intValue();
            case AnnotationValue.OfLong l -> l.longValue();
            case AnnotationValue.OfBoolean b -> b.booleanValue();
            case AnnotationValue.OfEnum e -> e.constantName().stringValue();
            case AnnotationValue.OfClass c -> binaryName(c.classSymbol());
            case AnnotationValue.OfArray a -> a.values().stream().map(ClassFileDescriptorReader::value).toList();
            case AnnotationValue.OfAnnotation n -> elements(n.annotation());
            default -> null; // element types the Servlet annotations never use
        };
    }

    /** The binary name ({@code Class.getName()} form) of a class or interface, as the processor emits it. */
    private static String binaryName(ClassDesc d) {
        if (d.isArray()) {
            return binaryName(d.componentType()) + "[]";
        }
        if (d.isPrimitive()) {
            return d.displayName();
        }
        String s = d.descriptorString();
        return s.substring(1, s.length() - 1).replace('/', '.');
    }

    // ---------------------------------------------------------------- phase 2: values to descriptor

    private static WebComponentDescriptor decode(Parsed p, Class<?> type) {
        Kind kind = kind(p, type);
        if (kind == null) {
            return null;
        }
        Map<String, Object> webServlet = p.annotations().get(WEB_SERVLET);
        Map<String, Object> main = webServlet != null ? webServlet : p.annotations().get(WEB_FILTER);
        MainElements e = main == null ? MainElements.NONE : mainElements(main, kind, type);
        Map<String, Object> multipart = p.annotations().get(MULTIPART);
        Map<String, Object> security = p.annotations().get(SECURITY);
        Map<String, Object> roles = p.annotations().get(DECLARE_ROLES);
        Map<String, Object> runAs = p.annotations().get(RUN_AS);
        return new WebComponentDescriptor(kind, e.name(), e.patterns(), e.params(), e.load(), e.async(),
                e.dispatchers(), e.servletNames(),
                multipart == null ? null : multipart(multipart),
                security == null ? null : security(security, type),
                roles == null ? List.of() : strings(roles, VALUE),
                runAs == null ? null : string(runAs, VALUE, null),
                handlesTypes(p, type));
    }

    /**
     * The kind of component {@code type} is: the kind of its Servlet annotation, else
     * {@code INITIALIZER} or {@code PLAIN} for a plain class; {@code null} when it is not a component.
     *
     * @throws IllegalArgumentException when the class does not implement what its annotation requires
     */
    private static Kind kind(Parsed p, Class<?> type) {
        boolean servlet = Servlet.class.isAssignableFrom(type);
        boolean filter = Filter.class.isAssignableFrom(type);
        boolean listener = LISTENERS.stream().anyMatch(l -> l.isAssignableFrom(type));
        if (p.annotations().get(WEB_SERVLET) != null) {
            return annotated(Kind.SERVLET, servlet, "@WebServlet", type, Servlet.class.getName());
        }
        if (p.annotations().get(WEB_FILTER) != null) {
            return annotated(Kind.FILTER, filter, "@WebFilter", type, Filter.class.getName());
        }
        if (p.annotations().get(WEB_LISTENER) != null) {
            return annotated(Kind.LISTENER, listener, "@WebListener", type, "a Servlet listener interface");
        }
        if (!isPlainClass(p)) {
            return null;
        }
        if (ServletContainerInitializer.class.isAssignableFrom(type)) {
            return Kind.INITIALIZER;
        }
        return servlet || filter || listener ? Kind.PLAIN : null;
    }

    /** {@code kind} when {@code implemented}; else the misuse of {@code annotation} on {@code type}. */
    private static Kind annotated(Kind kind, boolean implemented, String annotation, Class<?> type, String required) {
        if (!implemented) {
            throw new IllegalArgumentException(annotation + " requires " + type.getName() + " to implement " + required);
        }
        return kind;
    }

    /** The elements of a {@code @WebServlet} or {@code @WebFilter}, with the Servlet 6.1 defaults applied. */
    private record MainElements(String name, List<String> patterns, Map<String, String> params, int load,
                                boolean async, Set<DispatcherType> dispatchers, List<String> servletNames) {
        /** No {@code @WebServlet} nor {@code @WebFilter}. */
        static final MainElements NONE = new MainElements(null, List.of(), Map.of(), Integer.MIN_VALUE, false,
                Set.of(), List.of());
    }

    /** Decodes {@code main}, the {@code @WebServlet} ({@code kind == SERVLET}) or {@code @WebFilter} of {@code type}. */
    private static MainElements mainElements(Map<String, Object> main, Kind kind, Class<?> type) {
        List<String> value = strings(main, VALUE);
        List<String> urlPatterns = strings(main, "urlPatterns");
        if (!value.isEmpty() && !urlPatterns.isEmpty()) {
            throw new IllegalArgumentException(type.getName()
                    + ": 'value' and 'urlPatterns' must not be used together on the same annotation");
        }
        List<String> patterns = value.isEmpty() ? urlPatterns : value;
        String declared = string(main, kind == Kind.SERVLET ? "name" : "filterName", "");
        String name = declared.isEmpty() ? type.getName() : declared;
        boolean async = main.get("asyncSupported") instanceof Boolean b && b;
        Map<String, String> params = initParams(main);
        if (kind == Kind.SERVLET) {
            int declaredLoad = main.get("loadOnStartup") instanceof Integer i ? i : -1;
            return new MainElements(name, patterns, params, declaredLoad < 0 ? Integer.MIN_VALUE : declaredLoad,
                    async, Set.of(), List.of());
        }
        List<String> servletNames = strings(main, "servletNames");
        var types = EnumSet.noneOf(DispatcherType.class);
        for (String t : strings(main, "dispatcherTypes")) {
            types.add(constant(DispatcherType.class, t, type));
        }
        return new MainElements(name, patterns, params, Integer.MIN_VALUE, async,
                types.isEmpty() ? Set.of(DispatcherType.REQUEST) : types, servletNames);
    }

    /** The {@code initParams} of {@code main}, by name in declaration order. */
    private static Map<String, String> initParams(Map<String, Object> main) {
        var params = new LinkedHashMap<String, String>();
        for (Object o : list(main, "initParams")) {
            if (o instanceof Map<?, ?> param) {
                params.put(string(param, "name", ""), string(param, VALUE, ""));
            }
        }
        return params;
    }

    /** The {@code @HandlesTypes} classes of any initializer, whatever its kind: a {@code @WebListener} may also be the jar's initializer. */
    private static List<String> handlesTypes(Parsed p, Class<?> type) {
        Map<String, Object> handles = p.annotations().get(HANDLES_TYPES);
        if (handles == null || !ServletContainerInitializer.class.isAssignableFrom(type)) {
            return List.of();
        }
        return strings(handles, VALUE);
    }

    /** Whether an unannotated class may be a component: concrete, neither enum nor record. */
    private static boolean isPlainClass(Parsed p) {
        int excluded = ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT | ClassFile.ACC_ENUM
                | ClassFile.ACC_ANNOTATION;
        return (p.flags() & excluded) == 0 && !"java/lang/Record".equals(p.superclass());
    }

    private static MultipartConfigElement multipart(Map<String, Object> m) {
        return new MultipartConfigElement(string(m, "location", ""),
                m.get("maxFileSize") instanceof Long l ? l : -1L,
                m.get("maxRequestSize") instanceof Long l ? l : -1L,
                m.get("fileSizeThreshold") instanceof Integer i ? i : 0);
    }

    private static ServletSecurityElement security(Map<String, Object> m, Class<?> type) {
        HttpConstraintElement classConstraint = m.get(VALUE) instanceof Map<?, ?> c
                ? constraint(c, VALUE, "@HttpConstraint", type)
                : new HttpConstraintElement(EmptyRoleSemantic.PERMIT, TransportGuarantee.NONE);
        var methods = new ArrayList<HttpMethodConstraintElement>();
        try {
            for (Object o : list(m, "httpMethodConstraints")) {
                if (o instanceof Map<?, ?> mc) {
                    methods.add(new HttpMethodConstraintElement(string(mc, VALUE, ""),
                            constraint(mc, "emptyRoleSemantic", "@HttpMethodConstraint", type)));
                }
            }
            return new ServletSecurityElement(classConstraint, methods);
        } catch (MalformedBytesException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            // empty or duplicate HTTP method names
            throw new IllegalArgumentException(type.getName() + ": invalid @ServletSecurity: " + e.getMessage(), e);
        }
    }

    /** Rebuilds an {@code HttpConstraintElement}; {@code @HttpConstraint} names its semantic {@code value}. */
    private static HttpConstraintElement constraint(Map<?, ?> m, String semanticKey, String annotation,
                                                    Class<?> type) {
        EmptyRoleSemantic semantic = constant(EmptyRoleSemantic.class,
                string(m, semanticKey, EmptyRoleSemantic.PERMIT.name()), type);
        String[] roles = strings(m, "rolesAllowed").toArray(String[]::new);
        if (semantic == EmptyRoleSemantic.DENY && roles.length > 0) {
            throw new IllegalArgumentException(type.getName() + ": " + annotation
                    + " must not combine the DENY empty-role semantic with rolesAllowed");
        }
        return new HttpConstraintElement(semantic,
                constant(TransportGuarantee.class, string(m, "transportGuarantee", TransportGuarantee.NONE.name()), type),
                roles);
    }

    private static String string(Map<?, ?> m, String key, String fallback) {
        return m.get(key) instanceof String s ? s : fallback;
    }

    private static List<?> list(Map<?, ?> m, String key) {
        return m.get(key) instanceof List<?> l ? l : List.of();
    }

    private static List<String> strings(Map<?, ?> m, String key) {
        var out = new ArrayList<String>();
        for (Object o : list(m, key)) {
            if (o instanceof String s) {
                out.add(s);
            }
        }
        return out;
    }
}
