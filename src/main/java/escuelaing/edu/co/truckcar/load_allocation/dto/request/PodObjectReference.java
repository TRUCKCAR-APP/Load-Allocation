package escuelaing.edu.co.truckcar.load_allocation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Archivo de la prueba de entrega ya subido al almacenamiento con la URL prefirmada: la entrega solo envía su
 * referencia, nunca la imagen. Load-Allocation comprueba que el objeto exista con este tamaño y hash.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PodObjectReference {

    @NotBlank(message = "La clave del archivo es obligatoria")
    @Size(max = 512, message = "La clave del archivo no puede superar 512 caracteres")
    @Pattern(regexp = "pod/\\d{1,18}/(signature|photo)-[0-9a-f]{64}\\.(png|jpg|webp)",
            message = "La clave del archivo no corresponde a una evidencia de entrega")
    private String objectKey;

    @NotBlank(message = "El hash SHA-256 del archivo es obligatorio")
    @Pattern(regexp = "[0-9a-f]{64}", message = "El hash debe ser SHA-256 en hexadecimal (64 caracteres)")
    private String sha256;

    @NotBlank(message = "El tipo del archivo es obligatorio")
    @Pattern(regexp = "image/(png|jpeg|webp)", message = "El archivo debe ser una imagen PNG, JPEG o WebP")
    private String contentType;

    @NotNull(message = "El tamaño del archivo es obligatorio")
    @Positive(message = "El tamaño del archivo debe ser mayor que cero")
    private Long sizeBytes;
}
