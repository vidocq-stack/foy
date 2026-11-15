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
package io.vidocq.foy.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vidocq.chappe.api.WebSocketOrigins;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Foy's {@link OriginPolicy} and Chappe's {@link WebSocketOrigins} parse the same setting (WEBSOCKET.md
 * section 5.3.1: {@code *}, or a comma-separated list of {@code http(s)://host[:port]} origins), and Vidocq
 * feeds both the same key. For every value of one corpus, both accept it or both reject it; when both
 * accept it, they agree on {@code allowsAny} and allow the same (origin, host) pairs.
 */
class OriginParsersAgreementTest {

    /** A handshake's {@code Origin} and {@code Host} headers, either {@code null} when absent. */
    private record Handshake(String origin, String host) {
    }

    /** Setting values: valid lists, {@code *}, case, default ports, IPv6, and what both must refuse. */
    private static final List<String> SETTINGS = Arrays.asList(
            null, "", "   ", "\t", ",", ",,,", " , ",
            "*", " * ", "*,http://a.example", "http://a.example, *", "*, ftp://x", "**", "*/",
            "https://admin.example.com, http://localhost:5173", "http://a.example,,http://b.example",
            "http://a.example, ", ", http://a.example", " http://a.example ,\thttp://b.example\t",
            "HTTPS://Admin.Example.COM", "Http://LOCALHOST:5173",
            "http://a.example:80", "https://a.example:443", "https://a.example:80", "http://a.example:8080",
            "http://a.example:0", "http://a.example:00080", "http://a.example:65535", "http://a.example:65536",
            "http://a.example:99999", "http://a.example:123456", "http://a.example:",
            "http://[::1]", "http://[::1]:8080", "http://[2001:DB8::1]", "http://[::ffff:127.0.0.1]:443",
            "http://[::1", "http://::1", "http://127.0.0.1:8080", "http://a_b.example", "http://a~b.example",
            "http://a.example/", "https://x/", "http://a.example/path", "http://a.example?q", "http://a.example#f",
            "http://a.example\n", "\nhttp://a.example", "http://a\u0000.example", "http://a.example\u007F",
            "http://a.example\u0085", "*\u007F", "http://a\tb.example",
            "http://user@a.example", "http://user:pw@a.example", "http://@a.example",
            "ftp://a.example", "ws://a.example", "wss://a.example", "file://", "null", "a.example", "//a.example",
            "http:/a.example", "http://", "https:// a.example", "http://a .example",
            " http://a.example", " ", " ,http://a.example", "http://bücher.example",
            "http://xn--bcher-kva.example");

    private static final String HOST = "a.example";
    private static final String ORIGIN = "http://" + HOST;
    private static final String SECURE_ORIGIN = "https://" + HOST;
    private static final String SERVER = "server.example";

    /** The handshakes every policy both parsers accept is asked about. */
    private static final List<Handshake> HANDSHAKES = List.of(
            new Handshake(null, HOST), new Handshake(null, null),
            new Handshake(ORIGIN, HOST), new Handshake(ORIGIN, HOST + ":80"),
            new Handshake(SECURE_ORIGIN, HOST), new Handshake(SECURE_ORIGIN, HOST + ":443"),
            new Handshake(ORIGIN + ":8080", HOST + ":8080"), new Handshake("http://A.EXAMPLE", HOST),
            new Handshake("HTTP://a.example", "A.Example"), new Handshake(ORIGIN, HOST + ":080"),
            new Handshake("https://admin.example.com", SERVER), new Handshake("http://localhost:5173", SERVER),
            new Handshake("http://admin.example.com", SERVER), new Handshake(ORIGIN + ":80", SERVER),
            new Handshake(SECURE_ORIGIN + ":443", "x"), new Handshake(SECURE_ORIGIN + ":80", "x"),
            new Handshake(ORIGIN + ":0", HOST + ":0"),
            new Handshake("http://[::1]:8080", "[::1]:8080"), new Handshake("http://[::1]", SERVER),
            new Handshake("http://[2001:db8::1]", "x"), new Handshake("http://127.0.0.1:8080", "x"),
            new Handshake("http://a_b.example", "x"), new Handshake("http://b.example", "x"),
            new Handshake("null", HOST), new Handshake("file://", "a"), new Handshake("", HOST),
            new Handshake(ORIGIN + "/", HOST), new Handshake("ws://" + HOST, HOST),
            new Handshake("http://evil.example", HOST), new Handshake("http://evil.example", null),
            new Handshake(ORIGIN, HOST + ":99999"), new Handshake(ORIGIN, HOST + ":"),
            new Handshake(ORIGIN + ":65536", HOST + ":65536"), new Handshake(ORIGIN, ""),
            new Handshake(ORIGIN, HOST + "/x"), new Handshake("http://user@" + HOST, HOST));

    static Stream<Named<String>> settings() {
        return SETTINGS.stream().map(value -> Named.of(value == null ? "null" : OriginPolicy.printable(value), value));
    }

    @ParameterizedTest
    @MethodSource("settings")
    void bothParsersDecideAlike(String setting) {
        OriginPolicy foy = parsed(() -> OriginPolicy.parse(setting));
        WebSocketOrigins chappe = parsed(() -> WebSocketOrigins.parse(setting));

        assertEquals(chappe != null, foy != null, "accepted by Chappe, accepted by Foy");
        if (foy == null) {
            return;
        }
        assertEquals(chappe.allowsAny(), foy.allowsAny(), "allowsAny");
        List<String> disagreements = HANDSHAKES.stream()
                .filter(h -> foy.allows(h.origin(), h.host()) != chappe.allows(h.origin(), h.host()))
                .map(h -> h + ": Chappe " + chappe.allows(h.origin(), h.host()))
                .toList();
        assertEquals(List.of(), disagreements, "handshakes the two policies decide differently");
    }

    /** The policy {@code parse} returns, or {@code null} when it rejects the value. */
    private static <T> T parsed(Supplier<T> parse) {
        try {
            return parse.get();
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
