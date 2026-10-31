package escuelaing.edu.co.truckcar.load_allocation.dto.response;

import escuelaing.edu.co.truckcar.load_allocation.storage.PodMediaKind;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** URLs prefirmadas para subir la firma y la foto directamente al almacenamiento. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PodUploadUrlResponse {

    private List<Upload> uploads;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Upload {
        private PodMediaKind kind;
        /** Referencia que la PWA envía luego en la entrega (PodObjectReference.objectKey). */
        private String objectKey;
        private String uploadUrl;
        private String method;
        /** Cabeceras firmadas que deben acompañar el PUT. */
        private Map<String, String> headers;
        private Instant expiresAt;
    }
}
