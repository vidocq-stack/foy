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
package io.vidocq.foy.internal.bridge;

import io.vidocq.chappe.api.Response;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * The once-only handoff of a response head from the servlet pipeline thread to chappe's thread.
 *
 * <p>chappe's thread parks in {@link #await()} while the pipeline runs on its own virtual thread.
 * The head is completed exactly once: at the first real commit (a streamed body follows through a
 * {@link ResponsePipe}), or at the end of the pipeline for a response that was never committed
 * (the whole buffered body), or failed when the pipeline throws before any commit. Later attempts
 * are ignored and report {@code false}.</p>
 */
final class ResponseHead {

    private final CompletableFuture<Response> future = new CompletableFuture<>();

    /** Hands {@code response} to chappe; {@code false} when the head was already settled or abandoned. */
    boolean complete(Response response) {
        return future.complete(response);
    }

    /** Makes {@link #await()} throw {@code cause}; {@code false} when the head was already settled. */
    boolean fail(Throwable cause) {
        return future.completeExceptionally(cause);
    }

    boolean isDone() {
        return future.isDone();
    }

    /**
     * Blocks chappe's thread until the head is settled. An interrupt (server stop) abandons the
     * head, so a commit arriving later sees {@link #complete} return {@code false} and fails the
     * servlet's writes instead of feeding a pipe nobody reads.
     */
    Response await() throws Exception {
        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(false);
            throw e;
        } catch (CancellationException e) {
            throw new InterruptedException("response head abandoned");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw e;
        }
    }
}
