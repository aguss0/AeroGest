# Carga las variables de ../.env en la sesión actual de PowerShell.
# Uso (desde la raíz del repo):  . .\scripts\load-env.ps1
$envFile = Join-Path $PSScriptRoot "..\.env"
if (-not (Test-Path $envFile)) { throw "No existe .env. Copiá .env.example como .env primero." }
Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
    $k, $v = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($k.Trim(), $v.Trim(), 'Process')
}
Write-Host "Variables de entorno cargadas desde .env"
