# Prueba de humo de Flight Service contra los servicios reales (Keycloak + Flight Service en marcha).
# Uso (desde la raíz del repo, con el .env cargado):
#   . .\scripts\load-env.ps1
#   .\scripts\smoke-flights.ps1
param(
    [string]$FlightUrl = "http://localhost:8081",
    [string]$KeycloakUrl = "http://localhost:8180"
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
$user  = Get-Token @{ grant_type = "password"; client_id = "aerogest-api"; username = "user1"; password = $env:DEMO_USER_PASSWORD }
$svc   = Get-Token @{ grant_type = "client_credentials"; client_id = $env:RESERVATION_CLIENT_ID; client_secret = $env:RESERVATION_CLIENT_SECRET }

Add-Type -AssemblyName System.Net.Http
$script:http = New-Object System.Net.Http.HttpClient

function Call([string]$Method, [string]$Path, [string]$Token, $Body = $null) {
    $request = New-Object System.Net.Http.HttpRequestMessage
    $request.Method = New-Object System.Net.Http.HttpMethod($Method)
    $request.RequestUri = [Uri]"$FlightUrl$Path"
    if ($Token) { [void]$request.Headers.TryAddWithoutValidation("Authorization", "Bearer $Token") }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 5
        $request.Content = New-Object System.Net.Http.StringContent($json, [Text.Encoding]::UTF8, "application/json")
    }
    try {
        $response = $script:http.SendAsync($request).Result
        $text = $response.Content.ReadAsStringAsync().Result
        $parsed = $null
        if ($text) { try { $parsed = $text | ConvertFrom-Json } catch { } }
        return [pscustomobject]@{ Status = [int]$response.StatusCode; Body = $parsed }
    } catch {
        Write-Host ("  (sin respuesta HTTP: {0})" -f $_.Exception.Message) -ForegroundColor Yellow
        return [pscustomobject]@{ Status = -1; Body = $null }
    }
}

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

$fmt = "yyyy-MM-dd'T'HH:mm:ss'Z'"
$dep = (Get-Date).ToUniversalTime().AddDays(30)
$depStr = $dep.ToString($fmt)
$arrStr = $dep.AddHours(2).ToString($fmt)
$code = "SMK" + (Get-Random -Minimum 100000 -Maximum 999999)
$newFlight = @{ code = $code; origin = "COR"; destination = "EZE"; departureAt = $depStr; arrivalAt = $arrStr; capacity = 1 }

Write-Host "`n== Seguridad ==" -ForegroundColor Cyan
Check "Sin token -> 401" (Call GET "/api/v1/flights" $null) 401 "UNAUTHENTICATED"
Check "USER no puede crear vuelos -> 403" (Call POST "/api/v1/flights" $user $newFlight) 403 "ACCESS_DENIED"
Check "USER no puede usar /internal -> 403" (Call GET "/internal/v1/flights/1/availability" $user) 403 "ACCESS_DENIED"
Check "ADMIN no puede usar /internal -> 403" (Call GET "/internal/v1/flights/1/availability" $admin) 403 "ACCESS_DENIED"
Check "SERVICE no puede crear vuelos -> 403" (Call POST "/api/v1/flights" $svc $newFlight) 403 "ACCESS_DENIED"

Write-Host "`n== Vuelos ==" -ForegroundColor Cyan
$created = Call POST "/api/v1/flights" $admin $newFlight
Check "ADMIN crea un vuelo -> 201" $created 201
$id = $created.Body.id
Check "Código duplicado -> 409" (Call POST "/api/v1/flights" $admin $newFlight) 409 "DUPLICATE_FLIGHT_CODE"
$badSchedule = $newFlight.Clone(); $badSchedule.code = "BAD" + (Get-Random -Minimum 100000 -Maximum 999999); $badSchedule.arrivalAt = $dep.AddHours(-1).ToString($fmt)
Check "Llegada anterior a la salida -> 400" (Call POST "/api/v1/flights" $admin $badSchedule) 400 "INVALID_SCHEDULE"
$badCapacity = $newFlight.Clone(); $badCapacity.code = "CAP" + (Get-Random -Minimum 100000 -Maximum 999999); $badCapacity.capacity = 0
Check "Capacidad 0 -> 400" (Call POST "/api/v1/flights" $admin $badCapacity) 400 "VALIDATION_ERROR"
Check "USER consulta el vuelo -> 200" (Call GET "/api/v1/flights/$id" $user) 200
Check "USER lista vuelos -> 200" (Call GET "/api/v1/flights?origin=COR&size=5" $user) 200
Check "Vuelo inexistente -> 404" (Call GET "/api/v1/flights/999999" $user) 404 "FLIGHT_NOT_FOUND"
Check "SCHEDULED -> COMPLETED es inválido -> 409" (Call PATCH "/api/v1/flights/$id/status" $admin @{ status = "COMPLETED" }) 409 "INVALID_STATUS_TRANSITION"
Check "Estado inexistente -> 400" (Call PATCH "/api/v1/flights/$id/status" $admin @{ status = "VOLANDO" }) 400 "MALFORMED_REQUEST"

Write-Host "`n== Plazas (capacidad 1) ==" -ForegroundColor Cyan
$r1 = [guid]::NewGuid().ToString(); $r2 = [guid]::NewGuid().ToString(); $r3 = [guid]::NewGuid().ToString()
$avail = Call GET "/internal/v1/flights/$id/availability" $svc
Check "Disponibilidad con SERVICE -> 200" $avail 200
Check "El vuelo admite reservas" ([pscustomobject]@{ Status = $(if ($avail.Body.acceptsReservations -eq $true) { 200 } else { 0 }); Body = $null }) 200
Check "Asignar plaza a la reserva 1 -> 201" (Call POST "/internal/v1/flights/$id/seat-allocations" $svc @{ reservationId = $r1 }) 201
Check "Repetir la asignación (idempotente) -> 200" (Call POST "/internal/v1/flights/$id/seat-allocations" $svc @{ reservationId = $r1 }) 200
Check "Última plaza ya ocupada -> 409" (Call POST "/internal/v1/flights/$id/seat-allocations" $svc @{ reservationId = $r2 }) 409 "NO_AVAILABILITY"
$full = Call GET "/internal/v1/flights/$id/availability" $svc
Check "Plazas disponibles = 0 (no se descontó dos veces)" ([pscustomobject]@{ Status = $(if ($full.Body.availableSeats -eq 0) { 200 } else { 0 }); Body = $null }) 200
Check "Liberar la plaza de la reserva 1 -> 204" (Call DELETE "/internal/v1/flights/$id/seat-allocations/$r1" $svc) 204
Check "Liberar otra vez (idempotente) -> 204" (Call DELETE "/internal/v1/flights/$id/seat-allocations/$r1" $svc) 204
$freed = Call GET "/internal/v1/flights/$id/availability" $svc
Check "Plazas disponibles = 1 (se liberó una sola vez)" ([pscustomobject]@{ Status = $(if ($freed.Body.availableSeats -eq 1) { 200 } else { 0 }); Body = $null }) 200
Check "Asignar una reserva liberada -> 409" (Call POST "/internal/v1/flights/$id/seat-allocations" $svc @{ reservationId = $r1 }) 409 "ALLOCATION_CONFLICT"
Check "La reserva 2 ahora sí obtiene plaza -> 201" (Call POST "/internal/v1/flights/$id/seat-allocations" $svc @{ reservationId = $r2 }) 201
Check "Liberar una reserva que nunca ocupó plaza -> 204" (Call DELETE "/internal/v1/flights/$id/seat-allocations/$r3" $svc) 204
Check "Esa asignación, si llega tarde, se rechaza -> 409" (Call POST "/internal/v1/flights/$id/seat-allocations" $svc @{ reservationId = $r3 }) 409 "ALLOCATION_CONFLICT"
Check "Disponibilidad de vuelo inexistente -> 404" (Call GET "/internal/v1/flights/999999/availability" $svc) 404 "FLIGHT_NOT_FOUND"

Write-Host "`n== Vuelo que no admite reservas ==" -ForegroundColor Cyan
Check "ADMIN cierra el vuelo -> 200" (Call PATCH "/api/v1/flights/$id/status" $admin @{ status = "CLOSED" }) 200
Check "Asignar en un vuelo cerrado -> 409" (Call POST "/internal/v1/flights/$id/seat-allocations" $svc @{ reservationId = [guid]::NewGuid().ToString() }) 409 "FLIGHT_NOT_RESERVABLE"
Check "ADMIN lo cancela -> 200" (Call PATCH "/api/v1/flights/$id/status" $admin @{ status = "CANCELLED" }) 200
Check "Un vuelo cancelado no se puede modificar -> 409" (Call PUT "/api/v1/flights/$id" $admin @{ origin = "COR"; destination = "EZE"; departureAt = $depStr; arrivalAt = $arrStr; capacity = 5 }) 409 "FLIGHT_NOT_MODIFIABLE"

Write-Host ("`nResultado: {0} correctas, {1} fallidas" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
if ($script:fail -gt 0) { exit 1 }
