-- Contexto de traza de cada evento del outbox: el traceparent W3C de la petición que lo originó y su X-Correlation-Id.
-- El publicador los restaura al enviarlo a Kafka, así la traza continúa en el servicio que lo consume.
ALTER TABLE load_allocation_outbox_events
    ADD COLUMN IF NOT EXISTS trace_parent   VARCHAR(55),
    ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(100);
