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

import io.vidocq.foy.internal.boot.HandlesTypesResolver;
import jakarta.servlet.ServletContainerInitializer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.System.Logger.Level;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves the class set passed to a {@link ServletContainerInitializer} (§8.2.4) from the
 * build-time class index ({@code META-INF/foy/class-index.list}, one line per class:
 * {@code binaryName|supertypes|annotations}, all binary names).
 *
 * <p>A class matches when one of its supertypes or type-level annotations is among the
 * initializer's {@code @HandlesTypes}; the handled types themselves are excluded. The
 * index is read lazily, once per resolver instance.
 */
public final class IndexedHandlesTypesResolver implements HandlesTypesResolver {

    private static final System.Logger LOG = System.getLogger(IndexedHandlesTypesResolver.class.getName());
    private static final String INDEX_RESOURCE = "META-INF/foy/class-index.list";

    private record IndexEntry(String name, Set<String> related) {}

    private final WebComponentRegistry registry;
    private final ClassLoader loader;
    private volatile List<IndexEntry> entries;

    public IndexedHandlesTypesResolver(WebComponentRegistry registry, ClassLoader loader) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    @Override
    public Set<Class<?>> resolve(ServletContainerInitializer sci) {
        List<String> handled = registry.lookup(sci.getClass()).descriptor().handlesTypes();
        if (handled.isEmpty()) {
            return null;
        }
        Set<String> handledSet = Set.copyOf(handled);
        Set<Class<?>> result = new HashSet<>();
        for (IndexEntry entry : entries()) {
            if (handledSet.contains(entry.name()) || Collections.disjoint(entry.related(), handledSet)) {
                continue;
            }
            try {
                result.add(Class.forName(entry.name(), false, loader));
            } catch (ClassNotFoundException | LinkageError e) {
                LOG.log(Level.DEBUG, "Skipping indexed class {0}: not loadable ({1})", entry.name(), e.toString());
            }
        }
        return result;
    }

    private List<IndexEntry> entries() {
        List<IndexEntry> local = entries;
        if (local == null) {
            synchronized (this) {
                local = entries;
                if (local == null) {
                    local = readIndex();
                    entries = local;
                }
            }
        }
        return local;
    }

    private List<IndexEntry> readIndex() {
        Map<String, IndexEntry> byName = new LinkedHashMap<>();
        try {
            var urls = loader.getResources(INDEX_RESOURCE);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        parse(line, url, byName);
                    }
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Cannot read class index {0}: {1}", url, e.toString());
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot enumerate class indexes: {0}", e.toString());
        }
        return List.copyOf(byName.values());
    }

    private static void parse(String line, URL url, Map<String, IndexEntry> byName) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }
        String[] parts = line.split("\\|", -1);
        if (parts.length < 3 || parts[0].isBlank()) {
            LOG.log(Level.DEBUG, "Skipping malformed class index line in {0}: {1}", url, line);
            return;
        }
        String name = parts[0].strip();
        Set<String> related = new HashSet<>();
        for (int i = 1; i <= 2; i++) {
            Arrays.stream(parts[i].split(",")).map(String::strip).filter(s -> !s.isEmpty()).forEach(related::add);
        }
        byName.putIfAbsent(name, new IndexEntry(name, related));
    }
}
