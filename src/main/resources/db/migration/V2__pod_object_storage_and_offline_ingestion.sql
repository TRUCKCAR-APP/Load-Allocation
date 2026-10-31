-- ═══════════════════════════════════════════════════════════════════
-- V2 — Evidencias de entrega en almacenamiento de objetos (S3) e ingesta idempotente de eventos offline
-- ═══════════════════════════════════════════════════════════════════

-- La firma y la foto ya no se guardan en la base: solo la referencia al objeto en S3, su hash SHA-256 (integridad,
-- verificado por S3 al subir), el tipo y el tamaño. photo_external_url conserva enlaces https de versiones previas.
ALTER TABLE freight_load_pod_media
    ADD COLUMN IF NOT EXISTS signature_object_key   VARCHAR(512),
    ADD COLUMN IF NOT EXISTS signature_sha256       VARCHAR(64),
    ADD COLUMN IF NOT EXISTS signature_content_type VARCHAR(100),
    ADD COLUMN IF NOT EXISTS signature_size_bytes   BIGINT,
    ADD COLUMN IF NOT EXISTS photo_object_key       VARCHAR(512),
    ADD COLUMN IF NOT EXISTS photo_sha256           VARCHAR(64),
    ADD COLUMN IF NOT EXISTS photo_content_type     VARCHAR(100),
    ADD COLUMN IF NOT EXISTS photo_size_bytes       BIGINT,
    ADD COLUMN IF NOT EXISTS photo_external_url     VARCHAR(2048);

-- Registro de eventos offline ya procesados: (driver_id, client_event_id) hace idempotente el reenvío de un lote.
CREATE TABLE IF NOT EXISTS ingested_events (
    driver_id       VARCHAR(64)    NOT NULL,
    client_event_id UUID           NOT NULL,
    kind            VARCHAR(40)    NOT NULL,
    seq             BIGINT,
    occurred_at     TIMESTAMPTZ(6) NOT NULL,
    received_at     TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    status          VARCHAR(10)    NOT NULL,
    reason          VARCHAR(500),
    PRIMARY KEY (driver_id, client_event_id),
    CONSTRAINT ingested_events_status_check CHECK (status IN ('APPLIED', 'REJECTED'))
);
