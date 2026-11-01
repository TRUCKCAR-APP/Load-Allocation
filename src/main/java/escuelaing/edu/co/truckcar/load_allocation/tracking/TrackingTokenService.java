package escuelaing.edu.co.truckcar.load_allocation.tracking;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokens de los enlaces públicos: "payload.firma" en Base64URL, firmados con HMAC-SHA256. El payload solo lleva la
 * carga, la emisión y el vencimiento (sin datos personales). La firma se compara en tiempo constante.
 */
@Slf4j
@Component
public class TrackingTokenService {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final Pattern PAYLOAD = Pattern.compile("^v1\\|(\\d+)\\|(\\d+)\\|(\\d+)\\|[0-9a-f-]{36}$");

    private final byte[] key;

    public TrackingTokenService(TrackingProperties properties) {
        String secret = properties.getSecret();
        if (secret == null || secret.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.key = random;
            log.warn("truckcar.tracking.secret vacío: se generó una clave temporal; los enlaces de seguimiento"
                    + " dejan de valer al reiniciar el servicio");
        } else {
            this.key = secret.getBytes(StandardCharsets.UTF_8);
        }
    }

    public record Claims(long loadId, Instant issuedAt, Instant expiresAt) {
    }

    public String issue(long loadId, Instant issuedAt, Instant expiresAt) {
        String payload = "v1|" + loadId + "|" + issuedAt.getEpochSecond() + "|" + expiresAt.getEpochSecond() + "|" + UUID.randomUUID();
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(bytes) + "." + ENCODER.encodeToString(sign(bytes));
    }

    /** Vacío si el token está mal formado, la firma no coincide o ya venció. */
    public Optional<Claims> verify(String token, Instant now) {
        if (token == null || token.length() > 512) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return Optional.empty();
        }
        try {
            byte[] payload = DECODER.decode(token.substring(0, dot));
            byte[] signature = DECODER.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(sign(payload), signature)) {
                return Optional.empty();
            }
            Matcher m = PAYLOAD.matcher(new String(payload, StandardCharsets.UTF_8));
            if (!m.matches()) {
                return Optional.empty();
            }
            Claims claims = new Claims(Long.parseLong(m.group(1)), Instant.ofEpochSecond(Long.parseLong(m.group(2))),
                    Instant.ofEpochSecond(Long.parseLong(m.group(3))));
            return now.isBefore(claims.expiresAt()) ? Optional.of(claims) : Optional.empty();
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 no disponible", e);
        }
    }
}
