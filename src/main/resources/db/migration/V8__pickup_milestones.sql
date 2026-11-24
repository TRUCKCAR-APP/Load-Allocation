-- Hitos de la recogida dentro del viaje (IN_TRANSIT): el conductor sale hacia el cargue (trip_started_at), llega al
-- punto de cargue (arrived_at_pickup_at) y sale cargado hacia el destino (picked_up_at). La entrega exige la recogida.
ALTER TABLE freight_loads ADD COLUMN IF NOT EXISTS arrived_at_pickup_at TIMESTAMP(6);
ALTER TABLE freight_loads ADD COLUMN IF NOT EXISTS picked_up_at TIMESTAMP(6);
