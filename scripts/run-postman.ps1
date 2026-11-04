# Ejecuta la colección Postman por consola con Newman (requiere Node.js; npx lo descarga la primera vez).
# Toma las contraseñas del .env, así no hay que copiarlas al entorno de Postman.
# Uso (desde la raíz del repo, con el sistema levantado):
#   . .\scripts\load-env.ps1
#   .\scripts\run-postman.ps1                                   # carpetas automáticas 00 a 06
#   .\scripts\run-postman.ps1 -Folders "07 [Manual] Flight Service apagado (RN-10)"   # con Flight detenido
#   .\scripts\run-postman.ps1 -Folders "09 [Internet] OpenSky: salida real del vuelo (RN-16)"   # OpenSky real
param(
    [string[]]$Folders = @(
        "00 Autenticación (Keycloak)",
        "01 Vuelos (Flight Service)",
        "02 Pasajeros (Reservation Service)",
        "03 Reservas (proceso principal)",
        "04 Cancelación (RN-07)",
        "05 Vuelo cancelado o no reservable (RN-04)",
        "06 Capacidad agotada (RN-05)"
    ),
    [string]$NewmanVersion = "6.2.1"
)
$ErrorActionPreference = "Stop"

foreach ($v in "DEMO_ADMIN_PASSWORD", "DEMO_USER_PASSWORD", "RESERVATION_CLIENT_SECRET") {
    if (-not [Environment]::GetEnvironmentVariable($v)) {
        throw "Falta la variable $v. Ejecutá primero: . .\scripts\load-env.ps1"
    }
}

$root = Join-Path $PSScriptRoot ".."
$arguments = @(
    "--yes", "newman@$NewmanVersion", "run",
    (Join-Path $root "postman\AeroGest.postman_collection.json"),
    "--environment", (Join-Path $root "postman\aerogest-local.postman_environment.json"),
    "--env-var", "adminPassword=$env:DEMO_ADMIN_PASSWORD",
    "--env-var", "userPassword=$env:DEMO_USER_PASSWORD",
    "--env-var", "reservationClientSecret=$env:RESERVATION_CLIENT_SECRET"
)
foreach ($folder in $Folders) { $arguments += @("--folder", $folder) }

# npm escribe avisos en stderr: con "Stop", PowerShell 5.1 los trataría como errores y cortaría la ejecución
$ErrorActionPreference = "Continue"
& npx @arguments
exit $LASTEXITCODE
