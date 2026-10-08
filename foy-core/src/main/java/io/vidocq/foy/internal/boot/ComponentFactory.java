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

import io.vidocq.foy.internal.gen.ClassFileDescriptorReader;
import io.vidocq.foy.spi.gen.WebComponentDescriptor;
import jakarta.servlet.ServletException;

import java.util.Set;

/**
 * Loads and instantiates web components. The product default is the registry-backed
 * {@code RegistryComponentFactory} (generated, Class-File, then reflective tier);
 * {@link #reflective(ClassLoader)} stays for tests and as an explicit opt-out.
 */
public interface ComponentFactory {
	/**
	 * Loads and initializes {@code className}.
	 *
	 * @throws ClassNotFoundException when the class does not exist
	 * @throws ServletException when it exists but cannot be linked or initialized ({@link LinkageError})
	 */
	Class<?> load(String className) throws ClassNotFoundException, ServletException;

	<T> T newInstance(Class<T> type) throws ServletException;

	/**
	 * False for dynamically registered classes the deployment must ignore (TCK war isolation).
	 */
	default boolean isVisible(Class<?> type) {
		return true;
	}

	/**
	 * Static metadata of {@code type} (mapping, security, ...). The default reads the class
	 * bytes; an unreadable or unannotated class gets a plain descriptor.
	 *
	 * @throws IllegalArgumentException when the class misuses the Servlet annotations
	 */
	default WebComponentDescriptor descriptor(Class<?> type) {
		return ClassFileDescriptorReader.read(type).orElseGet(WebComponentDescriptor::plain);
	}

	static ComponentFactory reflective(ClassLoader loader) {
		return new Reflective(loader, null);
	}

	static ComponentFactory reflective(ClassLoader loader, Set<String> visibleClassNames) {
		return new Reflective(loader, visibleClassNames);
	}

	/**
	 * Reflective implementation of ComponentFactory. The visible names are copied: later changes
	 * to the caller's set have no effect.
	 */
	record Reflective(ClassLoader loader, Set<String> visibleClassNames) implements ComponentFactory {
		public Reflective {
			visibleClassNames = visibleClassNames == null ? null : Set.copyOf(visibleClassNames);
		}

		@Override
		public Class<?> load(String className) throws ClassNotFoundException, ServletException {
			try {
				return Class.forName(className, true, loader);
			} catch (LinkageError e) {
				throw new ServletException("cannot load " + className + ": " + e, e);
			}
		}

		@Override
		public <T> T newInstance(Class<T> type) throws ServletException {
			try {
				return type.getDeclaredConstructor().newInstance();
			} catch (ReflectiveOperationException | LinkageError e) {
				throw new ServletException("cannot instantiate " + type.getName() + ": " + e, e);
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
