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

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.foy.internal.container.VidocqServletContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code getLocales()} parses {@code Accept-Language} (RFC 9110 section 12.5.4): quality order, then header order. */
class AcceptLanguageTest {

    private record FakeRequest(String acceptLanguage) implements Request {
        @Override public HttpMethod method() { return HttpMethod.GET; }
        @Override public URI uri() { return URI.create("http://localhost/x"); }
        @Override public String path() { return "/x"; }
        @Override public String query() { return null; }
        @Override public Headers headers() {
            return Headers.builder().add("Host", "localhost").add("Accept-Language", acceptLanguage).build();
        }
        @Override public Body body() { return Body.empty(); }
        @Override public Map<String, String> pathParams() { return Map.of(); }
        @Override public Map<String, String> queryParams() { return Map.of(); }
        @Override public HttpVersion version() { return HttpVersion.HTTP_1_1; }
        @Override public boolean isSecure() { return false; }
        @Override public InetSocketAddress remoteAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 50000);
        }
        @Override public InetSocketAddress localAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 8080);
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "fr-CH, fr;q=0.9, en;q=0.8          | fr-CH fr en",
            "en;q=0.5, de                       | de en",
            "da, en-gb;q=0.8, en;q=0.8          | da en-GB en",
            "en;q=abc, de;q=0.5                 | en de",
            "en;q=0, de                         | de",
            "' , en,, de ;q=0.9'                | en de",
            "en;q=0.2;q=0.9, de;q=0.5           | en de",
            "en;q=0.2;q=oops, de;q=0.5          | de en",
            "en ; level=1, de;q=0.5             | en de"
    })
    void localesFollowQualityThenHeaderOrder(String header, String expected) {
        var request = new HttpServletRequestImpl(new FakeRequest(header), new VidocqServletContext("/ctx"), "/ctx",
                "/x", null);
        String locales = Collections.list(request.getLocales()).stream()
                .map(Locale::toLanguageTag)
                .collect(Collectors.joining(" "));
        assertEquals(expected, locales);
    }
}
