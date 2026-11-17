-- Tipo de conductor y afiliación a una transportadora según user-identity (evento user.affiliation.changed). Lo llena
-- UserAccountEventListener. Un INDEPENDIENTE o un conductor de EMPRESA sin la afiliación ACTIVA no acepta cargas
-- (load_eligible = false). Un conductor sin fila (cuentas anteriores a la afiliación) conserva el comportamiento de antes.
CREATE TABLE IF NOT EXISTS driver_affiliation (
    driver_id          VARCHAR(255) PRIMARY KEY,
    driver_type        VARCHAR(20)  NOT NULL,
    company_id         VARCHAR(64),
    company_name       VARCHAR(150),
    affiliation_status VARCHAR(20),
    load_eligible      BOOLEAN      NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
