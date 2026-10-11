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
package io.vidocq.foy.internal.http;

/**
 * Container ceilings on what a request may make Foy read or store, chosen by the embedder
 * ({@code FoyChappeBoot.Builder}, {@code DeployOptions}). {@code -1} lifts a size ceiling.
 *
 * @param maxChunkedRequestBodySize  bytes Foy reads from a body that declares no Content-Length
 *                                   (HTTP/1.1 chunked, HTTP/2 without content-length) before the read
 *                                   fails with {@link RequestBodyTooLargeException}; a body with a
 *                                   Content-Length is bounded by chappe ({@code ServerConfig.maxRequestSize})
 * @param maxFormBodySize            bytes of an {@code application/x-www-form-urlencoded} body parsed into
 *                                   parameters; beyond, the first {@code getParameter*} call throws
 *                                   {@link IllegalStateException} and later calls see the query-string
 *                                   parameters only
 * @param maxMultipartParts          parts in one {@code multipart/form-data} body; beyond, the parse fails
 *                                   with {@link MultipartLimitException}
 * @param maxMultipartPartHeaderSize bytes of the header section of one part (CRLFs included); beyond,
 *                                   the parse fails with {@link MultipartLimitException}
 */
public record RequestLimits(long maxChunkedRequestBodySize, long maxFormBodySize, int maxMultipartParts,
                            int maxMultipartPartHeaderSize) {

    /** 10 MiB (chappe's Content-Length default), 2 MiB, 50 parts, 8 KiB of headers per part. */
    public static final RequestLimits DEFAULTS =
            new RequestLimits(10L * 1024 * 1024, 2L * 1024 * 1024, 50, 8 * 1024);

    public RequestLimits {
        checkSize("maxChunkedRequestBodySize", maxChunkedRequestBodySize);
        checkSize("maxFormBodySize", maxFormBodySize);
        if (maxMultipartParts != -1 && maxMultipartParts < 1) {
            throw new IllegalArgumentException("maxMultipartParts must be -1 or at least 1: " + maxMultipartParts);
        }
        if (maxMultipartPartHeaderSize < 1) {
            throw new IllegalArgumentException("maxMultipartPartHeaderSize must be at least 1: "
                    + maxMultipartPartHeaderSize);
        }
    }

    private static void checkSize(String name, long bytes) {
        if (bytes < -1) throw new IllegalArgumentException(name + " must be -1 or a byte count: " + bytes);
    }

    public RequestLimits withMaxChunkedRequestBodySize(long bytes) {
        return new RequestLimits(bytes, maxFormBodySize, maxMultipartParts, maxMultipartPartHeaderSize);
    }

    public RequestLimits withMaxFormBodySize(long bytes) {
        return new RequestLimits(maxChunkedRequestBodySize, bytes, maxMultipartParts, maxMultipartPartHeaderSize);
    }

    public RequestLimits withMaxMultipartParts(int parts) {
        return new RequestLimits(maxChunkedRequestBodySize, maxFormBodySize, parts, maxMultipartPartHeaderSize);
    }

    public RequestLimits withMaxMultipartPartHeaderSize(int bytes) {
        return new RequestLimits(maxChunkedRequestBodySize, maxFormBodySize, maxMultipartParts, bytes);
    }
}
