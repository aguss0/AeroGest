# Pide un token a Keycloak para un usuario de demostración y lo copia al portapapeles,
# listo para pegar en el botón "Authorize" de Swagger (sin el prefijo "Bearer").
# Funciona desde cualquier carpeta: lee el .env del repo por su cuenta.
# Uso:  .\scripts\get-token.ps1              # user1
#       .\scripts\get-token.ps1 -User admin  # admin (para crear vuelos)
param(
    [ValidateSet("user1", "user2", "admin")]
    [string]$User = "user1",
    [string]$KeycloakUrl = "http://localhost:8180"
)
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "load-env.ps1") | Out-Null
$password = if ($User -eq "admin") { $env:DEMO_ADMIN_PASSWORD } else { $env:DEMO_USER_PASSWORD }

try {
    $response = Invoke-RestMethod -Method Post -Uri "$KeycloakUrl/realms/aerogest/protocol/openid-connect/token" `
        -Body @{ grant_type = "password"; client_id = "aerogest-api"; username = $User; password = $password }
} catch {
    throw "Keycloak no entregó el token ($($_.Exception.Message)). ¿Está levantado en $KeycloakUrl? Si cambiaste las claves del .env, reiniciá Keycloak para que reimporte el realm."
}

$response.access_token | Set-Clipboard
$minutes = [int]($response.expires_in / 60)
Write-Host "Token de $User copiado al portapapeles (vence en $minutes minutos)." -ForegroundColor Green
Write-Host "En Swagger: Authorize -> pegá el token tal cual (sin escribir 'Bearer') -> Authorize."
