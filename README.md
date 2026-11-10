# Load-Allocation

Ciclo de vida de las cargas de TruckCar (puerto `8083`, detrás del api-gateway en `/api/v1/loads`).

## Flujo de una carga

| Paso | Endpoint | Quién | `status` / `assignmentStatus` |
|---|---|---|---|
| Publicar | `POST /api/v1/loads` | GENERADOR, COORDINADOR, ADMIN | `AVAILABLE` / – |
| Proponer conductor | `PATCH /{id}/assign` (lo hace el motor de asignación al recibir `load.published`) | COORDINADOR, ADMIN, SYSTEM | `AVAILABLE` / `PROPOSED` |
| Aviso al conductor | evento `assignment.notified` de Notifications | – | `AVAILABLE` / `NOTIFIED` |
| Aceptar / rechazar | `PATCH /{id}/assignment/accept` · `/reject` | conductor propuesto | `ASSIGNED` / `ACCEPTED` · `AVAILABLE` / `REJECTED` |
| Iniciar viaje | `PATCH /{id}/trip/start` | conductor asignado | `IN_TRANSIT` |
| Subir evidencias | `POST /{id}/proof-of-delivery/upload-urls` → `PUT` directo al almacenamiento | conductor asignado | – |
| Entregar | `PATCH /{id}/trip/deliver` | conductor asignado | `DELIVERED` |
| Cancelar | `PATCH /{id}/cancel` (antes de iniciar el viaje) | COORDINADOR, ADMIN, GENERADOR dueño | `CANCELLED` |

**Sin intervención manual:** una propuesta `PROPOSED` o `NOTIFIED` sin respuesta vence sola a los
`TRUCKCAR_ASSIGNMENT_RESPONSE_TIMEOUT_MINUTES` (5 min por defecto). El conductor queda descartado y el motor busca otro
candidato, aunque Notifications no haya confirmado el aviso. Tras `TRUCKCAR_ASSIGNMENT_MAX_ATTEMPTS` intentos la carga
queda `UNASSIGNED_NO_DRIVERS`.

**Carga a bordo:** cada transición emite un evento con `loadStatus` y `weightTons`. fleets-drivers lo usa para
mantener las cargas activas del conductor y el peso a bordo, que solo cuenta mientras la carga está `IN_TRANSIT`.

## Evidencia digital de la entrega (POD)

- La **firma** de quien recibe es obligatoria; la **foto** es opcional. Ambas son PNG, JPEG o WebP, nunca SVG.
- La PWA calcula el SHA-256, pide URLs prefirmadas y sube cada archivo directamente al almacenamiento S3/MinIO. Las
  imágenes no atraviesan el gateway.
- Al entregar se comprueba, en este orden: que quien llama es el conductor asignado, que la clave pertenece a esta carga
  y a ese tipo, y que el objeto existe con el tamaño, el tipo y el SHA-256 declarados.
- La referencia queda asociada a la entrega (`proof_of_delivery_media`). `GET /{id}/proof-of-delivery` la devuelve con
  URLs de descarga temporales: la ven el conductor, el generador dueño y la torre de control.

## Seguimiento público

`POST /{id}/public-tracking-link` (generador dueño o torre de control) devuelve un enlace firmado con HMAC-SHA256. El
destinatario lo abre sin iniciar sesión (`GET /public-tracking/{token}`): ve el estado, la ruta y la posición en vivo
mientras la carga va en ruta. La placa sale enmascarada y no hay datos del conductor. El enlace vence a las 48 h, o 6 h
después de la entrega. Defina `TRUCKCAR_TRACKING_LINK_SECRET` para que los enlaces sobrevivan a un reinicio.

## Errores

Todas las respuestas de error llevan `timestamp`, `status`, `error` y `message`:
- 400: validación, JSON mal formado o tipo incorrecto.
- 401 / 403: sin identidad o sin permiso.
- 404: carga inexistente.
- 409: transición inválida o cambio concurrente.
- 410: enlace público no disponible.
- 422: evidencia inválida.
- 503: almacenamiento caído.

## Prueba de punta a punta

Con la plataforma levantada (`Deployment-Infrastructure`, `docker compose up -d`):

```bash
PASSWORD=... ACCEPT=1 bash scripts/e2e/e2e-asignacion.sh
```

Publica una carga, la asigna, la acepta, inicia el viaje, comparte el seguimiento público, sube la firma, registra la
entrega y verifica la evidencia y el seguimiento del destinatario. También prueba los errores del api-gateway y el
WebSocket con ticket de un solo uso.
