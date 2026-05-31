package io.vidocq.foy.tck;

import org.junit.jupiter.api.Disabled;

/**
 * Unique hooking point to validate that the Arquillian adapter boots correctly
 * on an official TCK test. Disabled by default because it depends on the {@code tck-official} profile.
 *
 * <p>Activate via: {@code mvn -Ptck-official -Dtest.official.tck=true -Dtest=OfficialTckSmokeTest verify}.
 * Without the profile, the TCK classes are not on the classpath and compilation skips.</p>
 */
@Disabled("Activation manuelle — voir README M3 TCK officiel")
public class OfficialTckSmokeTest {
    // Les tests sont les classes *Tests du jar jakarta.tck:servlet-tck-runtime.
    // Surefire les découvre automatiquement avec <includes>**/*Tests.class</includes>.
    // Ce fichier sert de sentinelle pour documenter l'entrée.
}
