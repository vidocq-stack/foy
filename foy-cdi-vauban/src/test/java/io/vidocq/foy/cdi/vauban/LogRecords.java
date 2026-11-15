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
package io.vidocq.foy.cdi.vauban;

import java.text.MessageFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The records one logger receives while open, through {@code java.util.logging}, the backend of
 * {@code System.Logger} when {@code java.logging} is in the boot layer (the surefire configuration adds it).
 */
final class LogRecords implements AutoCloseable {

    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Logger logger;
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            records.add(logRecord);
        }

        @Override
        public void flush() {
            // Nothing is buffered.
        }

        @Override
        public void close() {
            // The records stay readable after the handler is removed.
        }
    };

    private LogRecords(String loggerName) {
        logger = Logger.getLogger(loggerName);
        logger.addHandler(handler);
    }

    /** Starts recording what {@code loggerName} logs. */
    static LogRecords of(String loggerName) {
        return new LogRecords(loggerName);
    }

    /** The formatted messages logged at {@code WARNING} or above. */
    List<String> warnings() {
        return records.stream().filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                .map(LogRecords::message).toList();
    }

    private static String message(LogRecord logRecord) {
        var parameters = logRecord.getParameters();
        return parameters == null || parameters.length == 0
                ? logRecord.getMessage() : MessageFormat.format(logRecord.getMessage(), parameters);
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
    }
}
