-- Índices de las consultas frecuentes del ciclo de asignación (KAN-45). Sin ellos PostgreSQL recorría freight_loads
-- completa (Seq Scan) en cada pasada.
--
-- AssignmentTimeoutScheduler, cada 30 s:
--   findByAssignmentStatusAndAssignmentStatusChangedAtBefore -> WHERE assignment_status = ? AND assignment_status_changed_at < ?
CREATE INDEX IF NOT EXISTS idx_assignment_notified
    ON freight_loads (assignment_status, assignment_status_changed_at);

-- GET /api/v1/loads/mine (findByAssignedDriverIdOrderByAssignedAtDesc) y la comprobación de conductor ocupado al
-- aceptar (findByAssignedDriverIdAndStatusIn).
CREATE INDEX IF NOT EXISTS idx_assigned_driver
    ON freight_loads (assigned_driver_id);
