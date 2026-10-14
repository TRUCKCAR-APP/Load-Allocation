package escuelaing.edu.co.truckcar.load_allocation.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryConfirmationRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<ConstraintViolation<DeliveryConfirmationRequest>> validate(String signature, String photo) {
        return validator.validate(DeliveryConfirmationRequest.builder()
                .receiverName("Jefe de Bodega").signatureDataUrl(signature).photoUrl(photo).build());
    }

    private static String svg(String content) {
        return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void acceptsPngSignatureAndPhotoAsDataUrlOrHttpUrl() {
        assertThat(validate("data:image/png;base64,iVBORw0KGgo=", "data:image/jpeg;base64,/9j/4AAQ")).isEmpty();
        assertThat(validate(null, "https://evidencias.truckcar.co/pod/30.jpg")).isEmpty();
        assertThat(validate(svg("<svg xmlns='http://www.w3.org/2000/svg'><path d='M0 0L10 10'/></svg>"), null)).isEmpty();
    }

    @Test
    void rejectsNonImageOrMalformedPayloads() {
        assertThat(validate("data:text/html;base64,PGgxPg==", null)).isNotEmpty();
        assertThat(validate("javascript:alert(1)", null)).isNotEmpty();
        assertThat(validate(null, "data:image/svg+xml;base64,PHN2Zz4=")).isNotEmpty();
        assertThat(validate(null, "ftp://servidor/foto.jpg")).isNotEmpty();
    }

    @Test
    void rejectsSvgSignaturesWithScriptsHandlersOrExternalLinks() {
        assertThat(validate(svg("<svg><script>alert(1)</script></svg>"), null)).isNotEmpty();
        assertThat(validate(svg("<svg onload=\"alert(1)\"></svg>"), null)).isNotEmpty();
        assertThat(validate(svg("<svg><image href=\"https://tracker.example/x.png\"/></svg>"), null)).isNotEmpty();
    }

    @Test
    void rejectsOversizedSignatures() {
        String huge = "data:image/png;base64," + "A".repeat(DeliveryConfirmationRequest.MAX_SIGNATURE_LENGTH);
        assertThat(validate(huge, null)).extracting(ConstraintViolation::getMessage).contains("La firma no puede superar 500 KB");
    }
}
