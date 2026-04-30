package io.vidocq.foy.tck;

import org.junit.jupiter.api.Disabled;

/**
 * Point d'accrochage unique pour valider que l'adapter Arquillian boote correctement
 * sur un test TCK officiel. Désactivé par défaut car dépend du profil {@code tck-official}.
 *
 * <p>Activer via : {@code mvn -Ptck-official -Dtest.official.tck=true -Dtest=OfficialTckSmokeTest verify}.
 * Sans le profil, les classes TCK ne sont pas sur le classpath et la compilation saute.</p>
 */
@Disabled("Activation manuelle — voir README M3 TCK officiel")
public class OfficialTckSmokeTest {
    // Les tests sont les classes *Tests du jar jakarta.tck:servlet-tck-runtime.
    // Surefire les découvre automatiquement avec <includes>**/*Tests.class</includes>.
    // Ce fichier sert de sentinelle pour documenter l'entrée.
}
