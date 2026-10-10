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
package io.vidocq.foy.internal.security;

import io.vidocq.foy.spi.security.Identity;
import io.vidocq.foy.spi.security.IdentityStore;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An {@link IdentityStore} over a fixed set of users the embedder declares
 * ({@code FoyChappeBoot.Builder.user}). Each password is kept only as a salted SHA-256 digest:
 * no text, and no {@code char[]} the caller still owns. Validation compares digests with
 * {@link MessageDigest#isEqual} and digests a decoy for an unknown user, so the response time does
 * not tell which user names exist. This is not a password database: a persistent or shared user
 * base plugs its own {@link IdentityStore}, with a slow hash (PBKDF2, Argon2) if it stores hashes.
 */
public final class InMemoryIdentityStore implements IdentityStore {

    private record User(Identity identity, byte[] salt, byte[] digest) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, User> users;
    private final byte[] decoySalt = salt();
    private final byte[] decoyDigest = digest(decoySalt, new char[] {'\0'});

    private InMemoryIdentityStore(Map<String, User> users) {
        this.users = Map.copyOf(users);
    }

    public static Builder builder() { return new Builder(); }

    @Override
    public Optional<Identity> validate(String username, char[] password) {
        if (username == null || password == null) return Optional.empty();
        User user = users.get(username);
        byte[] candidate = digest(user == null ? decoySalt : user.salt(), password);
        try {
            boolean equal = MessageDigest.isEqual(candidate, user == null ? decoyDigest : user.digest());
            return equal && user != null ? Optional.of(user.identity()) : Optional.empty();
        } finally {
            Arrays.fill(candidate, (byte) 0);
        }
    }

    private static byte[] salt() {
        byte[] s = new byte[16];
        RANDOM.nextBytes(s);
        return s;
    }

    /** SHA-256 over salt then the UTF-8 password; every intermediate buffer is wiped. */
    static byte[] digest(byte[] salt, char[] password) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(bytes);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        } finally {
            Arrays.fill(bytes, (byte) 0);
            if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        }
    }

    public static final class Builder {
        private final Map<String, User> users = new LinkedHashMap<>();

        private Builder() {}

        /** Declares a user. {@code password} is digested at once and not kept: the caller may wipe it. */
        public Builder user(String name, char[] password, String... roles) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(password, "password");
            if (users.containsKey(name)) throw new IllegalArgumentException("user '" + name + "' declared twice");
            byte[] salt = salt();
            users.put(name, new User(Identity.of(name, Set.copyOf(List.of(roles))), salt, digest(salt, password)));
            return this;
        }

        public InMemoryIdentityStore build() { return new InMemoryIdentityStore(users); }
    }
}
