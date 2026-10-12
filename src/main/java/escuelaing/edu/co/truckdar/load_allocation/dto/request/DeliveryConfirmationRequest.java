package escuelaing.edu.co.truckdar.load_allocation.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/** Prueba de entrega (POD) que registra el conductor al finalizar el viaje. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryConfirmationRequest {

    /** ~370 KB de imagen: una firma PNG/SVG de un lienzo pesa bastante menos. */
    public static final int MAX_SIGNATURE_LENGTH = 500_000;
    /** ~2,2 MB de imagen: el frontend reduce la foto antes de enviarla. */
    public static final int MAX_PHOTO_LENGTH = 3_000_000;

    /** Patrones de SVG que podrían ejecutar código o cargar recursos externos al mostrarse. */
    private static final java.util.regex.Pattern UNSAFE_SVG = java.util.regex.Pattern.compile(
            "<script|javascript:|<foreignobject|\\son[a-z]+\\s*=|href\\s*=\\s*[\"']?\\s*(https?:|data:|//)");

    @NotBlank(message = "El nombre de quien recibe la carga es obligatorio")
    @Size(max = 150, message = "El nombre de quien recibe no puede superar 150 caracteres")
    private String receiverName;

    @Size(max = 40, message = "El documento de quien recibe no puede superar 40 caracteres")
    private String receiverDocument;

    @Size(max = 500, message = "Las observaciones no pueden superar 500 caracteres")
    private String notes;

    /** Firma de quien recibe como data URL Base64 (PNG, JPEG, WebP o SVG). */
    @Size(max = MAX_SIGNATURE_LENGTH, message = "La firma no puede superar 500 KB")
    @Pattern(regexp = "data:image/(png|jpeg|webp|svg\\+xml);base64,[A-Za-z0-9+/]+={0,2}",
            message = "La firma debe ser una imagen en data URL Base64 (PNG, JPEG, WebP o SVG)")
    private String signatureDataUrl;

    /** Foto de la carga entregada: URL http(s) o data URL Base64 (PNG, JPEG o WebP). */
    @Size(max = MAX_PHOTO_LENGTH, message = "La foto no puede superar 3 MB")
    @Pattern(regexp = "https?://\\S{1,2040}|data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/]+={0,2}",
            message = "La foto debe ser una URL http(s) o una imagen en data URL Base64 (PNG, JPEG o WebP)")
    private String photoUrl;

    /** Una firma SVG no puede traer scripts, manejadores de eventos ni referencias externas. */
    @JsonIgnore
    @AssertTrue(message = "La firma SVG contiene elementos no permitidos (scripts, eventos o enlaces externos)")
    public boolean isSignatureSafe() {
        if (signatureDataUrl == null || !signatureDataUrl.startsWith("data:image/svg+xml;base64,")) {
            return true;
        }
        try {
            String svg = new String(Base64.getDecoder().decode(
                    signatureDataUrl.substring("data:image/svg+xml;base64,".length())), StandardCharsets.UTF_8);
            return !UNSAFE_SVG.matcher(svg.toLowerCase(Locale.ROOT)).find();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
