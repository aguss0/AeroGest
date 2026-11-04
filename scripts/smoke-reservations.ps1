# Prueba de humo de punta a punta: Keycloak + API Gateway + Flight Service + Reservation Service reales.
# Todas las llamadas pasan por el gateway, como las haría un cliente (ADR 0005).
# Cubre el proceso de reserva, cancelación, seguridad y la última plaza bajo concurrencia.
# Uso (desde la raíz del repo, con el .env cargado):
#   . .\scripts\load-env.ps1
#   .\scripts\smoke-reservations.ps1
#   .\scripts\smoke-reservations.ps1 -IncludeFailures   # además apaga/congela Flight (requiere el sistema en Docker)
param(
    [string]$GatewayUrl = "http://localhost:8080",
    [string]$KeycloakUrl = "http://localhost:8180",
    [int]$Concurrent = 10,
    [switch]$IncludeFailures,
    [string]$FlightContainer = "aerogest-flight-service"
)
$ErrorActionPreference = "Stop"

foreach ($v in "DEMO_ADMIN_PASSWORD", "DEMO_USER_PASSWORD", "RESERVATION_CLIENT_ID", "RESERVATION_CLIENT_SECRET") {
    if (-not [Environment]::GetEnvironmentVariable($v)) {
        throw "Falta la variable $v. Ejecutá primero: . .\scripts\load-env.ps1"
    }
}

$tokenUrl = "$KeycloakUrl/realms/aerogest/protocol/openid-connect/token"
function Get-Token([hashtable]$Form) {
    (Invoke-RestMethod -Method Post -Uri $tokenUrl -Body $Form).access_token
}
$admin = Get-Token @{ grant_type = "password"; client_id = "aerogest-api"; username = "admin"; password = $env:DEMO_ADMIN_PASSWORD }
$user1 = Get-Token @{ grant_type = "password"; client_id = "aerogest-api"; username = "user1"; password = $env:DEMO_USER_PASSWORD }
$user2 = Get-Token @{ grant_type = "password"; client_id = "aerogest-api"; username = "user2"; password = $env:DEMO_USER_PASSWORD }
$svc   = Get-Token @{ grant_type = "client_credentials"; client_id = $env:RESERVATION_CLIENT_ID; client_secret = $env:RESERVATION_CLIENT_SECRET }

# .NET Framework limita a 2 las conexiones simultáneas por servidor: sin esto, las solicitudes
# "simultáneas" de la prueba de la última plaza se enviarían de a dos.
[Net.ServicePointManager]::DefaultConnectionLimit = 100
Add-Type -AssemblyName System.Net.Http
$script:http = New-Object System.Net.Http.HttpClient
$script:http.Timeout = [TimeSpan]::FromSeconds(60)

function New-Request([string]$Method, [string]$Url, [string]$Token, $Body = $null) {
    $request = New-Object System.Net.Http.HttpRequestMessage
    $request.Method = New-Object System.Net.Http.HttpMethod($Method)
    $request.RequestUri = [Uri]$Url
    if ($Token) { [void]$request.Headers.TryAddWithoutValidation("Authorization", "Bearer $Token") }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 5
        $request.Content = New-Object System.Net.Http.StringContent($json, [Text.Encoding]::UTF8, "application/json")
    }
    return $request
}

function Read-Response($Response) {
    $text = $Response.Content.ReadAsStringAsync().Result
    $parsed = $null
    if ($text) { try { $parsed = $text | ConvertFrom-Json } catch { } }
    return [pscustomobject]@{ Status = [int]$Response.StatusCode; Body = $parsed }
}

function Call([string]$Method, [string]$Url, [string]$Token, $Body = $null) {
    try {
        return Read-Response ($script:http.SendAsync((New-Request $Method $Url $Token $Body)).Result)
    } catch {
        Write-Host ("  (sin respuesta HTTP: {0})" -f $_.Exception.Message) -ForegroundColor Yellow
        return [pscustomobject]@{ Status = -1; Body = $null }
    }
}
# Flt y Rsv separan las llamadas por servicio para leer mejor el script; las dos van al gateway.
# ("r" ya es un alias de PowerShell, por eso no se usan nombres de una letra)
function Flt([string]$Method, [string]$Path, [string]$Token, $Body = $null) { Call $Method "$GatewayUrl$Path" $Token $Body }
function Rsv([string]$Method, [string]$Path, [string]$Token, $Body = $null) { Call $Method "$GatewayUrl$Path" $Token $Body }

$script:pass = 0
$script:fail = 0
function Check([string]$Name, $Response, [int]$ExpectedStatus, [string]$ExpectedCode = "") {
    $actualCode = ""
    if ($Response.Body -and $Response.Body.code) { $actualCode = [string]$Response.Body.code }
    $ok = ($Response.Status -eq $ExpectedStatus)
    if ($ok -and $ExpectedCode) { $ok = ($actualCode -eq $ExpectedCode) }
    if ($ok) {
        $script:pass++
        Write-Host ("PASS  {0} (HTTP {1} {2})" -f $Name, $Response.Status, $actualCode) -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host ("FAIL  {0}: esperado {1} {2}, recibido {3} {4}" -f $Name, $ExpectedStatus, $ExpectedCode, $Response.Status, $actualCode) -ForegroundColor Red
    }
}
function CheckValue([string]$Name, $Actual, $Expected) {
    if ($Actual -eq $Expected) {
        $script:pass++
        Write-Host ("PASS  {0} ({1})" -f $Name, $Actual) -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host ("FAIL  {0}: esperado {1}, recibido {2}" -f $Name, $Expected, $Actual) -ForegroundColor Red
    }
}

# ---------- datos de la corrida (únicos, así el script se puede repetir sin limpiar la base) ----------
$fmt = "yyyy-MM-dd'T'HH:mm:ss'Z'"
$dep = (Get-Date).ToUniversalTime().AddDays(30)
$run = Get-Random -Minimum 1000 -Maximum 9999
$script:dni = [long](Get-Random -Minimum 10000000 -Maximum 89000000)
function Next-Dni { $script:dni++; return [string]$script:dni }

function New-Flight([string]$Suffix, [int]$Capacity) {
    $body = @{ code = "RSV$run$Suffix"; origin = "COR"; destination = "EZE"
               departureAt = $dep.ToString($fmt); arrivalAt = $dep.AddHours(2).ToString($fmt); capacity = $Capacity }
    $created = Flt POST "/api/v1/flights" $admin $body
    if ($created.Status -ne 201) { throw "No se pudo crear el vuelo de prueba (HTTP $($created.Status))" }
    return $created.Body.id
}
function New-Passenger([string]$Token, [string]$Name) {
    $created = Rsv POST "/api/v1/passengers" $Token @{ firstName = $Name; lastName = "Smoke"; documentType = "DNI"; documentNumber = (Next-Dni) }
    if ($created.Status -ne 201) { throw "No se pudo registrar el pasajero $Name (HTTP $($created.Status))" }
    return $created.Body.id
}
function Seats([long]$FlightId) { (Flt GET "/api/v1/flights/$FlightId" $user1).Body.availableSeats }
function Reserve([string]$Token, [long]$PassengerId, [long]$FlightId) {
    Rsv POST "/api/v1/reservations" $Token @{ passengerId = $PassengerId; flightId = $FlightId }
}
function StatusesOnFlight([long]$FlightId) {
    $all = (Rsv GET "/api/v1/reservations?size=100" $admin).Body.content | Where-Object { $_.flightId -eq $FlightId }
    return @($all | ForEach-Object { $_.status })
}

Write-Host "`n== Seguridad (RN-09) ==" -ForegroundColor Cyan
Check "Sin token -> 401" (Rsv GET "/api/v1/reservations" $null) 401 "UNAUTHENTICATED"
Check "El rol SERVICE no usa la API pública -> 403" (Rsv GET "/api/v1/reservations" $svc) 403 "ACCESS_DENIED"
Check "El gateway no publica la API interna, ni para el rol SERVICE -> 403" (Flt GET "/internal/v1/flights/1/availability" $svc) 403 "ACCESS_DENIED"
Check "El gateway enruta a Flight con un token USER -> 200" (Flt GET "/api/v1/flights?size=1" $user1) 200

Write-Host "`n== Pasajeros (RN-14) ==" -ForegroundColor Cyan
$dniAna = Next-Dni
$p1Resp = Rsv POST "/api/v1/passengers" $user1 @{ firstName = "Ana"; lastName = "Smoke"; documentType = "DNI"; documentNumber = $dniAna }
Check "user1 registra a Ana -> 201" $p1Resp 201
$p1 = $p1Resp.Body.id
$dotted = $dniAna.Insert(2, ".").Insert(6, ".")
Check "Mismo DNI con puntos, otro usuario -> 409" (Rsv POST "/api/v1/passengers" $user2 @{ firstName = "Otra"; lastName = "Smoke"; documentType = "DNI"; documentNumber = $dotted }) 409 "DUPLICATE_PASSENGER_DOCUMENT"
Check "DNI con letras -> 400" (Rsv POST "/api/v1/passengers" $user1 @{ firstName = "Ana"; lastName = "Smoke"; documentType = "DNI"; documentNumber = "ABC123" }) 400 "INVALID_PASSENGER_DATA"
Check "user2 no ve un pasajero de user1 -> 403" (Rsv GET "/api/v1/passengers/$p1" $user2) 403 "ACCESS_DENIED"
$p2 = New-Passenger $user2 "Bruno"
$p3 = New-Passenger $user2 "Carla"

Write-Host "`n== Reserva ==" -ForegroundColor Cyan
$flightA = New-Flight "A" 2
$created = Reserve $user1 $p1 $flightA
Check "user1 reserva a Ana -> 201" $created 201
CheckValue "La reserva queda CONFIRMED" $created.Body.status "CONFIRMED"
$reservation = $created.Body.id
CheckValue "Flight descontó la plaza (2 -> 1)" (Seats $flightA) 1
Check "Misma reserva otra vez -> 409" (Reserve $user1 $p1 $flightA) 409 "DUPLICATE_ACTIVE_RESERVATION"
Check "user2 no ve la reserva de user1 -> 403" (Rsv GET "/api/v1/reservations/$reservation" $user2) 403 "ACCESS_DENIED"
Check "user2 no la puede cancelar -> 403" (Rsv POST "/api/v1/reservations/$reservation/cancel" $user2) 403 "ACCESS_DENIED"
Check "user2 no reserva con el pasajero de user1 -> 403" (Reserve $user2 $p1 $flightA) 403 "ACCESS_DENIED"
Check "Vuelo inexistente -> 404" (Reserve $user1 $p1 999999999) 404 "FLIGHT_NOT_FOUND"
Check "Solicitud sin vuelo -> 400" (Rsv POST "/api/v1/reservations" $user1 @{ passengerId = $p1 }) 400 "VALIDATION_ERROR"
Check "ADMIN ve la reserva -> 200" (Rsv GET "/api/v1/reservations/$reservation" $admin) 200

Write-Host "`n== Cancelación (RN-07) ==" -ForegroundColor Cyan
$cancelled = Rsv POST "/api/v1/reservations/$reservation/cancel" $user1
Check "user1 cancela su reserva -> 200" $cancelled 200
CheckValue "La reserva queda CANCELLED" $cancelled.Body.status "CANCELLED"
CheckValue "Flight liberó la plaza (1 -> 2)" (Seats $flightA) 2
Check "Cancelar otra vez -> 409" (Rsv POST "/api/v1/reservations/$reservation/cancel" $user1) 409 "INVALID_RESERVATION_STATE"
CheckValue "La plaza no se liberó dos veces (sigue en 2)" (Seats $flightA) 2
Check "Tras cancelar, Ana puede volver a reservar -> 201" (Reserve $user1 $p1 $flightA) 201

Write-Host "`n== Vuelo que no admite reservas (RN-04) ==" -ForegroundColor Cyan
$flightB = New-Flight "B" 5
Check "ADMIN cancela el vuelo -> 200" (Flt PATCH "/api/v1/flights/$flightB/status" $admin @{ status = "CANCELLED" }) 200
Check "Reservar en un vuelo cancelado -> 409" (Reserve $user2 $p2 $flightB) 409 "FLIGHT_NOT_RESERVABLE"
CheckValue "La reserva quedó REJECTED" ((StatusesOnFlight $flightB) -join ",") "REJECTED"

Write-Host "`n== Última plaza: $Concurrent reservas simultáneas (RN-05) ==" -ForegroundColor Cyan
$flightC = New-Flight "C" 1
$passengers = 1..$Concurrent | ForEach-Object { New-Passenger $user1 "Concurrente$_" }
# Se arman todas las solicitudes y se disparan sin esperar respuesta; recién después se espera a todas
$requests = $passengers | ForEach-Object { New-Request POST "$GatewayUrl/api/v1/reservations" $user1 @{ passengerId = $_; flightId = $flightC } }
$tasks = @($requests | ForEach-Object { $script:http.SendAsync($_) })
[System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$tasks)
$results = @($tasks | ForEach-Object { Read-Response $_.Result })
CheckValue "Exactamente una reserva confirmada (201)" @($results | Where-Object { $_.Status -eq 201 }).Count 1
CheckValue "Las demás rechazadas por falta de plaza (409 NO_AVAILABILITY)" @($results | Where-Object { $_.Status -eq 409 -and $_.Body.code -eq "NO_AVAILABILITY" }).Count ($Concurrent - 1)
CheckValue "Flight no sobrevendió: plazas disponibles = 0" (Seats $flightC) 0
$statuses = StatusesOnFlight $flightC
CheckValue "En la base: 1 CONFIRMED" @($statuses | Where-Object { $_ -eq "CONFIRMED" }).Count 1
CheckValue "En la base: $($Concurrent - 1) REJECTED" @($statuses | Where-Object { $_ -eq "REJECTED" }).Count ($Concurrent - 1)

if ($IncludeFailures) {
    function Wait-FlightUp {
        for ($i = 0; $i -lt 60; $i++) {
            # Mientras arranca, el gateway responde 503 UPSTREAM_UNAVAILABLE: se consulta hasta que Flight responda
            try {
                if ((Invoke-WebRequest -UseBasicParsing -Uri "$GatewayUrl/api/v1/flights?size=1" -Headers @{ Authorization = "Bearer $admin" } -TimeoutSec 2).StatusCode -eq 200) { return }
            } catch { }
            Start-Sleep -Seconds 2
        }
        throw "Flight Service no volvió a estar disponible"
    }
    function Latest-Status([string]$Token, [long]$PassengerId) {
        ((Rsv GET "/api/v1/reservations?size=100" $Token).Body.content | Where-Object { $_.passengerId -eq $PassengerId } | Select-Object -First 1).status
    }

    Write-Host "`n== Flight Service fuera de servicio (RN-10) ==" -ForegroundColor Cyan
    docker stop $FlightContainer | Out-Null
    try {
        Check "Reservar con Flight apagado -> 503" (Reserve $user2 $p2 $flightA) 503 "FLIGHT_SERVICE_UNAVAILABLE"
        # Tampoco se pudo liberar: queda PENDING (nunca CONFIRMED) y la resuelve el job de reconciliación
        CheckValue "La reserva no quedó confirmada (PENDING)" (Latest-Status $user2 $p2) "PENDING"
    } finally {
        docker start $FlightContainer | Out-Null
        Wait-FlightUp
    }
    CheckValue "Flight no ocupó plazas mientras estuvo caído" (Seats $flightA) 1

    Write-Host "`n== Flight Service no responde a tiempo (RN-10) ==" -ForegroundColor Cyan
    docker pause $FlightContainer | Out-Null
    try {
        # El proceso congelado acepta la conexión pero no responde: vence el read timeout
        Check "Reservar con Flight congelado -> 504" (Reserve $user2 $p3 $flightA) 504 "FLIGHT_SERVICE_TIMEOUT"
        CheckValue "La reserva no quedó confirmada (PENDING)" (Latest-Status $user2 $p3) "PENDING"
    } finally {
        docker unpause $FlightContainer | Out-Null
    }
    Write-Host "  Las reservas PENDING de esta sección las resuelve el job (REJECTED) en unos 6 minutos." -ForegroundColor DarkGray
}

Write-Host ("`nResultado: {0} correctas, {1} fallidas" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
if ($script:fail -gt 0) { exit 1 }
