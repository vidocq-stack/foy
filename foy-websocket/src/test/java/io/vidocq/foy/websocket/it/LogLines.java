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
package io.vidocq.foy.websocket.it;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Collects the records of one level of a logger and its children, the logger opened to that level meanwhile
 * ({@code DEBUG} is {@code FINE}). The records it captures are the test's to assert: they stay off the console.
 */
final class LogLines extends Handler implements AutoCloseable {

    /** The loggers of {@code foy-websocket}. */
    static final String FOY_WEBSOCKET = "io.vidocq.foy.websocket";
    /** The logger of Chappe's WebSocket connection, which logs the failure of a callback at WARNING. */
    static final String CHAPPE_CONNECTION = "io.vidocq.chappe.http.ws.WebSocketConnection";

    private final Logger logger;
    private final Level previous;
    private final boolean useParentHandlers;
    private final Level level;
    private final List<String> messages = new CopyOnWriteArrayList<>();

    LogLines(String loggerName, Level level) {
        this.logger = Logger.getLogger(loggerName);
        this.previous = logger.getLevel();
        this.useParentHandlers = logger.getUseParentHandlers();
        this.level = level;
        logger.setLevel(level);
        logger.setUseParentHandlers(false);
        logger.addHandler(this);
    }

    /** The formatted messages of the records of the level, in order. */
    List<String> messages() {
        return messages;
    }

    @Override
    public void publish(LogRecord logRecord) {
        if (level.equals(logRecord.getLevel())) {
            messages.add(new SimpleFormatter().formatMessage(logRecord));
        }
    }

    @Override
    public void flush() {
        // nothing buffered
    }

    @Override
    public void close() {
        logger.removeHandler(this);
        logger.setUseParentHandlers(useParentHandlers);
        logger.setLevel(previous);
    }
}
