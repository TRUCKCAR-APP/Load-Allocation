package escuelaing.edu.co.truckcar.load_allocation.service;

import escuelaing.edu.co.truckcar.load_allocation.dto.request.DeliveryConfirmationRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.request.PodObjectReference;
import escuelaing.edu.co.truckcar.load_allocation.dto.request.PodUploadUrlRequest;
import escuelaing.edu.co.truckcar.load_allocation.dto.response.PodUploadUrlResponse;
import escuelaing.edu.co.truckcar.load_allocation.exception.ProofOfDeliveryEvidenceException;
import escuelaing.edu.co.truckcar.load_allocation.storage.PodMediaKind;
import escuelaing.edu.co.truckcar.load_allocation.storage.PodObjectStorage;
import escuelaing.edu.co.truckcar.load_allocation.storage.S3StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProofOfDeliveryServiceTest {

    private static final String SHA = "c".repeat(64);
    private static final String PHOTO_KEY = "pod/30/photo-" + SHA + ".jpg";

    private IFreightLoadService loads;
    private PodObjectStorage storage;
    private ProofOfDeliveryService service;

    @BeforeEach
    void setUp() {
        loads = mock(IFreightLoadService.class);
        storage = mock(PodObjectStorage.class);
        service = new ProofOfDeliveryService(loads, storage, new S3StorageProperties());
    }

    private static DeliveryConfirmationRequest deliveryWithPhoto(long size) {
        return DeliveryConfirmationRequest.builder().receiverName("Bodega")
                .photo(PodObjectReference.builder().objectKey(PHOTO_KEY).sha256(SHA).contentType("image/jpeg")
                        .sizeBytes(size).build())
                .build();
    }

    @Test
    void uploadUrlKeyDependsOnTheContentHashAndSignsTypeSizeAndChecksum() {
        when(storage.presignUpload(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new PodObjectStorage.PresignedUpload("http://localhost:5500/pod-media/" + PHOTO_KEY,
                        Map.of("content-type", "image/jpeg"), Instant.now()));

        PodUploadUrlResponse response = service.issueUploadUrls(30L, "driver-ana", PodUploadUrlRequest.builder()
                .files(List.of(PodUploadUrlRequest.File.builder().kind(PodMediaKind.PHOTO).contentType("image/jpeg")
                        .sizeBytes(1000L).sha256(SHA).build()))
                .build());

        verify(loads).requireDriverCanUploadProof(30L, "driver-ana");
        verify(storage).presignUpload(PHOTO_KEY, "image/jpeg", 1000L, SHA);
        assertThat(response.getUploads()).singleElement().satisfies(upload -> {
            assertThat(upload.getObjectKey()).isEqualTo(PHOTO_KEY);
            assertThat(upload.getMethod()).isEqualTo("PUT");
        });
    }

    @Test
    void oversizedEvidenceIsRefusedBeforeSigning() {
        assertThatThrownBy(() -> service.issueUploadUrls(30L, "driver-ana", PodUploadUrlRequest.builder()
                .files(List.of(PodUploadUrlRequest.File.builder().kind(PodMediaKind.SIGNATURE).contentType("image/png")
                        .sizeBytes(10L * 1024 * 1024).sha256(SHA).build()))
                .build()))
                .isInstanceOf(ProofOfDeliveryEvidenceException.class)
                .hasMessageContaining("tamaño máximo");
    }

    @Test
    void deliveryRequiresTheUploadedObjectWithTheDeclaredSizeAndHash() {
        when(storage.head(PHOTO_KEY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.verifyEvidence(30L, deliveryWithPhoto(1000)))
                .isInstanceOf(ProofOfDeliveryEvidenceException.class)
                .hasMessageContaining("no se ha subido");

        when(storage.head(PHOTO_KEY)).thenReturn(Optional.of(new PodObjectStorage.StoredObject(999L, "image/jpeg", null)));
        assertThatThrownBy(() -> service.verifyEvidence(30L, deliveryWithPhoto(1000)))
                .hasMessageContaining("999 bytes");

        // El almacenamiento no devolvió checksum: se recalcula leyendo el objeto.
        when(storage.head(PHOTO_KEY)).thenReturn(Optional.of(new PodObjectStorage.StoredObject(1000L, "image/jpeg", null)));
        when(storage.sha256Of(PHOTO_KEY)).thenReturn("d".repeat(64));
        assertThatThrownBy(() -> service.verifyEvidence(30L, deliveryWithPhoto(1000)))
                .hasMessageContaining("no coincide con su SHA-256");

        when(storage.sha256Of(PHOTO_KEY)).thenReturn(SHA);
        service.verifyEvidence(30L, deliveryWithPhoto(1000));
    }

    @Test
    void storageChecksumIsTrustedWhenPresent() {
        when(storage.head(PHOTO_KEY)).thenReturn(Optional.of(new PodObjectStorage.StoredObject(1000L, "image/jpeg",
                PodObjectStorage.base64(SHA))));

        service.verifyEvidence(30L, deliveryWithPhoto(1000));

        verify(storage, org.mockito.Mockito.never()).sha256Of(eq(PHOTO_KEY));
    }

    @Test
    void evidenceOfAnotherLoadIsRejected() {
        assertThatThrownBy(() -> service.verifyEvidence(31L, deliveryWithPhoto(1000)))
                .isInstanceOf(ProofOfDeliveryEvidenceException.class)
                .hasMessageContaining("no es una evidencia PHOTO de la carga #31");
    }
}
