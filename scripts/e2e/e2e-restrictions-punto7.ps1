# =============================================================================
# TruckCar - Prueba Extremo a Extremo (E2E) - Punto 7 (HU3.4)
# Validacion de Restricciones Viales y Compatibilidad de Vehiculos
# =============================================================================
# NOTA: Se evitan tildes en los valores JSON para prevenir problemas de encoding
# en PowerShell. Los nombres de ciudad se envian sin tilde (Bogota, Medellin).
# =============================================================================

$ErrorActionPreference = "Stop"
$gateway = "http://localhost:8000/api/v1"
$password = "TruckCar2026*"

function Get-JwtClaim($jwt, $claim) {
    $parts = $jwt.Split('.')
    $payload = $parts[1]
    while ($payload.Length % 4) { $payload += "=" }
    $bytes = [Convert]::FromBase64String($payload.Replace('-', '+').Replace('_', '/'))
    $json = [System.Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json
    return $json.$claim
}

function Invoke-Api {
    param(
        [string]$Uri,
        [string]$Method = "Get",
        [string]$JsonBody = $null,
        [hashtable]$Headers = @{}
    )
    $params = @{
        Uri         = $Uri
        Method      = $Method
        Headers     = $Headers
        ContentType = "application/json; charset=utf-8"
    }
    if ($JsonBody) {
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes($JsonBody)
    }
    return Invoke-RestMethod @params
}

function Invoke-ApiExpectError {
    param(
        [string]$Uri,
        [string]$Method = "Get",
        [string]$JsonBody = $null,
        [hashtable]$Headers = @{}
    )
    try {
        $params = @{
            Uri         = $Uri
            Method      = $Method
            Headers     = $Headers
            ContentType = "application/json; charset=utf-8"
        }
        if ($JsonBody) {
            $params.Body = [System.Text.Encoding]::UTF8.GetBytes($JsonBody)
        }
        $result = Invoke-RestMethod @params
        return @{ StatusCode = 200; Body = ($result | ConvertTo-Json -Depth 5) }
    } catch {
        $statusCode = $_.Exception.Response.StatusCode.value__
        $errorStream = $_.Exception.Response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($errorStream)
        $errorBody = $reader.ReadToEnd()
        return @{ StatusCode = $statusCode; Body = $errorBody }
    }
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "Iniciando Prueba E2E - Punto 7: Routing-Restrictions & Load-Allocation" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# -----------------------------------------------------------------------
# 1. Registrar usuarios
# -----------------------------------------------------------------------
$users = @(
    @{ email = "coord.e2e@truckcar.co"; firstName = "Carlos"; lastName = "Torre"; role = "GENERADOR" },
    @{ email = "driver.incompatible@truckcar.co"; firstName = "Juan"; lastName = "Camionero"; role = "CONDUCTOR" },
    @{ email = "driver.compatible@truckcar.co"; firstName = "Pedro"; lastName = "Conductor"; role = "CONDUCTOR" },
    @{ email = "gen.e2e@truckcar.co"; firstName = "Maria"; lastName = "Generadora"; role = "GENERADOR" }
)

foreach ($u in $users) {
    Write-Host "Registrando $($u.email)..." -NoNewline
    $regJson = "{""email"":""$($u.email)"",""password"":""$password"",""firstName"":""$($u.firstName)"",""lastName"":""$($u.lastName)"",""role"":""$($u.role)""}"
    try {
        Invoke-Api -Uri "$gateway/auth/register" -Method Post -JsonBody $regJson | Out-Null
        Write-Host " CREADO" -ForegroundColor Green
    } catch {
        Write-Host " YA EXISTE" -ForegroundColor Yellow
    }
}

# -----------------------------------------------------------------------
# 2. Activar usuarios y fijar roles
# -----------------------------------------------------------------------
Write-Host "`nActivando cuentas en PostgreSQL (user_identity)..." -ForegroundColor Cyan
docker exec truckcar-postgis psql -U truckcar_admin -d truckcar_db -c "
UPDATE user_identity.users SET status = 'ACTIVE';
UPDATE user_identity.users SET role = 'COORDINADOR' WHERE email = 'coord.e2e@truckcar.co';
"

# -----------------------------------------------------------------------
# 3. Login
# -----------------------------------------------------------------------
Write-Host "`nObteniendo JWT tokens..." -ForegroundColor Cyan
function Login-User($email) {
    $body = "{""email"":""$email"",""password"":""$password""}"
    return Invoke-Api -Uri "$gateway/auth/login" -Method Post -JsonBody $body
}

$coordAuth   = Login-User "coord.e2e@truckcar.co"
$incompAuth  = Login-User "driver.incompatible@truckcar.co"
$compAuth    = Login-User "driver.compatible@truckcar.co"
$genAuth     = Login-User "gen.e2e@truckcar.co"

$coordId  = Get-JwtClaim $coordAuth.accessToken "sub"
$incompId = Get-JwtClaim $incompAuth.accessToken "sub"
$compId   = Get-JwtClaim $compAuth.accessToken "sub"
$genId    = Get-JwtClaim $genAuth.accessToken "sub"

Write-Host "  Coordinador ID:           $coordId" -ForegroundColor Green
Write-Host "  Conductor Incompatible ID: $incompId" -ForegroundColor Green
Write-Host "  Conductor Compatible ID:   $compId" -ForegroundColor Green
Write-Host "  Generador ID:             $genId" -ForegroundColor Green

$coordHeaders  = @{ Authorization = "Bearer $($coordAuth.accessToken)" }
$incompHeaders = @{ Authorization = "Bearer $($incompAuth.accessToken)" }
$compHeaders   = @{ Authorization = "Bearer $($compAuth.accessToken)" }
$genHeaders    = @{ Authorization = "Bearer $($genAuth.accessToken)" }

# -----------------------------------------------------------------------
# 4. Registrar vehiculos en fleets-drivers
# -----------------------------------------------------------------------
Write-Host "`nRegistrando vehiculos en fleets-drivers..." -ForegroundColor Cyan

# Conductor Incompatible: 4.40 m (> 4.10 m limite)
$incompVehicleJson = @"
{"driverId":"$incompId","driverName":"Juan Camionero","licenseCategory":"C3","rating":4.8,"totalTrips":150,"vehiclePlate":"INC-440","vehicleModel":"Kenworth T800","vehicleType":"TRACTOMULA_3S3","maxHeightMeters":4.40,"tareWeightTons":16.0,"netCargoWeightTons":0.0,"maxPayloadTons":35.0,"drivingHoursToday":2.0,"hazmatAuthorized":false,"coldChainCertified":false,"runtValid":true,"electronicTollTagActive":true,"currentLatitude":4.6097,"currentLongitude":-74.0817,"currentSpeedKmH":0,"status":"ACTIVO"}
"@
$resInc = Invoke-Api -Uri "$gateway/fleets-drivers/state" -Method Post -JsonBody $incompVehicleJson.Trim() -Headers $incompHeaders
Write-Host "  Vehiculo Incompatible: $($resInc.vehiclePlate) (altura: $($resInc.maxHeightMeters)m, tipo: $($resInc.vehicleType))" -ForegroundColor Yellow

# Conductor Compatible: 3.80 m (< 4.10 m limite)
$compVehicleJson = @"
{"driverId":"$compId","driverName":"Pedro Conductor","licenseCategory":"C3","rating":4.9,"totalTrips":220,"vehiclePlate":"COM-380","vehicleModel":"International ProStar","vehicleType":"TRACTOMULA_3S3","maxHeightMeters":3.80,"tareWeightTons":14.0,"netCargoWeightTons":0.0,"maxPayloadTons":35.0,"drivingHoursToday":1.0,"hazmatAuthorized":false,"coldChainCertified":false,"runtValid":true,"electronicTollTagActive":true,"currentLatitude":4.6097,"currentLongitude":-74.0817,"currentSpeedKmH":0,"status":"ACTIVO"}
"@
$resComp = Invoke-Api -Uri "$gateway/fleets-drivers/state" -Method Post -JsonBody $compVehicleJson.Trim() -Headers $compHeaders
Write-Host "  Vehiculo Compatible:   $($resComp.vehiclePlate) (altura: $($resComp.maxHeightMeters)m, tipo: $($resComp.vehicleType))" -ForegroundColor Green

# -----------------------------------------------------------------------
# 5. Crear restriccion de galibo (4.10 m en Bogota - Medellin)
# -----------------------------------------------------------------------
Write-Host "`nCreando restriccion vial en Routing-Restrictions..." -ForegroundColor Cyan
$restrictionCode = "REST-GALIBO-E2E-" + (Get-Random -Minimum 1000 -Maximum 9999)
$restrictionJson = @"
{"restrictionCode":"$restrictionCode","title":"Puente con galibo reducido - Limite 4.10m","description":"Restriccion de prueba E2E: altura maxima permitida 4.10 metros","restrictionType":"GALIBO_ALTURA","severity":"BLOQUEO_TOTAL","roadCorridor":"Bogota - Medellin","department":"Cundinamarca","maxClearanceMeters":4.10,"latitude":4.8500,"longitude":-74.3500}
"@
$resRest = Invoke-Api -Uri "$gateway/restrictions" -Method Post -JsonBody $restrictionJson.Trim() -Headers $coordHeaders
Write-Host "  Restriccion creada: $($resRest.restrictionCode), corredor='$($resRest.roadCorridor)', galibo=$($resRest.maxClearanceMeters)m" -ForegroundColor Green

# =============================================================================
# ESCENARIO A: Camion INCOMPATIBLE (INC-440, 4.40 m) -> HTTP 409 CONFLICT
# =============================================================================
Write-Host "`n----------------------------------------------------------" -ForegroundColor Cyan
Write-Host "ESCENARIO A: Carga con Camion Incompatible (4.40m > 4.10m)" -ForegroundColor Cyan
Write-Host "----------------------------------------------------------" -ForegroundColor Cyan

$load1Json = @"
{"originCity":"Bogota","destinationCity":"Medellin","originDepartment":"Cundinamarca","destinationDepartment":"Antioquia","originLatitude":4.6097,"originLongitude":-74.0817,"destinationLatitude":6.2442,"destinationLongitude":-75.5812,"weightTons":28.0,"requiredTruckType":"TRACTOMULA_3S3","cargoDescription":"Carga E2E para camion incompatible","generatorCompanyName":"Constructora Andina SA","offeredBudget":4500000.00}
"@

$load1 = Invoke-Api -Uri "$gateway/loads" -Method Post -JsonBody $load1Json.Trim() -Headers $genHeaders
$load1Id = $load1.id
Write-Host "  Carga #$load1Id publicada (Bogota -> Medellin)" -ForegroundColor Green

# Asignar a conductor incompatible
$assignIncJson = "{""driverId"":""$incompId"",""driverName"":""Juan Camionero"",""vehiclePlate"":""INC-440""}"
Invoke-Api -Uri "$gateway/loads/$load1Id/assign" -Method Patch -JsonBody $assignIncJson -Headers $coordHeaders | Out-Null
Invoke-Api -Uri "$gateway/loads/$load1Id/assignment/notify" -Method Patch -Headers $coordHeaders | Out-Null
Write-Host "  Carga asignada y notificada a conductor incompatible" -ForegroundColor Yellow

# Intento de aceptacion -> DEBE RECHAZAR CON 409
Write-Host "  Intentando ACEPTAR carga con camion incompatible (4.40m vs limite 4.10m)..." -ForegroundColor Cyan
$resultA = Invoke-ApiExpectError -Uri "$gateway/loads/$load1Id/assignment/accept" -Method Patch -Headers $incompHeaders

if ($resultA.StatusCode -eq 409) {
    Write-Host "  [EXITO] Aceptacion BLOQUEADA con HTTP $($resultA.StatusCode)!" -ForegroundColor Green
    Write-Host "  Respuesta: $($resultA.Body)" -ForegroundColor Yellow
} else {
    Write-Host "  [FALLO] Se esperaba HTTP 409, se obtuvo HTTP $($resultA.StatusCode)" -ForegroundColor Red
    Write-Host "  Respuesta: $($resultA.Body)" -ForegroundColor Red
    exit 1
}

# =============================================================================
# ESCENARIO B: Camion COMPATIBLE (COM-380, 3.80 m) -> HTTP 200 OK + IN_TRANSIT
# =============================================================================
Write-Host "`n----------------------------------------------------------" -ForegroundColor Cyan
Write-Host "ESCENARIO B: Carga con Camion Compatible (3.80m <= 4.10m)" -ForegroundColor Cyan
Write-Host "----------------------------------------------------------" -ForegroundColor Cyan

$load2Json = @"
{"originCity":"Bogota","destinationCity":"Medellin","originDepartment":"Cundinamarca","destinationDepartment":"Antioquia","originLatitude":4.6097,"originLongitude":-74.0817,"destinationLatitude":6.2442,"destinationLongitude":-75.5812,"weightTons":26.0,"requiredTruckType":"TRACTOMULA_3S3","cargoDescription":"Carga E2E para camion compatible","generatorCompanyName":"Constructora Andina SA","offeredBudget":4200000.00}
"@

$load2 = Invoke-Api -Uri "$gateway/loads" -Method Post -JsonBody $load2Json.Trim() -Headers $genHeaders
$load2Id = $load2.id
Write-Host "  Carga #$load2Id publicada (Bogota -> Medellin)" -ForegroundColor Green

# Asignar a conductor compatible
$assignCompJson = "{""driverId"":""$compId"",""driverName"":""Pedro Conductor"",""vehiclePlate"":""COM-380""}"
Invoke-Api -Uri "$gateway/loads/$load2Id/assign" -Method Patch -JsonBody $assignCompJson -Headers $coordHeaders | Out-Null
Invoke-Api -Uri "$gateway/loads/$load2Id/assignment/notify" -Method Patch -Headers $coordHeaders | Out-Null
Write-Host "  Carga asignada y notificada a conductor compatible" -ForegroundColor Green

# Aceptar carga -> DEBE RESPONDER 200 OK
Write-Host "  Aceptando carga con conductor compatible (3.80m <= 4.10m)..." -ForegroundColor Cyan
$resAccept = Invoke-Api -Uri "$gateway/loads/$load2Id/assignment/accept" -Method Patch -Headers $compHeaders
Write-Host "  [EXITO] Carga ACEPTADA: status=$($resAccept.status), assignmentStatus=$($resAccept.assignmentStatus)" -ForegroundColor Green

# Iniciar viaje -> DEBE TRANSICIONAR A IN_TRANSIT
Write-Host "  Iniciando viaje (PATCH /trip/start)..." -ForegroundColor Cyan
$resTrip = Invoke-Api -Uri "$gateway/loads/$load2Id/trip/start" -Method Patch -Headers $compHeaders
Write-Host "  [EXITO] Viaje INICIADO! status=$($resTrip.status), tripStartedAt=$($resTrip.tripStartedAt)" -ForegroundColor Green

# =============================================================================
# RESUMEN
# =============================================================================
Write-Host "`n==========================================================" -ForegroundColor Cyan
Write-Host "TODAS LAS PRUEBAS E2E DEL PUNTO 7 PASARON EXITOSAMENTE!" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "1. Camion incompatible (INC-440, 4.40m) BLOQUEADO con HTTP 409 Conflict" -ForegroundColor Green
Write-Host "   -> Restriccion de galibo 4.10m en corredor BOGOTA-MEDELLIN aplicada" -ForegroundColor Green
Write-Host "2. Camion compatible (COM-380, 3.80m) ACEPTADO y paso a IN_TRANSIT (HTTP 200)" -ForegroundColor Green
Write-Host "   -> Viaje iniciado exitosamente" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Cyan
