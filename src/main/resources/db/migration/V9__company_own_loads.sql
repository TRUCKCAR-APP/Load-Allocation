-- Carga propia de una transportadora: la publica la empresa y solo la pueden llevar sus conductores con la afiliación
-- ACTIVA. owner_company_id es el id de la empresa en user-identity; NULL en las cargas de un generador.
ALTER TABLE freight_loads ADD COLUMN IF NOT EXISTS owner_company_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_owner_company ON freight_loads (owner_company_id);
