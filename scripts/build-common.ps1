# Compila, prueba e instala el componente común (aerogest-common) en el repositorio Maven local (~/.m2).
# Hace falta antes de correr o probar con Maven cualquiera de los servicios o el gateway, y cada vez
# que cambia el código de common/. Docker no lo necesita: cada imagen lo instala por su cuenta (ADR 0004).
# Uso (desde la raíz del repo):  .\scripts\build-common.ps1
$ErrorActionPreference = "Stop"
Push-Location (Join-Path $PSScriptRoot "..\common")
try {
    .\mvnw.cmd -B install
    if ($LASTEXITCODE -ne 0) { throw "No se pudo instalar aerogest-common" }
    Write-Host "aerogest-common 1.0.0 instalado en el repositorio Maven local" -ForegroundColor Green
} finally {
    Pop-Location
}
