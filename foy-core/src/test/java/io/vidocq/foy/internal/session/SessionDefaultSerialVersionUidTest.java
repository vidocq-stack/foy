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
package io.vidocq.foy.internal.session;

import io.vidocq.foy.internal.LogCapture;
import io.vidocq.foy.internal.container.VidocqServletContext;
import io.vidocq.foy.internal.session.SessionManager.SessionImport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectStreamClass;
import java.io.ObjectStreamField;
import java.lang.reflect.Array;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A class that declares no {@code serialVersionUID} gets one from the JDK, computed from its non-private methods and
 * constructors among other things, so a method added changes it and plain Java refuses the stream. Across a swap Foy
 * reads such a class with the new class's descriptor when its serialized shape is the same, and otherwise drops the
 * session and says why (BUG-20261117-01). Each test compiles two versions of the same classes and loads them through
 * two loaders, as the old and the new application layer: the export runs on the first, the import on the second.
 */
class SessionDefaultSerialVersionUidTest {

    private static final String VALUE = "value";
    private static final SessionImport ONE = new SessionImport(1, 0, 0);
    private static final SessionImport DROPPED = new SessionImport(0, 0, 1);
    /** Where a version adds its members to a source. */
    private static final String MORE = "// more";
    private static final Pattern TYPE_NAME = Pattern.compile("public (?:class|enum|record|interface) (\\w+)");

    private static final String DECLARED = "its declared serialVersionUID changed";
    private static final String EXTERNALIZABLE = "its default serialVersionUID changed and it is Externalizable";
    private static final String WRITES_OBJECT = "its default serialVersionUID changed and it declares writeObject";
    private static final String FIELDS = "its default serialVersionUID changed and its serializable fields changed";
    private static final String SUPERCLASS =
            "its default serialVersionUID changed and its serializable superclass changed";
    private static final String INTERFACES = "its default serialVersionUID changed and its interfaces changed";
    private static final String SHAPE = "its default serialVersionUID changed and its shape could not be read";

    private static final String METHOD = "public int added() { return 1; }";
    private static final String WRITE_OBJECT = "private void writeObject(java.io.ObjectOutputStream out)"
            + " throws java.io.IOException { out.defaultWriteObject(); }";

    private static final String VISITS = """
            package swap;

            public class Visits implements java.io.Serializable {
                private int count;
                private String last;

                public Visits(int count, String last) {
                    this.count = count;
                    this.last = last;
                }

                public static Object sample() {
                    return new Visits(3, "home");
                }

                @Override
                public String toString() {
                    return count + " " + last;
                }
                // more
            }
            """;
    private static final String VISITS_NAME = "swap.Visits";
    private static final String SERIALIZABLE = "implements java.io.Serializable";
    private static final String SUB_NAME = "swap.Sub";
    private static final String ITEM_NAME = "swap.Item";
    private static final String NOTE_NAME = "swap.Note";

    /** Holds a {@code Visits} in a field and in a list. */
    private static final String CART = """
            package swap;

            public class Cart implements java.io.Serializable {
                private final Visits visits = new Visits(4, "cart");
                private final java.util.List<Visits> history = new java.util.ArrayList<>(
                        java.util.List.of(new Visits(1, "a"), new Visits(2, "b")));

                public static Object sample() {
                    return new Cart();
                }

                @Override
                public String toString() {
                    return visits + " " + history;
                }
            }
            """;

    private static final String BASE = """
            package swap;

            public class Base implements java.io.Serializable {
                protected int count = 3;
            }
            """;
    private static final String SUB = """
            package swap;

            public class Sub extends Base {
                private String last = "home";

                public static Object sample() {
                    return new Sub();
                }

                @Override
                public String toString() {
                    return count + " " + last;
                }
                // more
            }
            """;

    private static final String FIRST = """
            package swap;

            public class First implements java.io.Serializable {
            }
            """;
    private static final String SECOND = """
            package swap;

            public class Second implements java.io.Serializable {
            }
            """;
    private static final String ITEM = """
            package swap;

            public class Item extends First {
                private String name = "pen";

                public static Object sample() {
                    return new Item();
                }
                // more
            }
            """;

    private static final String NOTE = """
            package swap;

            public class Note implements java.io.Externalizable {
                private String text = "kept";

                public Note() {
                }

                public static Object sample() {
                    return new Note();
                }

                @Override
                public void writeExternal(java.io.ObjectOutput out) throws java.io.IOException {
                    out.writeUTF(text);
                }

                @Override
                public void readExternal(java.io.ObjectInput in) throws java.io.IOException {
                    text = in.readUTF();
                }
                // more
            }
            """;

    private static final String LEFT = """
            package swap;

            public interface Left extends java.io.Serializable {
            }
            """;
    private static final String RIGHT = """
            package swap;

            public interface Right extends java.io.Serializable {
            }
            """;
    /** Two anonymous classes, which javac numbers in the order of the source: {@code Sides$1} is the first. */
    private static final String SIDES = """
            package swap;

            public class Sides {
                %s
                %s

                public static Object sample() {
                    return LEFT;
                }
            }
            """;
    private static final String LEFT_SIDE =
            "private static final Left LEFT = new Left() { public String toString() { return \"left\"; } };";
    private static final String RIGHT_SIDE =
            "private static final Right RIGHT = new Right() { public String toString() { return \"right\"; } };";
    private static final String FIRST_SIDE = "swap.Sides$1";

    /** A class with a method whose parameter type is compiled with it, then deleted. */
    private static final String HOLDER = """
            package swap;

            public class Holder implements java.io.Serializable {
                public void use(Gone gone) {
                }
            }
            """;
    private static final String GONE = """
            package swap;

            public class Gone {
            }
            """;

    private static final String COLOR = """
            package swap;

            public enum Color {
                RED, GREEN;

                public static Object sample() {
                    return GREEN;
                }
                // more
            }
            """;

    private static final String POINT = """
            package swap;

            public record Point(int x, int y) implements java.io.Serializable {
                public static Object sample() {
                    return new Point(1, 2);
                }
                // more
            }
            """;

    @TempDir
    Path tmp;
    private final List<SessionManager> managers = new ArrayList<>();
    private final List<URLClassLoader> loaders = new ArrayList<>();
    private int compilations;

    @AfterEach
    void close() throws IOException {
        managers.forEach(SessionManager::close);
        for (URLClassLoader loader : loaders) loader.close();
    }

    // ---- kept: the serialized shape is the same ----

    @Test
    void aClassWithoutSerialVersionUidThatGainsAPublicMethodKeepsItsSession() throws Exception {
        var versions = versions(VISITS, with(VISITS, METHOD));
        assertDefaultUidChanged(versions, VISITS_NAME);

        assertKept(move(versions, VISITS_NAME), versions.after());
    }

    @Test
    void aClassWithoutSerialVersionUidThatGainsAConstructorKeepsItsSession() throws Exception {
        var versions = versions(VISITS, with(VISITS, "public Visits() { this(0, \"none\"); }"));
        assertDefaultUidChanged(versions, VISITS_NAME);

        assertKept(move(versions, VISITS_NAME), versions.after());
    }

    @Test
    void anObjectFieldOfSuchAClassKeepsItsSession() throws Exception {
        var versions = versions(List.of(CART, VISITS), List.of(CART, with(VISITS, METHOD)));
        assertDefaultUidChanged(versions, VISITS_NAME);

        Moved moved = move(versions, "swap.Cart");

        assertKept(moved, versions.after());
        assertEquals("4 cart [1 a, 2 b]", moved.value().toString());
    }

    @Test
    void aSubclassThatGainsAMethodOverAnUnchangedSuperclassKeepsItsSession() throws Exception {
        var versions = versions(List.of(BASE, SUB), List.of(BASE, with(SUB, METHOD)));
        assertDefaultUidChanged(versions, SUB_NAME);

        Moved moved = move(versions, SUB_NAME);

        assertKept(moved, versions.after());
        assertEquals("3 home", moved.value().toString(), "the superclass's field moved too");
    }

    // ---- dropped: the session stays behind, and the line says why ----

    @Test
    void aFieldAddedDropsTheSession() throws Exception {
        var versions = versions(VISITS, with(VISITS, "private int extra;"));
        assertDefaultUidChanged(versions, VISITS_NAME);

        assertDropped(move(versions, VISITS_NAME), VISITS_NAME, FIELDS);
    }

    @Test
    void aFieldWhoseTypeChangedDropsTheSession() throws Exception {
        var versions = versions(VISITS, VISITS.replace("private int count;", "private long count;"));
        assertDefaultUidChanged(versions, VISITS_NAME);

        assertDropped(move(versions, VISITS_NAME), VISITS_NAME, FIELDS);
    }

    @Test
    void aWriteObjectAddedDropsTheSession() throws Exception {
        var versions = versions(VISITS, with(VISITS, WRITE_OBJECT, METHOD));
        assertDefaultUidChanged(versions, VISITS_NAME);

        assertDropped(move(versions, VISITS_NAME), VISITS_NAME, WRITES_OBJECT);
    }

    @Test
    void aWriteObjectOnBothSidesDropsTheSession() throws Exception {
        var versions = versions(with(VISITS, WRITE_OBJECT), with(VISITS, WRITE_OBJECT, METHOD));
        assertDefaultUidChanged(versions, VISITS_NAME);

        assertDropped(move(versions, VISITS_NAME), VISITS_NAME, WRITES_OBJECT);
    }

    @Test
    void anExternalizableClassDropsTheSession() throws Exception {
        var versions = versions(NOTE, with(NOTE, METHOD));
        assertDefaultUidChanged(versions, NOTE_NAME);

        assertDropped(move(versions, NOTE_NAME), NOTE_NAME, EXTERNALIZABLE);
    }

    @Test
    void aDeclaredSerialVersionUidThatChangedDropsTheSession() throws Exception {
        var versions = versions(with(VISITS, uid(1)), with(VISITS, uid(2)));

        assertDropped(move(versions, VISITS_NAME), VISITS_NAME, DECLARED);
    }

    @Test
    void aSerialVersionUidDeclaredWithAnotherValueThanTheOldDefaultDropsTheSession() throws Exception {
        var versions = versions(VISITS, with(VISITS, uid(42)));
        assertDefaultUidChanged(versions, VISITS_NAME);

        assertDropped(move(versions, VISITS_NAME), VISITS_NAME, DECLARED);
    }

    @Test
    void aSerialVersionUidDeclarationRemovedDropsTheSession() throws Exception {
        var versions = versions(with(VISITS, uid(42)), VISITS);
        assertDefaultUidChanged(versions, VISITS_NAME);

        assertDropped(move(versions, VISITS_NAME), VISITS_NAME, DECLARED);
    }

    @Test
    void aSerializableSuperclassThatChangedDropsTheSession() throws Exception {
        var versions = versions(List.of(FIRST, SECOND, ITEM),
                List.of(FIRST, SECOND, with(ITEM, METHOD).replace("extends First", "extends Second")));
        assertDefaultUidChanged(versions, ITEM_NAME);

        assertDropped(move(versions, ITEM_NAME), ITEM_NAME, SUPERCLASS);
    }

    @Test
    void anInterfaceAddedDropsTheSession() throws Exception {
        var versions = versions(VISITS, VISITS.replace(SERIALIZABLE, SERIALIZABLE + ", Cloneable"));
        assertDefaultUidChanged(versions, VISITS_NAME);

        assertDropped(move(versions, VISITS_NAME), VISITS_NAME, INTERFACES);
    }

    @Test
    void anInterfaceReplacedDropsTheSession() throws Exception {
        var versions = versions(VISITS.replace(SERIALIZABLE, SERIALIZABLE + ", Cloneable"),
                VISITS.replace(SERIALIZABLE, SERIALIZABLE + ", java.util.RandomAccess"));
        assertDefaultUidChanged(versions, VISITS_NAME);

        assertDropped(move(versions, VISITS_NAME), VISITS_NAME, INTERFACES);
    }

    @Test
    void twoAnonymousClassesWhoseNumbersSwappedDropTheSession() throws Exception {
        var versions = versions(List.of(LEFT, RIGHT, SIDES.formatted(LEFT_SIDE, RIGHT_SIDE)),
                List.of(LEFT, RIGHT, SIDES.formatted(RIGHT_SIDE, LEFT_SIDE)));
        assertDefaultUidChanged(versions, FIRST_SIDE);
        // The same name, no field on either side: the new Sides$1 is the right side, the old one the left.
        assertEquals("swap.Right", versions.after().loadClass(FIRST_SIDE).getInterfaces()[0].getName());

        assertDropped(move(versions, "swap.Sides"), FIRST_SIDE, INTERFACES);
    }

    @Test
    void aWriteObjectOfTheLoadedClassDropsTheSessionWhateverItsClassFileSays() throws Exception {
        var versions = versions(VISITS, with(VISITS, WRITE_OBJECT, METHOD));
        assertDefaultUidChanged(versions, VISITS_NAME);
        // The new layer defines its class with a writeObject, and serves a class file that has none.
        var newLayer = new ClassFilesFrom(versions.afterClasses(), compile(List.of(with(VISITS, METHOD))));
        loaders.add(newLayer);

        assertDropped(move(versions.before(), newLayer, VISITS_NAME), VISITS_NAME, WRITES_OBJECT);
    }

    // ---- an unknown shape: the class is never read with the new descriptor ----

    @Test
    void aClassOfTheJdkHasAnUnknownShape() {
        assertSame(ClassLoader.getPlatformClassLoader(), Diagnostic.Kind.class.getClassLoader());

        assertFalse(SerialShape.of(String.class).known(), "a class of the bootstrap loader");
        assertFalse(SerialShape.of(Diagnostic.Kind.class).known(), "a class of the platform loader");
    }

    @Test
    void aClassWhoseMembersCannotBeListedHasAnUnknownShapeAndIsNeverReadWithTheNewDescriptor() throws Exception {
        Path classes = compile(List.of(HOLDER, GONE));
        Files.delete(classes.resolve("swap/Gone.class"));
        Class<?> holder = loader(classes).loadClass("swap.Holder");
        assertThrows(NoClassDefFoundError.class, holder::getDeclaredMethods);

        SerialShape shape = SerialShape.of(holder);

        assertFalse(shape.known());
        var known = new SerialShape(false, false, false, "", shape.interfaces(), true);
        ObjectStreamField[] none = {};
        assertEquals(Optional.of(SHAPE), SerialShape.refusal(shape, known, none, none), "exported unknown");
        assertEquals(Optional.of(SHAPE), SerialShape.refusal(known, shape, none, none), "local unknown");
    }

    // ---- compiled in place: the old layer's loader serves the new class files, as in Vidocq's dev swap ----

    @Test
    void aSerialVersionUidDeclarationRemovedInPlaceDropsTheSession() throws Exception {
        var versions = versions(with(VISITS, uid(42)), VISITS);
        var oldLayer = compiledInPlace(versions);

        assertDropped(move(oldLayer, versions.after(), VISITS_NAME), VISITS_NAME, DECLARED);
    }

    @Test
    void aWriteObjectRemovedInPlaceDropsTheSession() throws Exception {
        var versions = versions(with(VISITS, "private void writeObject(java.io.ObjectOutputStream out)"
                + " throws java.io.IOException { out.defaultWriteObject(); out.writeInt(7); }"), with(VISITS, METHOD));
        assertDefaultUidChanged(versions, VISITS_NAME);
        var oldLayer = compiledInPlace(versions);

        assertDropped(move(oldLayer, versions.after(), VISITS_NAME), VISITS_NAME, WRITES_OBJECT);
    }

    @Test
    void aClassThatGainsAMethodInPlaceKeepsItsSession() throws Exception {
        var versions = versions(VISITS, with(VISITS, METHOD));
        var oldLayer = compiledInPlace(versions);

        assertKept(move(oldLayer, versions.after(), VISITS_NAME), versions.after());
    }

    // ---- unchanged: plain Java decides, as before ----

    @Test
    void equalDeclaredSerialVersionUidsWithAFieldAddedReadAsPlainJavaDoes() throws Exception {
        var versions = versions(with(VISITS, uid(7)), with(VISITS, uid(7),
                "private String added = \"initialized\";", "public String added() { return added; }"));

        Moved moved = move(versions, VISITS_NAME);

        assertKept(moved, versions.after());
        assertNull(moved.value().getClass().getMethod("added").invoke(moved.value()),
                "the field added has its default value, as plain Java gives it");
    }

    @Test
    void anEnumThatGainsAMethodAndAConstantReadsAsBefore() throws Exception {
        var versions = versions(COLOR, with(COLOR.replace("RED, GREEN;", "RED, GREEN, BLUE;"), METHOD));

        assertKept(move(versions, "swap.Color"), versions.after());
    }

    @Test
    void aRecordThatGainsAComponentAndAMethodReadsAsBefore() throws Exception {
        var versions = versions(POINT, with(POINT.replace("(int x, int y)", "(int x, int y, int z)")
                .replace("new Point(1, 2)", "new Point(1, 2, 3)"), METHOD));

        Moved moved = move(versions, "swap.Point");

        assertEquals(ONE, moved.result(), moved.warnings()::toString);
        assertEquals("Point[x=1, y=2, z=0]", moved.value().toString(), "the component added has its default value");
    }

    @Test
    void anArrayOfSuchAClassReadsAsBeforeAndItsElementsMove() throws Exception {
        var versions = versions(VISITS, with(VISITS, METHOD));
        Class<?> before = versions.before().loadClass(VISITS_NAME);
        Object array = Array.newInstance(before, 2);
        Array.set(array, 0, before.getMethod("sample").invoke(null));
        Array.set(array, 1, before.getMethod("sample").invoke(null));

        Moved moved = move(array, versions.after());

        assertEquals(ONE, moved.result(), moved.warnings()::toString);
        assertSame(versions.after().loadClass(VISITS_NAME).arrayType(), moved.value().getClass());
        assertEquals("[3 home, 3 home]", Arrays.toString((Object[]) moved.value()));
    }

    // ---- helpers ----

    /** The two versions of a swap: the classes and the loader of each. */
    private record Versions(URLClassLoader before, URLClassLoader after, Path beforeClasses, Path afterClasses) {
    }

    /** What an import of one session made of its value: the count, the WARNING lines and the value read. */
    private record Moved(Object before, SessionImport result, List<String> warnings, Object value) {
    }

    private Versions versions(String before, String after) throws IOException {
        return versions(List.of(before), List.of(after));
    }

    private Versions versions(List<String> before, List<String> after) throws IOException {
        Path beforeClasses = compile(before);
        Path afterClasses = compile(after);
        return new Versions(loader(beforeClasses), loader(afterClasses), beforeClasses, afterClasses);
    }

    /** Puts the {@code sample()} of the old version of {@code className} in a session, and moves the session. */
    private Moved move(Versions versions, String className) throws ReflectiveOperationException {
        return move(versions.before(), versions.after(), className);
    }

    private Moved move(ClassLoader before, ClassLoader after, String className) throws ReflectiveOperationException {
        return move(before.loadClass(className).getMethod("sample").invoke(null), after);
    }

    /** Exports a session that holds {@code value}, and imports it through {@code after}. */
    private Moved move(Object value, ClassLoader after) {
        var source = manager();
        var session = source.createNew();
        session.setAttribute(VALUE, value);
        byte[] export = source.closeExporting();

        var target = manager();
        try (var log = LogCapture.of(SessionManager.class.getName())) {
            SessionImport result = target.importSessions(export, after);
            Object moved = target.store().get(session.getId()).map(s -> s.getAttribute(VALUE)).orElse(null);
            return new Moved(value, result, log.warnings(), moved);
        }
    }

    private static void assertKept(Moved moved, ClassLoader after) {
        assertEquals(ONE, moved.result(), moved.warnings()::toString);
        assertSame(after, moved.value().getClass().getClassLoader(), "the value is of the new version's class");
        assertNotSame(moved.before().getClass(), moved.value().getClass());
        assertEquals(moved.before().toString(), moved.value().toString(), "the field values moved");
    }

    private static void assertDropped(Moved moved, String className, String reason) {
        assertEquals(DROPPED, moved.result(), moved.warnings()::toString);
        assertEquals(1, moved.warnings().size(), moved.warnings()::toString);
        String expected = "first cause: class " + className + ": " + reason;
        assertTrue(moved.warnings().getFirst().endsWith(expected), () -> moved.warnings().getFirst()
                + "\n does not end with\n" + expected);
    }

    /** Guards the scenario: plain Java would refuse it, the two versions having two default UIDs. */
    private static void assertDefaultUidChanged(Versions versions, String className) throws ClassNotFoundException {
        assertNotEquals(uid(versions.before(), className), uid(versions.after(), className));
    }

    private static long uid(ClassLoader loader, String className) throws ClassNotFoundException {
        return ObjectStreamClass.lookup(loader.loadClass(className)).getSerialVersionUID();
    }

    private static String uid(long value) {
        return "private static final long serialVersionUID = " + value + "L;";
    }

    /** {@code source} with {@code members} added. */
    private static String with(String source, String... members) {
        return source.replace(MORE, String.join("\n", members));
    }

    private SessionManager manager() {
        var manager = new SessionManager(new InMemorySessionStore(), new VidocqServletContext("/"), 1800);
        managers.add(manager);
        return manager;
    }

    /** Compiles {@code sources} into a directory of their own. */
    private Path compile(List<String> sources) throws IOException {
        int n = compilations++;
        Path src = Files.createDirectories(tmp.resolve("src" + n));
        Path out = Files.createDirectories(tmp.resolve("classes" + n));
        List<String> args = new ArrayList<>(List.of("-proc:none", "-d", out.toString()));
        for (String source : sources) {
            Matcher name = TYPE_NAME.matcher(source);
            assertTrue(name.find(), source);
            Path file = src.resolve(name.group(1) + ".java");
            Files.writeString(file, source);
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)),
                "compilation failed");
        return out;
    }

    /**
     * The old version's classes behind a loader that reads its class files from the new version's directory, as a
     * Vauban layer loader reads them from a directory the dev loop compiled again before the swap.
     */
    private ClassFilesFrom compiledInPlace(Versions versions) throws MalformedURLException {
        var oldLayer = new ClassFilesFrom(versions.beforeClasses(), versions.afterClasses());
        loaders.add(oldLayer);
        return oldLayer;
    }

    private URLClassLoader loader(Path classes) throws MalformedURLException {
        var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader());
        loaders.add(loader);
        return loader;
    }

    /**
     * A loader that defines the classes of one directory but serves the class files of another: the old layer of a
     * swap whose classes were compiled again in place, or any loader whose class files are not the bytes it defined.
     */
    private static final class ClassFilesFrom extends URLClassLoader {
        private final Path classFiles;

        ClassFilesFrom(Path classes, Path classFiles) throws MalformedURLException {
            super(new URL[] {classes.toUri().toURL()}, ClassFilesFrom.class.getClassLoader());
            this.classFiles = classFiles;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (!name.endsWith(".class")) return super.getResourceAsStream(name);
            if (!Files.isRegularFile(classFiles.resolve(name))) return null;
            try {
                return Files.newInputStream(classFiles.resolve(name));
            } catch (IOException _) {
                return null;
            }
        }
    }
}
