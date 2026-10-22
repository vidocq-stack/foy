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

/**
 * The target of the nightly OWASP ZAP scan (Vidocq/vidocq#273): Foy on Chappe with Vauban, serving the
 * surfaces a Servlet container is responsible for. A test vehicle, never published.
 */
module io.vidocq.foy.zap {
    requires jakarta.servlet;
    requires jakarta.cdi;
    requires io.vidocq.foy.chappe;
    requires io.vidocq.chappe.api;
    requires io.vidocq.vauban.core;

    // The companions foy-processor generates: Foy builds the web components without reflection.
    provides io.vidocq.foy.spi.gen.WebComponent with
            io.vidocq.foy.zap.EchoServlet$$FoyComponent,
            io.vidocq.foy.zap.ErrorServlet$$FoyComponent,
            io.vidocq.foy.zap.RedirectServlet$$FoyComponent,
            io.vidocq.foy.zap.SecurityHeadersFilter$$FoyComponent,
            io.vidocq.foy.zap.SessionServlet$$FoyComponent;

    // The bean index vauban-processor generates.
    provides io.vidocq.vauban.api.VaubanComponentProvider with io.vidocq.foy.zap._VaubanComponents;
}
