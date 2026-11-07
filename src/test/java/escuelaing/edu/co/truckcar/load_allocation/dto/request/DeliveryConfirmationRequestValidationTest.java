package escuelaing.edu.co.truckcar.load_allocation.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** La entrega solo acepta referencias a evidencias en el almacenamiento: nunca imágenes en Base64. */
class DeliveryConfirmationRequestValidationTest {

    private static final String SHA = "a".repeat(64);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<ConstraintViolation<DeliveryConfirmationRequest>> validate(PodObjectReference signature,
                                                                           PodObjectReference photo) {
        return validator.validate(DeliveryConfirmationRequest.builder()
                .receiverName("Jefe de Bodega").signature(signature).photo(photo).build());
    }

    private static PodObjectReference reference(String key, String sha, String type, long size) {
        return PodObjectReference.builder().objectKey(key).sha256(sha).contentType(type).sizeBytes(size).build();
    }

    private static final PodObjectReference VALID_SIGNATURE =
            reference("pod/30/signature-" + SHA + ".png", SHA, "image/png", 2048);

    @Test
    void acceptsDeliveryWithSignatureAndOptionalPhoto() {
        assertThat(validate(VALID_SIGNATURE, null)).isEmpty();
        assertThat(validate(VALID_SIGNATURE,
                reference("pod/30/photo-" + SHA + ".jpg", SHA, "image/jpeg", 350_000))).isEmpty();
    }

    @Test
    void rejectsDeliveryWithoutSignature() {
        assertThat(validate(null, null))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("La firma de quien recibe es obligatoria para registrar la entrega");
        assertThat(validate(null, reference("pod/30/photo-" + SHA + ".jpg", SHA, "image/jpeg", 350_000)))
                .isNotEmpty();
    }

    @Test
    void rejectsBase64OrForeignKeysAndMalformedHashes() {
        assertThat(validate(reference("data:image/png;base64,iVBORw0KGgo=", SHA, "image/png", 10), null))
                .extracting(ConstraintViolation::getMessage)
                .contains("La clave del archivo no corresponde a una evidencia de entrega");
        assertThat(validate(reference("../otro-bucket/secreto.png", SHA, "image/png", 10), null)).isNotEmpty();
        assertThat(validate(reference("pod/30/signature-" + SHA + ".png", "no-es-sha", "image/png", 10), null))
                .extracting(ConstraintViolation::getMessage)
                .contains("El hash debe ser SHA-256 en hexadecimal (64 caracteres)");
    }

    @Test
    void rejectsSvgAndEmptyFiles() {
        assertThat(validate(reference("pod/30/signature-" + SHA + ".png", SHA, "image/svg+xml", 10), null))
                .extracting(ConstraintViolation::getMessage)
                .contains("El archivo debe ser una imagen PNG, JPEG o WebP");
        assertThat(validate(reference("pod/30/signature-" + SHA + ".png", SHA, "image/png", 0), null)).isNotEmpty();
    }
}
