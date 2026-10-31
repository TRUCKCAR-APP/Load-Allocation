package escuelaing.edu.co.truckcar.load_allocation.dto.request;

import escuelaing.edu.co.truckcar.load_allocation.storage.PodMediaKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Archivos de la prueba de entrega para los que la PWA pide URLs de subida (firma y/o foto). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PodUploadUrlRequest {

    @NotEmpty(message = "Debe indicar al menos un archivo")
    @Size(max = 2, message = "Una entrega tiene como máximo una firma y una foto")
    private List<@Valid File> files;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class File {

        @NotNull(message = "El tipo de evidencia es obligatorio (SIGNATURE o PHOTO)")
        private PodMediaKind kind;

        @NotBlank(message = "El tipo del archivo es obligatorio")
        @Pattern(regexp = "image/(png|jpeg|webp)", message = "El archivo debe ser una imagen PNG, JPEG o WebP")
        private String contentType;

        @NotNull(message = "El tamaño del archivo es obligatorio")
        @Positive(message = "El tamaño del archivo debe ser mayor que cero")
        private Long sizeBytes;

        /** SHA-256 calculado en el dispositivo: la URL solo acepta exactamente ese contenido. */
        @NotBlank(message = "El hash SHA-256 del archivo es obligatorio")
        @Pattern(regexp = "[0-9a-f]{64}", message = "El hash debe ser SHA-256 en hexadecimal (64 caracteres)")
        private String sha256;
    }
}
