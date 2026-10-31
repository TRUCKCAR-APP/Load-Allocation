package escuelaing.edu.co.truckcar.load_allocation.migration;

import escuelaing.edu.co.truckcar.load_allocation.storage.PodMediaKind;
import escuelaing.edu.co.truckcar.load_allocation.storage.PodObjectStorage;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V3 — Traslada las firmas y fotos guardadas como data URL Base64 en freight_load_pod_media al almacenamiento de
 * objetos (S3) y elimina esas columnas TEXT. Las fotos que eran un enlace https se conservan en photo_external_url.
 * <p>
 * Es una migración Java (bean de Spring que Spring Boot registra en Flyway) porque necesita el cliente S3. Corre
 * dentro de la transacción de Flyway: si el almacenamiento no responde, la migración falla, el servicio no arranca
 * y se reintenta en el siguiente inicio sin perder datos.
 */
@Slf4j
@Component
public class V3__MovePodMediaToObjectStorage extends BaseJavaMigration {

    private static final Pattern DATA_URL = Pattern.compile("^data:(image/(?:png|jpeg|webp|svg\\+xml));base64,(.+)$",
            Pattern.DOTALL);

    private final PodObjectStorage storage;

    public V3__MovePodMediaToObjectStorage(PodObjectStorage storage) {
        this.storage = storage;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!hasLegacyColumns(connection)) {
            return;
        }
        int moved = 0;
        boolean bucketReady = false;
        try (Statement select = connection.createStatement();
             ResultSet rows = select.executeQuery("SELECT load_id, signature_data_url, photo_url FROM freight_load_pod_media"
                     + " WHERE signature_data_url IS NOT NULL OR photo_url IS NOT NULL")) {
            while (rows.next()) {
                long loadId = rows.getLong("load_id");
                String signature = rows.getString("signature_data_url");
                String photo = rows.getString("photo_url");
                if (!bucketReady) {
                    storage.ensureBucket();
                    bucketReady = true;
                }
                Stored storedSignature = upload(loadId, PodMediaKind.SIGNATURE, signature);
                Stored storedPhoto = upload(loadId, PodMediaKind.PHOTO, photo);
                String externalPhoto = photo != null && photo.matches("(?i)https?://.+") ? photo : null;
                try (PreparedStatement update = connection.prepareStatement("UPDATE freight_load_pod_media SET"
                        + " signature_object_key = ?, signature_sha256 = ?, signature_content_type = ?, signature_size_bytes = ?,"
                        + " photo_object_key = ?, photo_sha256 = ?, photo_content_type = ?, photo_size_bytes = ?,"
                        + " photo_external_url = ? WHERE load_id = ?")) {
                    bind(update, 1, storedSignature);
                    bind(update, 5, storedPhoto);
                    update.setString(9, externalPhoto);
                    update.setLong(10, loadId);
                    update.executeUpdate();
                }
                moved++;
            }
        }
        try (Statement drop = connection.createStatement()) {
            drop.execute("ALTER TABLE freight_load_pod_media DROP COLUMN IF EXISTS signature_data_url,"
                    + " DROP COLUMN IF EXISTS photo_url");
        }
        log.info("Evidencias de entrega trasladadas al almacenamiento de objetos: {} registro(s)", moved);
    }

    /** Sube un data URL y devuelve su referencia; null si no es una imagen embebida (vacío o enlace https). */
    private Stored upload(long loadId, PodMediaKind kind, String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = DATA_URL.matcher(value.trim());
        if (!matcher.matches()) {
            return null;
        }
        String contentType = matcher.group(1);
        if (kind.extensionFor(contentType).isEmpty()) {
            // Firmas SVG de la versión anterior: no se migran como imagen ejecutable al almacenamiento.
            log.warn("Evidencia {} de la carga #{} en formato {} no se traslada", kind, loadId, contentType);
            return null;
        }
        byte[] content = Base64.getMimeDecoder().decode(matcher.group(2));
        String sha256 = PodObjectStorage.sha256Hex(content);
        String key = kind.objectKey(loadId, sha256, contentType);
        storage.put(key, content, contentType);
        return new Stored(key, sha256, contentType, content.length);
    }

    private static void bind(PreparedStatement statement, int index, Stored stored) throws java.sql.SQLException {
        statement.setString(index, stored == null ? null : stored.key());
        statement.setString(index + 1, stored == null ? null : stored.sha256());
        statement.setString(index + 2, stored == null ? null : stored.contentType());
        if (stored == null) {
            statement.setNull(index + 3, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index + 3, stored.sizeBytes());
        }
    }

    private static boolean hasLegacyColumns(Connection connection) throws java.sql.SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM information_schema.columns"
                + " WHERE table_schema = current_schema() AND table_name = 'freight_load_pod_media'"
                + " AND column_name = 'signature_data_url'");
             ResultSet result = query.executeQuery()) {
            return result.next();
        }
    }

    private record Stored(String key, String sha256, String contentType, long sizeBytes) {
    }
}
