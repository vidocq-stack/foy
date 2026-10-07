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

import java.util.Set;

/**
 * Loads and instantiates web components. Phase 1: reflective; Phase 2 replaces
 * the default with the generated-code registry (APT → Class-File → reflection).
 */
public interface ComponentFactory {
	Class<?> load(String className) throws ClassNotFoundException;

	<T> T newInstance(Class<T> type) throws ServletException;

	/**
	 * False for classes the deployment must ignore (TCK war isolation).
	 */
	default boolean isVisible(Class<?> type) {
		return true;
	}

	static ComponentFactory reflective(ClassLoader loader) {
		return new Reflective(loader, null);
	}

	static ComponentFactory reflective(ClassLoader loader, Set<String> visibleClassNames) {
		return new Reflective(loader, visibleClassNames);
	}

	/**
	 * Reflective implementation of ComponentFactory.
	 */
	record Reflective(ClassLoader loader, Set<String> visibleClassNames) implements ComponentFactory {
		@Override
		public Class<?> load(String className) throws ClassNotFoundException {
			return Class.forName(className, true, loader);
		}

		@Override
		public <T> T newInstance(Class<T> type) throws ServletException {
			try {
				return type.getDeclaredConstructor().newInstance();
			} catch (ReflectiveOperationException e) {
				throw new ServletException("cannot instantiate " + type.getName(), e);
			}
		}

		@Override
		public boolean isVisible(Class<?> type) {
			if (visibleClassNames == null) {
				return true;
			}
			return visibleClassNames.contains(type.getName());
		}
	}
}
