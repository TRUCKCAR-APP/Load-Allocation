package escuelaing.edu.co.truckcar.load_allocation.tracking;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class TrackingTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-31T15:00:00Z");

    private static TrackingTokenService service(String secret) {
        TrackingProperties properties = new TrackingProperties();
        properties.setSecret(secret);
        return new TrackingTokenService(properties);
    }

    @Test
    void aSignedTokenCarriesTheLoadAndItsExpiry() {
        TrackingTokenService tokens = service("clave-de-pruebas-de-al-menos-32-bytes!!");
        String token = tokens.issue(30L, NOW, NOW.plus(Duration.ofHours(48)));

        var claims = tokens.verify(token, NOW.plus(Duration.ofHours(1))).orElseThrow();

        assertThat(claims.loadId()).isEqualTo(30L);
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(48)));
        assertThat(token).doesNotContain("=").doesNotContain("+").doesNotContain("/");
    }

    @Test
    void expiredForgedOrForeignTokensAreRejected() {
        TrackingTokenService tokens = service("clave-de-pruebas-de-al-menos-32-bytes!!");
        String token = tokens.issue(30L, NOW, NOW.plus(Duration.ofHours(1)));

        assertThat(tokens.verify(token, NOW.plus(Duration.ofHours(1)))).isEmpty();
        assertThat(service("otra-clave-distinta-de-al-menos-32-bytes").verify(token, NOW)).isEmpty();

        // Cambiar la carga del payload sin poder volver a firmarlo.
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("v1|31|" + NOW.getEpochSecond() + "|" + NOW.plus(Duration.ofDays(30)).getEpochSecond()
                        + "|00000000-0000-0000-0000-000000000000").getBytes(StandardCharsets.UTF_8));
        String forged = forgedPayload + token.substring(token.indexOf('.'));
        assertThat(tokens.verify(forged, NOW)).isEmpty();

        assertThat(tokens.verify("", NOW)).isEmpty();
        assertThat(tokens.verify("a.b.c", NOW)).isEmpty();
        assertThat(tokens.verify("%%%.###", NOW)).isEmpty();
    }

    @Test
    void withoutAConfiguredSecretEachProcessUsesItsOwnKey() {
        String token = service("").issue(30L, NOW, NOW.plus(Duration.ofHours(1)));

        assertThat(service("").verify(token, NOW)).isEmpty();
    }
}
