package io.vidocq.foy.tck.arquillian;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TckIdentitiesTest {

    @Test
    void theTckUsersHaveTheirSunWebXmlRoles() {
        var store = TckIdentities.store();
        assertEquals(Set.of("Administrator", "Employee"),
                store.validate("j2ee", "j2ee".toCharArray()).orElseThrow().roles());
        assertEquals(Set.of("Manager", "Employee"),
                store.validate("javajoe", "javajoe".toCharArray()).orElseThrow().roles());
        assertTrue(store.validate("j2ee", "javajoe".toCharArray()).isEmpty());
    }
}
