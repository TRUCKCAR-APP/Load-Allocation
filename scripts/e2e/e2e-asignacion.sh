#!/usr/bin/env bash
# =============================================================================
# TruckCar - Prueba E2E del ciclo de asignación de carga a través del api-gateway.
#
#   0. Cierra los compromisos previos del conductor real: rechaza propuestas NOTIFIED, vence las PROPOSED,
#      cancela las cargas ASSIGNED y cierra con entrega los viajes IN_TRANSIT de ejecuciones anteriores.
#   1. Inactiva los conductores ficticios drv-e2e-1..3 en fleets-drivers.
#   2. Activa al conductor real (usuario CONDUCTOR) con un vehículo apto.
#   3. Publica una carga limpia de 30 t (Load-Allocation).
#   4. Ejecuta el matching (AI-powered-fleet-allocation) y verifica que la carga quede
#      propuesta al conductor real.
#   5. Espera NOTIFIED (Notifications); si no llega, la notifica con PATCH /assignment/notify.
#   6. Verifica que la carga sea visible para el frontend del conductor.
#   7. (Opcional, ACCEPT=1) verifica que otro usuario no pueda aceptarla (403), la acepta como el
#      conductor asignado y planea la ruta con Route-AI.
#   8. (ACCEPT=1) inicia el viaje (IN_TRANSIT), genera el enlace público de seguimiento y lo abre sin sesión, sube la
#      firma con URL prefirmada, registra la entrega (DELIVERED), comprueba que la firma quedó asociada (SHA-256) y
#      que el destinatario ve el envío entregado.
#   9. (ACCEPT=1) publica otra carga y verifica la cancelación: 403 para CONDUCTOR, 200 para COORDINADOR.
#  10. api-gateway: 401/400/404 con JSON y mensaje claro, y WebSocket con ticket de un solo uso (sin JWT en la URL).
#
# La propuesta que Notifications no confirme vence sola (truckcar.assignment.response-timeout-minutes) y vuelve al
# motor; el script solo la notifica a mano en la sección 5 para no esperar ese plazo.
#
# Uso (desde la raíz del repo Load-Allocation):
#        PASSWORD=... bash scripts/e2e/e2e-asignacion.sh            # deja la carga en NOTIFIED para aceptarla en el frontend
#        PASSWORD=... ACCEPT=1 bash scripts/e2e/e2e-asignacion.sh   # además acepta y planea la ruta
#
# Requiere: infraestructura (docker compose up -d) y los microservicios + api-gateway corriendo.
#   User-Identity, api-gateway y Notifications deben arrancar con el MISMO TRUCKCAR_JWT_SECRET
#   (Base64, >= 256 bits; JWT_SECRET se acepta como nombre heredado). El script no necesita el
#   secreto (obtiene los tokens con login); si los servicios no lo comparten, verá 401 desde la sección 2.
# =============================================================================
set -uo pipefail

GATEWAY="${GATEWAY:-http://localhost:8000}"
API="$GATEWAY/api/v1"
COORD_EMAIL="${COORD_EMAIL:-coord.e2e@truckcar.co}"
DRIVER_EMAIL="${DRIVER_EMAIL:-conductor.e2e@truckcar.co}"
# Contraseña de los usuarios de prueba: se pasa por entorno, nunca se versiona.
PASSWORD="${PASSWORD:?Defina PASSWORD con la contraseña de los usuarios de prueba (ej. PASSWORD=... bash scripts/e2e/e2e-asignacion.sh)}"
DRIVER_ID="${DRIVER_ID:-207a8e39-990e-48da-9b85-10178c83a8cb}"
LOAD_WEIGHT="${LOAD_WEIGHT:-30.0}"
ACCEPT="${ACCEPT:-0}"
MOCK_DRIVERS=("drv-e2e-1|E2E101" "drv-e2e-2|E2E102" "drv-e2e-3|E2E103")

PASSED=0
FAILED=0
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ----------------------------------------------------------------- utilidades
pass() { PASSED=$((PASSED + 1)); printf '  \033[32mOK\033[0m   %s\n' "$1"; }
fail() { FAILED=$((FAILED + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$1"; }
info() { printf '       %s\n' "$1"; }
section() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

# call METHOD PATH TOKEN [BODY] -> deja cuerpo en $TMP/body, cabeceras en $TMP/headers y código en $CODE
call() {
    local method="$1" path="$2" token="$3" body="${4:-}"
    local args=(-s -o "$TMP/body" -D "$TMP/headers" -w '%{http_code}' -X "$method" "$API$path"
                -H 'Accept: application/json' -H "X-Correlation-Id: $RUN_ID")
    [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
    [ -n "$body" ] && args+=(-H 'Content-Type: application/json' --data-binary "$body")
    CODE="$(curl "${args[@]}")"
    BODY="$(cat "$TMP/body")"
    printf '       %-6s %-52s -> HTTP %s\n' "$method" "$path" "$CODE"
}

# field NAME [JSON] -> primer valor de "NAME" (string o número) en el JSON
field() {
    local json="${2:-$BODY}"
    printf '%s' "$json" | grep -oE "\"$1\":(\"[^\"]*\"|-?[0-9.]+|true|false|null)" | head -1 \
        | sed -E "s/^\"$1\"://; s/^\"//; s/\"$//"
}

expect_code() {
    local expected="$1" label="$2"
    if [[ " $expected " == *" $CODE "* ]]; then pass "$label"; return 0; fi
    fail "$label (esperado $expected, recibido $CODE: $(printf '%s' "$BODY" | head -c 200))"
    return 1
}

expect_eq() {
    local actual="$1" expected="$2" label="$3"
    if [ "$actual" = "$expected" ]; then pass "$label = $actual"; return 0; fi
    fail "$label: esperado '$expected', recibido '$actual'"
    return 1
}

load_state() {
    call GET "/loads/$LOAD_ID" "$COORD_TOKEN" >/dev/null
    LOAD_STATUS="$(field status)"; ASSIGNMENT="$(field assignmentStatus)"; ASSIGNED_TO="$(field assignedDriverId)"
}

wait_assignment() {   # wait_assignment ESTADO1|ESTADO2 SEGUNDOS
    local wanted="$1" seconds="$2"
    for _ in $(seq 1 "$seconds"); do
        load_state
        [[ "|$wanted|" == *"|$ASSIGNMENT|"* ]] && return 0
        sleep 1
    done
    return 1
}

# Firma PNG de 1x1 px: suficiente para validar el contrato del POD de punta a punta.
SIGNATURE_PNG_B64='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=='

# upload_signature LOAD_ID -> sube la firma con la URL prefirmada (como la PWA) y deja su referencia JSON en
# $SIGNATURE_REF y su SHA-256 en $SIGNATURE_SHA. Devuelve 1 si algún paso falla.
upload_signature() {
    local load_id="$1" file="$TMP/signature.png"
    printf '%s' "$SIGNATURE_PNG_B64" | base64 -d > "$file"
    local size sha
    size="$(wc -c < "$file" | tr -d ' ')"
    sha="$(sha256sum "$file" | cut -d' ' -f1)"
    call POST "/loads/$load_id/proof-of-delivery/upload-urls" "$DRIVER_TOKEN" \
        "{\"files\":[{\"kind\":\"SIGNATURE\",\"contentType\":\"image/png\",\"sizeBytes\":$size,\"sha256\":\"$sha\"}]}"
    [ "$CODE" = "200" ] || return 1
    local url key
    url="$(field uploadUrl)"; key="$(field objectKey)"
    # Cabeceras firmadas (Content-Type, x-amz-checksum-sha256...): deben enviarse tal cual en el PUT.
    local headers=()
    while IFS= read -r pair; do
        [ -z "$pair" ] && continue
        headers+=(-H "$(printf '%s' "$pair" | sed -E 's/^"([^"]+)":"([^"]*)"$/\1: \2/')")
    done < <(printf '%s' "$BODY" | grep -oE '"headers":\{[^}]*\}' | head -1 | sed -E 's/^"headers":\{//; s/\}$//' \
                | grep -oE '"[^"]+":"[^"]*"')
    local put_code
    put_code="$(curl -s -o "$TMP/put" -w '%{http_code}' -X PUT "${headers[@]}" --data-binary "@$file" "$url")"
    printf '       %-6s %-52s -> HTTP %s\n' PUT "(almacenamiento) $key" "$put_code"
    [ "$put_code" = "200" ] || return 1
    SIGNATURE_SHA="$sha"
    SIGNATURE_REF="{\"objectKey\":\"$key\",\"sha256\":\"$sha\",\"contentType\":\"image/png\",\"sizeBytes\":$size}"
}

# ws_handshake QUERY -> código HTTP del upgrade a WebSocket en /ws-telemetry (101 = conexión abierta).
ws_handshake() {
    curl -s -o /dev/null -w '%{http_code}' --http1.1 --max-time 3 "$GATEWAY/ws-telemetry$1" \
        -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' \
        -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' -H "Origin: ${WS_ORIGIN:-http://localhost:5500}" || true
}

driver_state() {   # driver_state ID PLACA STATUS HORAS A_BORDO_TN LAT LON MODELO NOMBRE (capacidad: maxPayloadTons 35)
    cat <<JSON
{"driverId":"$1","driverName":"$9","licenseCategory":"C3","rating":4.9,"totalTrips":210,
 "vehiclePlate":"$2","vehicleModel":"$8","vehicleType":"TRACTOMULA_3S3","maxHeightMeters":4.2,"maxPayloadTons":35.0,
 "netCargoWeightTons":$5,"tareWeightTons":16.0,"drivingHoursToday":$4,"hazmatAuthorized":true,
 "runtValid":true,"electronicTollTagActive":true,"currentLatitude":$6,"currentLongitude":$7,
 "currentSpeedKmH":0,"status":"$3"}
JSON
}

RUN_ID="$(cat /proc/sys/kernel/random/uuid 2>/dev/null || printf 'e2e-%s' "$(date +%s)")"
echo "TruckCar E2E asignación | gateway=$GATEWAY | correlationId=$RUN_ID"

# ----------------------------------------------------------------- 0. salud
section "0. Salud del api-gateway"
# El actuator del gateway escucha en :8090, que no se publica; en :8000 una ruta protegida sin token debe dar 401.
call GET "/loads" ""
expect_code 401 "api-gateway disponible (ruta protegida sin token -> 401)" \
    || { echo "El gateway no responde en $GATEWAY"; exit 1; }

# ----------------------------------------------------------------- 1. sesiones
section "1. Autenticación (User-Identity)"
call POST "/auth/login" "" "{\"email\":\"$COORD_EMAIL\",\"password\":\"$PASSWORD\"}"
expect_code 200 "login coordinador ($COORD_EMAIL)" && COORD_TOKEN="$(field accessToken)" || exit 1
call POST "/auth/login" "" "{\"email\":\"$DRIVER_EMAIL\",\"password\":\"$PASSWORD\"}"
expect_code 200 "login conductor ($DRIVER_EMAIL)" && DRIVER_TOKEN="$(field accessToken)" || exit 1
call GET "/users/me" "$DRIVER_TOKEN"
expect_code 200 "perfil del conductor"
expect_eq "$(field id)" "$DRIVER_ID" "id de usuario del conductor = driverId en fleets-drivers" \
    || { echo "El driverId debe ser el id de usuario del conductor. Ajuste DRIVER_ID."; exit 1; }
DRIVER_NAME="$(field firstName) $(field lastName)"

# ----------------------------------------------------------------- 1b. limpieza
# Ejecuciones previas (o el reintento automático del matching) pueden dejar al conductor con propuestas o
# cargas activas; mientras las tenga, el motor no le propone la carga nueva. Se cierran por rondas porque cada
# rechazo/cancelación dispara un reintento asíncrono que puede proponerle otra carga en espera.
section "1b. Limpieza de compromisos previos de $DRIVER_NAME"
driver_commitments() {
    call GET "/loads/driver-commitments" "$COORD_TOKEN" >/dev/null
    printf '%s' "$BODY" | grep -oE '\{[^}]*\}' | grep "\"driverId\":\"$DRIVER_ID\"" || true
}
CLEAN=0
for round in 1 2 3 4 5 6; do
    PENDING="$(driver_commitments)"
    if [ -z "$PENDING" ]; then
        # Confirmación tras dar tiempo a un reintento en curso.
        sleep 4
        PENDING="$(driver_commitments)"
        [ -z "$PENDING" ] && { CLEAN=1; break; }
    fi
    while IFS= read -r commitment; do
        id="$(field loadId "$commitment")"; status="$(field loadStatus "$commitment")"
        assignment="$(field assignmentStatus "$commitment")"
        info "ronda $round: carga #$id ($status/$assignment)"
        case "$status/$assignment" in
            AVAILABLE/NOTIFIED) call PATCH "/loads/$id/assignment/reject" "$DRIVER_TOKEN" ;;
            AVAILABLE/PROPOSED) call PATCH "/loads/$id/assignment/timeout" "$COORD_TOKEN" ;;
            ASSIGNED/*)         call PATCH "/loads/$id/cancel" "$COORD_TOKEN" \
                                    '{"reason":"Limpieza automatica antes de la prueba E2E"}' ;;
            IN_TRANSIT/*)       # La entrega exige firma: se sube una antes de cerrar el viaje.
                                upload_signature "$id" && call PATCH "/loads/$id/trip/deliver" "$DRIVER_TOKEN" \
                                    "{\"receiverName\":\"Cierre automatico E2E\",\"notes\":\"Viaje de una prueba anterior cerrado por el script E2E\",\"signature\":$SIGNATURE_REF}" ;;
        esac
    done <<< "$PENDING"
    sleep 3
done
if [ "$CLEAN" = "1" ]; then
    pass "$DRIVER_NAME sin propuestas ni cargas activas"
else
    fail "$DRIVER_NAME sigue con compromisos tras 6 rondas: $(printf '%s' "$PENDING" | head -c 300)"
    exit 1
fi

# ----------------------------------------------------------------- 2. flota
section "2. Flota (fleets-drivers)"
for mock in "${MOCK_DRIVERS[@]}"; do
    IFS='|' read -r mock_id mock_plate <<< "$mock"
    call POST "/fleets-drivers/state" "$COORD_TOKEN" \
        "$(driver_state "$mock_id" "$mock_plate" INACTIVO 2 0 4.65 -74.10 'Kenworth T800' "Mock $mock_id")"
    expect_code 200 "inactivar $mock_id" && expect_eq "$(field status)" "INACTIVO" "  status $mock_id"
done

call POST "/fleets-drivers/state" "$DRIVER_TOKEN" \
    "$(driver_state "$DRIVER_ID" TDK-410 ACTIVO 2 0 4.6097 -74.0817 'Kenworth T800' "$DRIVER_NAME")"
expect_code 200 "activar conductor real $DRIVER_NAME"
expect_eq "$(field status)" "ACTIVO" "  status"
expect_eq "$(field restRequiredAlert)" "false" "  restRequiredAlert"

call GET "/fleets-drivers/active" "$COORD_TOKEN"
ELIGIBLE="$(printf '%s' "$BODY" | grep -oE '\{[^{}]*\}' | grep -iE '"status":"ACTIVO"' | grep -E '"restRequiredAlert":false' \
    | grep -E '"vehicleType":"TRACTOMULA_3S3"' | grep -oE '"driverId":"[^"]*"' | cut -d'"' -f4)"
info "candidatos elegibles TRACTOMULA_3S3: $(printf '%s' "$ELIGIBLE" | tr '\n' ' ')"
if [ "$(printf '%s\n' "$ELIGIBLE" | grep -c .)" = "1" ] && [ "$ELIGIBLE" = "$DRIVER_ID" ]; then
    pass "el conductor real es el único candidato elegible"
else
    fail "hay otros candidatos elegibles; el motor podría no elegir al conductor real"
fi

# ----------------------------------------------------------------- 3. carga
section "3. Publicar carga (Load-Allocation)"
call POST "/loads" "$COORD_TOKEN" "{\"generatorCompanyId\":\"empresa-e2e-script\",\"generatorCompanyName\":\"Acerias E2E\",
  \"cargoDescription\":\"Perfiles de acero (E2E $RUN_ID)\",\"weightTons\":$LOAD_WEIGHT,\"volumeM3\":28,
  \"requiredTruckType\":\"TRACTOMULA_3S3\",\"originCity\":\"Bogota\",\"originDepartment\":\"Cundinamarca\",
  \"originLatitude\":4.6100,\"originLongitude\":-74.0820,\"destinationCity\":\"Cali\",
  \"destinationDepartment\":\"Valle del Cauca\",\"destinationLatitude\":3.4516,\"destinationLongitude\":-76.5320,
  \"offeredBudget\":5300000}"
expect_code 201 "carga publicada" || exit 1
LOAD_ID="$(field id)"
info "loadId=$LOAD_ID correlationId=$(field correlationId)"
expect_eq "$(field status)" "AVAILABLE" "  status inicial"
expect_eq "$(field assignmentStatus)" "null" "  assignmentStatus inicial"

# ----------------------------------------------------------------- 4. matching
section "4. Matching (AI-powered-fleet-allocation)"
# Al publicar, Load-Allocation emite load.published y AI-fleet hace el matching automático.
if wait_assignment "PROPOSED|NOTIFIED" 15; then
    pass "matching automático por evento load.published -> $ASSIGNMENT"
else
    info "no hubo matching automático en 15 s (assignmentStatus=$ASSIGNMENT)"
fi
ALREADY_PROPOSED=0
{ [ "$ASSIGNMENT" = "PROPOSED" ] || [ "$ASSIGNMENT" = "NOTIFIED" ]; } && ALREADY_PROPOSED=1
call POST "/fleet-allocation/match/$LOAD_ID" "$COORD_TOKEN"
info "decisión: status=$(field status) driver=$(field selectedDriverId) score=$(field matchingScore)"
expect_eq "$(field selectedDriverId)" "$DRIVER_ID" "  conductor seleccionado por el motor"
if [ "$ALREADY_PROPOSED" = "1" ]; then
    # La carga ya tiene propuesta vigente: Load-Allocation rechaza el /assign y el match lo informa con 409.
    expect_code 409 "POST /fleet-allocation/match/$LOAD_ID informa el conflicto (carga ya propuesta)"
    expect_eq "$(field status)" "ASSIGNMENT_CONFLICT" "  status de la decisión"
else
    expect_code 200 "POST /fleet-allocation/match/$LOAD_ID aplica la propuesta"
    expect_eq "$(field status)" "SUCCESSFUL" "  status de la decisión"
fi
wait_assignment "PROPOSED|NOTIFIED" 10
expect_eq "$ASSIGNED_TO" "$DRIVER_ID" "carga $LOAD_ID asignada a $DRIVER_NAME"

# ----------------------------------------------------------------- 5. notificación
section "5. Notificación (Notifications)"
if wait_assignment "NOTIFIED" 15; then
    pass "Notifications emitió assignment.notified -> NOTIFIED"
else
    info "Notifications no confirmó en 15 s; se notifica manualmente"
    call PATCH "/loads/$LOAD_ID/assignment/notify" "$COORD_TOKEN"
    expect_code 200 "PATCH /loads/$LOAD_ID/assignment/notify"
    load_state
    expect_eq "$ASSIGNMENT" "NOTIFIED" "  assignmentStatus"
fi

# ----------------------------------------------------------------- 6. visibilidad frontend
section "6. Visibilidad para el frontend del conductor"
call GET "/loads/available" "$DRIVER_TOKEN"
expect_code 200 "GET /loads/available con el token del conductor"
VISIBLE="$(printf '%s' "$BODY" | grep -oE '\{[^{}]*"id":'"$LOAD_ID"',[^{}]*\}')"
if printf '%s' "$VISIBLE" | grep -q "\"assignedDriverId\":\"$DRIVER_ID\"" \
    && printf '%s' "$VISIBLE" | grep -q '"assignmentStatus":"NOTIFIED"'; then
    pass "la carga $LOAD_ID aparece como NOTIFIED para $DRIVER_NAME (pantalla 'Carga' del frontend)"
else
    fail "la carga $LOAD_ID no aparece lista para aceptar en el frontend"
fi

# ----------------------------------------------------------------- 7. aceptación opcional
if [ "$ACCEPT" = "1" ]; then
    section "7. Aceptación y ruta (opcional)"
    call PATCH "/loads/$LOAD_ID/assignment/accept" "$COORD_TOKEN"
    expect_code 403 "un usuario distinto al conductor asignado no puede aceptar"
    load_state
    expect_eq "$ASSIGNMENT" "NOTIFIED" "  la carga sigue intacta"
    call PATCH "/loads/$LOAD_ID/assignment/accept" "$DRIVER_TOKEN"
    expect_code 200 "PATCH /loads/$LOAD_ID/assignment/accept"
    expect_eq "$(field assignmentStatus)" "ACCEPTED" "  assignmentStatus"
    expect_eq "$(field status)" "ASSIGNED" "  status"
    call POST "/routes/plan" "$DRIVER_TOKEN" "{\"vehiclePlate\":\"TDK-410\",\"truckType\":\"TRACTOMULA_3S3\",
      \"grossWeightTons\":$(awk "BEGIN{print 16+$LOAD_WEIGHT}"),\"heightMeters\":4.2,\"originCity\":\"Bogota\",
      \"destinationCity\":\"Cali\",\"originLatitude\":4.61,\"originLongitude\":-74.082,
      \"destinationLatitude\":3.4516,\"destinationLongitude\":-76.532}"
    expect_code 200 "POST /routes/plan" && info "corredor=$(field recommendedCorridor) distancia=$(field totalDistanceKm) km riesgo=$(field riskLevel)"

    section "8. Ciclo del viaje: tránsito y entrega con prueba de entrega"
    call GET "/loads/mine" "$DRIVER_TOKEN"
    expect_code 200 "GET /loads/mine del conductor"
    printf '%s' "$BODY" | grep -q "\"id\":$LOAD_ID," && pass "  la carga $LOAD_ID aparece en sus cargas" || fail "  la carga $LOAD_ID no aparece en /loads/mine"
    call PATCH "/loads/$LOAD_ID/trip/start" "$COORD_TOKEN"
    expect_code 403 "otro usuario no puede iniciar el viaje"
    # Con una firma bien formada (la firma es obligatoria): lo que se prueba es el estado, no la validación (400).
    EARLY_SHA="$(printf 'antes-de-iniciar-%s' "$RUN_ID" | sha256sum | cut -d' ' -f1)"
    call PATCH "/loads/$LOAD_ID/trip/deliver" "$DRIVER_TOKEN" \
        "{\"receiverName\":\"Jefe de Bodega\",\"signature\":{\"objectKey\":\"pod/$LOAD_ID/signature-$EARLY_SHA.png\",
          \"sha256\":\"$EARLY_SHA\",\"contentType\":\"image/png\",\"sizeBytes\":68}}"
    expect_code 409 "no se puede entregar antes de iniciar el viaje"
    call PATCH "/loads/$LOAD_ID/trip/start" "$DRIVER_TOKEN"
    expect_code 200 "PATCH /loads/$LOAD_ID/trip/start"
    expect_eq "$(field status)" "IN_TRANSIT" "  status"
    call PATCH "/loads/$LOAD_ID/cancel" "$COORD_TOKEN" '{"reason":"Prueba de cancelacion en transito"}'
    expect_code 409 "una carga en tránsito no se puede cancelar"

    section "8b. Seguimiento público para el destinatario"
    call POST "/loads/$LOAD_ID/public-tracking-link" "$DRIVER_TOKEN"
    expect_code 403 "un CONDUCTOR no comparte el seguimiento"
    call POST "/loads/$LOAD_ID/public-tracking-link" "$COORD_TOKEN"
    expect_code 201 "POST /loads/$LOAD_ID/public-tracking-link (torre de control)"
    TRACK_TOKEN="$(field token)"
    info "enlace para el destinatario: $(field url)"
    call GET "/loads/public-tracking/$TRACK_TOKEN" ""
    expect_code 200 "el destinatario abre el seguimiento sin iniciar sesión"
    expect_eq "$(field status)" "IN_TRANSIT" "  estado visible para el destinatario"
    expect_eq "$(field reference)" "CARGA-$LOAD_ID" "  referencia del envío"
    printf '%s' "$BODY" | grep -q 'TDK-410' && fail "  la placa completa no debe publicarse" || pass "  placa enmascarada"
    printf '%s' "$BODY" | grep -q "$DRIVER_ID" && fail "  el id del conductor no debe publicarse" || pass "  sin datos del conductor"
    call GET "/loads/public-tracking/${TRACK_TOKEN}x" ""
    expect_code 410 "un enlace alterado no muestra el envío"

    section "8c. Entrega con evidencia digital"
    call PATCH "/loads/$LOAD_ID/trip/deliver" "$DRIVER_TOKEN" '{"receiverName":"","notes":"x"}'
    expect_code 400 "la entrega exige el nombre de quien recibe"
    call PATCH "/loads/$LOAD_ID/trip/deliver" "$DRIVER_TOKEN" '{"receiverName":"Jefe de Bodega Cali"}'
    expect_code 400 "la entrega exige la firma de quien recibe"
    # Formato antiguo (imagen en Base64 dentro del JSON, aquí un SVG con script): ya no se acepta.
    call PATCH "/loads/$LOAD_ID/trip/deliver" "$DRIVER_TOKEN" \
        '{"receiverName":"Jefe de Bodega Cali","signatureDataUrl":"data:image/svg+xml;base64,PHN2ZyBvbmxvYWQ9ImFsZXJ0KDEpIj48L3N2Zz4="}'
    expect_code 400 "una firma en Base64 / SVG se rechaza"
    # Referencia a una firma que nunca se subió: 422.
    FAKE_SHA="$(printf 'no-subida-%s' "$RUN_ID" | sha256sum | cut -d' ' -f1)"
    call PATCH "/loads/$LOAD_ID/trip/deliver" "$DRIVER_TOKEN" \
        "{\"receiverName\":\"Jefe de Bodega Cali\",\"signature\":{\"objectKey\":\"pod/$LOAD_ID/signature-$FAKE_SHA.png\",
          \"sha256\":\"$FAKE_SHA\",\"contentType\":\"image/png\",\"sizeBytes\":68}}"
    expect_code 422 "una firma que no está en el almacenamiento se rechaza"
    call POST "/loads/$LOAD_ID/proof-of-delivery/upload-urls" "$COORD_TOKEN" \
        '{"files":[{"kind":"SIGNATURE","contentType":"image/png","sizeBytes":68,"sha256":"'"$FAKE_SHA"'"}]}'
    expect_code 403 "solo el conductor asignado pide URLs de subida"
    if upload_signature "$LOAD_ID"; then pass "firma subida al almacenamiento con URL prefirmada"; else fail "no se pudo subir la firma"; fi
    call PATCH "/loads/$LOAD_ID/trip/deliver" "$DRIVER_TOKEN" \
        "{\"receiverName\":\"Jefe de Bodega Cali\",\"receiverDocument\":\"CC 1020304050\",\"notes\":\"Sin novedades\",
          \"signature\":$SIGNATURE_REF}"
    expect_code 200 "PATCH /loads/$LOAD_ID/trip/deliver"
    expect_eq "$(field status)" "DELIVERED" "  status"
    expect_eq "$(field podReceiverName)" "Jefe de Bodega Cali" "  prueba de entrega: quien recibe"
    expect_eq "$(field podHasSignature)" "true" "  prueba de entrega con firma"
    call PATCH "/loads/$LOAD_ID/trip/deliver" "$DRIVER_TOKEN" \
        "{\"receiverName\":\"Jefe de Bodega Cali\",\"signature\":$SIGNATURE_REF}"
    expect_code 409 "una carga entregada no se entrega dos veces"
    call GET "/loads/$LOAD_ID/proof-of-delivery" "$COORD_TOKEN"
    expect_code 200 "GET /loads/$LOAD_ID/proof-of-delivery (torre de control)"
    expect_eq "$(field signatureSha256)" "$SIGNATURE_SHA" "  firma asociada a la entrega"
    SIGNATURE_URL="$(field signatureUrl)"
    if [ -n "$SIGNATURE_URL" ] && [ "$SIGNATURE_URL" != "null" ] \
        && [ "$(curl -s "$SIGNATURE_URL" | sha256sum | cut -d' ' -f1)" = "$SIGNATURE_SHA" ]; then
        pass "  la firma descargada coincide con su SHA-256"
    else
        fail "  no se pudo descargar la firma almacenada o su contenido no coincide"
    fi
    call GET "/loads/$LOAD_ID/proof-of-delivery" "$DRIVER_TOKEN"
    expect_code 200 "el conductor ve su prueba de entrega"
    call GET "/loads/public-tracking/$TRACK_TOKEN" ""
    expect_code 200 "el destinatario ve el envío entregado"
    expect_eq "$(field status)" "DELIVERED" "  estado visible para el destinatario"
    call GET "/loads?status=DELIVERED" "$DRIVER_TOKEN"
    expect_code 403 "un CONDUCTOR no lista todas las cargas"
    call GET "/loads?status=DELIVERED" "$COORD_TOKEN"
    expect_code 200 "GET /loads?status=DELIVERED (torre de control)"
    printf '%s' "$BODY" | grep -q "\"id\":$LOAD_ID," && pass "  la carga $LOAD_ID aparece en el historial de entregas" \
        || fail "  la carga $LOAD_ID no aparece en el historial de entregas"

    section "9. Cancelación por la torre de control"
    call POST "/loads" "$COORD_TOKEN" "{\"generatorCompanyId\":\"empresa-e2e-script\",\"generatorCompanyName\":\"Acerias E2E\",
      \"cargoDescription\":\"Carga a cancelar (E2E $RUN_ID)\",\"weightTons\":10,\"requiredTruckType\":\"TRACTOMULA_3S3\",
      \"originCity\":\"Bogota\",\"originDepartment\":\"Cundinamarca\",\"originLatitude\":4.61,\"originLongitude\":-74.082,
      \"destinationCity\":\"Tunja\",\"destinationDepartment\":\"Boyaca\",\"destinationLatitude\":5.535,\"destinationLongitude\":-73.367,
      \"offeredBudget\":1200000}"
    expect_code 201 "carga a cancelar publicada" && CANCEL_ID="$(field id)"
    call PATCH "/loads/$CANCEL_ID/cancel" "$DRIVER_TOKEN" '{"reason":"Intento del conductor"}'
    expect_code 403 "un CONDUCTOR no puede cancelar cargas"
    call PATCH "/loads/$CANCEL_ID/cancel" "$COORD_TOKEN" '{"reason":"El generador retiro la carga"}'
    expect_code 200 "PATCH /loads/$CANCEL_ID/cancel (COORDINADOR)"
    expect_eq "$(field status)" "CANCELLED" "  status"
    call PATCH "/loads/$CANCEL_ID/assignment/accept" "$DRIVER_TOKEN"
    expect_code "403 409" "la carga cancelada ya no admite aceptación"
fi

# ----------------------------------------------------------------- 10. api-gateway
section "10. api-gateway: errores normalizados y acceso"
expect_json_error() {   # expect_json_error ETIQUETA -> el cuerpo trae status, error y message
    if printf '%s' "$BODY" | grep -q '"status":' && printf '%s' "$BODY" | grep -q '"message":"[^"]\+'; then
        pass "  $1: JSON con mensaje ($(field message | head -c 90))"
    else
        fail "  $1: el error no trae un mensaje claro: $(printf '%s' "$BODY" | head -c 200)"
    fi
}
call GET "/loads" ""
expect_code 401 "sin token: 401" && expect_json_error "sin token"
call GET "/loads" "token.invalido.firma"
expect_code 401 "token inválido: 401" && expect_json_error "token inválido"
call GET "/ruta-que-no-existe" "$COORD_TOKEN"
expect_code 404 "ruta inexistente: 404" && expect_json_error "ruta inexistente"
call POST "/loads" "$COORD_TOKEN" '{"cargoDescription":'
expect_code 400 "JSON mal formado: 400" && expect_json_error "JSON mal formado"
call POST "/loads" "$COORD_TOKEN" "{\"generatorCompanyName\":\"Acerias E2E\",\"cargoDescription\":\"$(printf 'x%.0s' $(seq 1 300))\",
  \"weightTons\":10,\"requiredTruckType\":\"TRACTOMULA_3S3\",\"originCity\":\"Bogota\",\"originDepartment\":\"Cundinamarca\",
  \"originLatitude\":400,\"originLongitude\":-74.082,\"destinationCity\":\"Tunja\",\"destinationDepartment\":\"Boyaca\",
  \"destinationLatitude\":5.535,\"destinationLongitude\":-73.367,\"offeredBudget\":1200000}"
expect_code 400 "texto demasiado largo y latitud fuera de rango: 400 (antes 500)" && expect_json_error "validación"
call GET "/loads/no-es-un-numero" "$COORD_TOKEN"
expect_code 400 "id con tipo incorrecto: 400" && expect_json_error "tipo incorrecto"
call PATCH "/loads/999999999/assignment/accept" "$DRIVER_TOKEN"
expect_code 404 "carga inexistente: 404" && expect_json_error "carga inexistente"

# WebSocket de telemetría: solo con ticket de un solo uso; nunca con el JWT en la URL.
WS_CODE="$(ws_handshake "")"
expect_eq "$WS_CODE" "401" "WebSocket sin ticket"
WS_CODE="$(ws_handshake "?access_token=$DRIVER_TOKEN")"
expect_eq "$WS_CODE" "401" "WebSocket con el JWT en la URL (no autentica)"
call POST "/telemetry/ws-ticket" ""
expect_code 401 "ticket sin token: 401"
call POST "/telemetry/ws-ticket" "$DRIVER_TOKEN"
if expect_code 200 "POST /telemetry/ws-ticket"; then
    WS_TICKET="$(field ticket)"
    WS_CODE="$(ws_handshake "?ticket=$WS_TICKET")"
    expect_eq "$WS_CODE" "101" "WebSocket abierto con el ticket"
    WS_CODE="$(ws_handshake "?ticket=$WS_TICKET")"
    expect_eq "$WS_CODE" "401" "el mismo ticket no sirve dos veces"
fi

# ----------------------------------------------------------------- resumen
section "Resumen"
echo "  $PASSED verificaciones OK, $FAILED fallidas | loadId=$LOAD_ID | correlationId=$RUN_ID"
if [ "$ACCEPT" != "1" ] && [ "$FAILED" = "0" ]; then
    echo "  Frontend listo: http://localhost:5500 -> login $DRIVER_EMAIL -> 'Carga' -> 'Aceptar & Ver Ruta'"
fi
[ "$FAILED" = "0" ]
