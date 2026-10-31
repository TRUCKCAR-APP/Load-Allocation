package escuelaing.edu.co.truckcar.load_allocation.service;

import escuelaing.edu.co.truckcar.load_allocation.dto.request.DeliveryConfirmationRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.request.PodObjectReference;
import escuelaing.edu.co.truckcar.load_allocation.dto.request.PodUploadUrlRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.response.PodUploadUrlResponse;
import escuelaing.edu.co.truckcar.load_allocation.dto.response.ProofOfDeliveryResponse;
import escuelaing.edu.co.truckcar.load_allocation.exception.ProofOfDeliveryEvidenceException;
import escuelaing.edu.co.truckcar.load_allocation.storage.PodMediaKind;
import escuelaing.edu.co.truckcar.load_allocation.storage.PodObjectStorage;
import escuelaing.edu.co.truckcar.load_allocation.storage.S3StorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Evidencias de entrega en el almacenamiento de objetos (patrón de URL prefirmada):
 * <ol>
 *   <li>La PWA calcula el SHA-256 de la firma y la foto y pide URLs de subida (issueUploadUrls).</li>
 *   <li>Sube cada archivo directamente al almacenamiento con un PUT; la firma de la URL fija tipo, tamaño y hash.</li>
 *   <li>Registra la entrega con las claves y hashes; aquí se verifica cada objeto antes de aceptarla.</li>
 * </ol>
 * La base de datos solo guarda referencias; las imágenes nunca atraviesan el gateway ni los microservicios.
 */
@Service
@RequiredArgsConstructor
public class ProofOfDeliveryService {

    private final IFreightLoadService freightLoadService;
    private final PodObjectStorage storage;
    private final S3StorageProperties properties;

    public PodUploadUrlResponse issueUploadUrls(Long loadId, String authenticatedUserId, PodUploadUrlRequest request) {
        freightLoadService.requireDriverCanUploadProof(loadId, authenticatedUserId);
        if (request.getFiles().stream().map(PodUploadUrlRequest.File::getKind).distinct().count()
                != request.getFiles().size()) {
            throw new ProofOfDeliveryEvidenceException("Cada tipo de evidencia (SIGNATURE, PHOTO) se pide una sola vez");
        }
        List<PodUploadUrlResponse.Upload> uploads = request.getFiles().stream().map(file -> {
            requireWithinLimit(file.getKind(), file.getSizeBytes());
            String key = file.getKind().objectKey(loadId, file.getSha256(), file.getContentType());
            PodObjectStorage.PresignedUpload upload =
                    storage.presignUpload(key, file.getContentType(), file.getSizeBytes(), file.getSha256());
            return PodUploadUrlResponse.Upload.builder()
                    .kind(file.getKind())
                    .objectKey(key)
                    .uploadUrl(upload.url())
                    .method("PUT")
                    .headers(upload.headers())
                    .expiresAt(upload.expiresAt())
                    .build();
        }).toList();
        return PodUploadUrlResponse.builder().uploads(uploads).build();
    }

    /** Antes de registrar la entrega: cada evidencia referenciada existe y coincide en tamaño, tipo y SHA-256. */
    public void verifyEvidence(Long loadId, DeliveryConfirmationRequest pod) {
        verify(loadId, PodMediaKind.SIGNATURE, pod.getSignature());
        verify(loadId, PodMediaKind.PHOTO, pod.getPhoto());
    }

    /** Las claves internas se convierten en URLs de descarga prefirmadas de vida corta. */
    public ProofOfDeliveryResponse withDownloadUrls(ProofOfDeliveryResponse response) {
        if (response.getSignatureObjectKey() != null) {
            response.setSignatureUrl(storage.presignDownload(response.getSignatureObjectKey()));
        }
        if (response.getPhotoObjectKey() != null) {
            response.setPhotoUrl(storage.presignDownload(response.getPhotoObjectKey()));
        }
        return response;
    }

    private void verify(Long loadId, PodMediaKind kind, PodObjectReference reference) {
        if (reference == null) {
            return;
        }
        if (!kind.ownsKey(loadId, reference.getObjectKey())) {
            throw new ProofOfDeliveryEvidenceException("El archivo " + reference.getObjectKey()
                    + " no es una evidencia " + kind + " de la carga #" + loadId);
        }
        requireWithinLimit(kind, reference.getSizeBytes());
        PodObjectStorage.StoredObject stored = storage.head(reference.getObjectKey())
                .orElseThrow(() -> new ProofOfDeliveryEvidenceException("La evidencia " + kind
                        + " no se ha subido todavía (" + reference.getObjectKey() + ")"));
        if (stored.sizeBytes() == null || stored.sizeBytes().longValue() != reference.getSizeBytes()) {
            throw new ProofOfDeliveryEvidenceException("La evidencia " + kind + " tiene " + stored.sizeBytes()
                    + " bytes y se declararon " + reference.getSizeBytes());
        }
        // Con checksum en la subida el almacenamiento ya validó el contenido; si no lo devuelve, se recalcula.
        boolean hashMatches = stored.checksumSha256() != null
                ? stored.checksumSha256().equals(PodObjectStorage.base64(reference.getSha256()))
                : storage.sha256Of(reference.getObjectKey()).equals(reference.getSha256());
        if (!hashMatches) {
            throw new ProofOfDeliveryEvidenceException("El contenido de la evidencia " + kind
                    + " no coincide con su SHA-256 declarado");
        }
    }

    private void requireWithinLimit(PodMediaKind kind, long sizeBytes) {
        long max = kind == PodMediaKind.PHOTO ? properties.getMaxPhotoBytes() : properties.getMaxSignatureBytes();
        if (sizeBytes > max) {
            throw new ProofOfDeliveryEvidenceException("La evidencia " + kind + " supera el tamaño máximo de "
                    + (max / 1024) + " KB");
        }
    }
}
