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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ComponentFactoryTest {

	public static final class Ok extends HttpServlet {}
	public static final class Boom extends HttpServlet {
		public Boom() { throw new IllegalStateException("nope"); }
	}

	private final ComponentFactory f = ComponentFactory.reflective(getClass().getClassLoader());

	@Test
	void loadsAndInstantiates() throws Exception {
		Class<?> c = f.load(Ok.class.getName());
		assertInstanceOf(Ok.class, f.newInstance(c));
	}

	public static final class NonPublicCtor extends HttpServlet {
		NonPublicCtor() {}
	}

	@Test
	void instantiatesThroughANonPublicNoArgConstructor() throws Exception {
		assertInstanceOf(NonPublicCtor.class, f.newInstance(NonPublicCtor.class));
	}

	@Test
	void constructorFailureBecomesServletException() {
		var e = assertThrows(ServletException.class, () -> f.newInstance(Boom.class));
		assertTrue(e.getMessage().contains(Boom.class.getName()));
	}

	@Test
	void visibilityRestrictsToTheGivenNames() {
		var restricted = ComponentFactory.reflective(getClass().getClassLoader(), Set.of(Ok.class.getName()));
		assertTrue(restricted.isVisible(Ok.class));
		assertFalse(restricted.isVisible(Boom.class));
		assertTrue(f.isVisible(Boom.class));
	}

	@Test
	void visibleNamesAreCopiedAtCreation() {
		var names = new HashSet<>(Set.of(Ok.class.getName()));
		var restricted = ComponentFactory.reflective(getClass().getClassLoader(), names);
		names.add(Boom.class.getName());
		names.remove(Ok.class.getName());
		assertTrue(restricted.isVisible(Ok.class), "later removal by the caller has no effect");
		assertFalse(restricted.isVisible(Boom.class), "later addition by the caller has no effect");
	}

	static void fail() { throw new IllegalStateException("static init failed"); }

	/** Static initializer failure when loaded (initialized) by name. */
	public static final class BadInitOnLoad extends HttpServlet {
		static { fail(); }
	}

	/** Static initializer failure when instantiated. */
	public static final class BadInitOnNew extends HttpServlet {
		static { fail(); }
	}

	@Test
	void linkageErrorWhileLoadingBecomesServletException() {
		var e = assertThrows(ServletException.class, () -> f.load(BadInitOnLoad.class.getName()));
		assertInstanceOf(ExceptionInInitializerError.class, e.getCause());
		assertTrue(e.getMessage().contains(BadInitOnLoad.class.getName()), e.getMessage());
	}

	@Test
	void linkageErrorWhileInstantiatingBecomesServletException() {
		var e = assertThrows(ServletException.class, () -> f.newInstance(BadInitOnNew.class));
		assertInstanceOf(LinkageError.class, e.getCause());
		assertTrue(e.getMessage().contains(BadInitOnNew.class.getName()), e.getMessage());
	}

	@Test
	void noneResolverYieldsNull() {
		assertNull(HandlesTypesResolver.NONE.resolve((c, ctx) -> {}));
	}
}
