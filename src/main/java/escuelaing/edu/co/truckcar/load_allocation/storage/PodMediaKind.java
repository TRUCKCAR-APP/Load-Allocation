package escuelaing.edu.co.truckcar.load_allocation.storage;

import java.util.Map;
import java.util.Optional;

/** Archivos de una prueba de entrega: firma de quien recibe y foto de la carga. */
public enum PodMediaKind {
    SIGNATURE("signature", Map.of("image/png", "png", "image/jpeg", "jpg", "image/webp", "webp")),
    PHOTO("photo", Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp"));

    private final String prefix;
    /** Tipos permitidos y su extensión. Sin SVG: una firma vectorial podría ejecutar scripts al mostrarse. */
    private final Map<String, String> extensions;

    PodMediaKind(String prefix, Map<String, String> extensions) {
        this.prefix = prefix;
        this.extensions = extensions;
    }

    public Optional<String> extensionFor(String contentType) {
        return Optional.ofNullable(contentType == null ? null : extensions.get(contentType.toLowerCase()));
    }

    /**
     * pod/{loadId}/{signature|photo}-{sha256}.{ext}: la clave depende del contenido, así que reintentar una subida
     * (offline, respuesta perdida) escribe el mismo objeto en vez de crear otro.
     */
    public String objectKey(long loadId, String sha256Hex, String contentType) {
        String extension = extensionFor(contentType)
                .orElseThrow(() -> new IllegalArgumentException("Tipo de archivo no permitido para " + this + ": " + contentType));
        return "pod/" + loadId + "/" + prefix + "-" + sha256Hex + "." + extension;
    }

    public boolean ownsKey(long loadId, String objectKey) {
        return objectKey != null && objectKey.startsWith("pod/" + loadId + "/" + prefix + "-");
    }
}
