-- Estado de la cuenta de cada conductor según user-identity (ACTIVE, PENDING_VERIFICATION o SUSPENDED). Lo llena
-- UserAccountEventListener con user.created, user.status.changed y la fotografía que user-identity publica al
-- arrancar; Load-Allocation no lee la base de user-identity. Solo una cuenta ACTIVE puede aceptar cargas (Lote 2).
CREATE TABLE IF NOT EXISTS driver_account_status (
    driver_id  VARCHAR(255) PRIMARY KEY,
    status     VARCHAR(40)  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
