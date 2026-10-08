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
package io.vidocq.foy.internal;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Captures the java.util.logging records of one logger (the default System.Logger backend). */
public final class LogCapture implements AutoCloseable {

    /** Holds every java.util.logging reference, so it is linked only after the read edge exists. */
    private static final class Jul {
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();
        private final Logger logger;
        private final Handler handler = new Handler() {
            @Override public void publish(LogRecord r) { records.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        };

        Jul(String name) {
            logger = Logger.getLogger(name);
            logger.addHandler(handler);
        }

        List<String> warnings() {
            return records.stream().filter(r -> r.getLevel() == Level.WARNING)
                    .map(r -> r.getParameters() == null ? r.getMessage()
                            : java.text.MessageFormat.format(r.getMessage(), r.getParameters()))
                    .toList();
        }

        void close() {
            logger.removeHandler(handler);
        }
    }

    private final Jul jul;

    private LogCapture(Jul jul) {
        this.jul = jul;
    }

    /**
     * Starts capturing {@code loggerName}. foy-core does not read java.logging; the tests are
     * patched into its module, so the read edge is added before any java.util.logging type is linked.
     */
    public static LogCapture of(String loggerName) {
        LogCapture.class.getModule().addReads(ModuleLayer.boot().findModule("java.logging").orElseThrow());
        return new LogCapture(new Jul(loggerName));
    }

    /** @return the formatted messages logged at WARNING */
    public List<String> warnings() {
        return jul.warnings();
    }

    @Override
    public void close() {
        jul.close();
    }
}
