# Genera los esqueletos Maven de ambos servicios con Spring Initializr
# y los copia a flight-service/ y reservation-service/ SIN pisar los archivos de este repo.
# Uso (desde la raíz del repo):  .\scripts\init-projects.ps1
param([string]$BootVersion = "4.1.2")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$deps = "web,data-jpa,validation,security,oauth2-resource-server,flyway,postgresql,actuator,testcontainers"

function New-Service([string]$artifactId, [string]$name, [string]$pkg, [string]$dir) {
    $zip = Join-Path $env:TEMP "$artifactId.zip"
    $tmp = Join-Path $env:TEMP "$artifactId-init"
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    curl.exe -sSf https://start.spring.io/starter.zip `
        -d type=maven-project -d language=java `
        -d bootVersion=$BootVersion -d javaVersion=21 `
        -d groupId=ar.edu.utn.frc -d artifactId=$artifactId -d name=$name `
        -d packageName=$pkg -d dependencies=$deps -o $zip
    if ($LASTEXITCODE -ne 0) { throw "Falló la descarga desde start.spring.io para $artifactId" }
    Expand-Archive $zip -DestinationPath $tmp -Force
    $target = Join-Path $root $dir
    Copy-Item "$tmp\*" $target -Recurse -Force
    # Usamos application.yml (ya incluido en el repo), no application.properties
    Remove-Item (Join-Path $target "src\main\resources\application.properties") -ErrorAction SilentlyContinue
    Write-Host "OK: $dir"
}

New-Service "flight-service"      "flight-service"      "ar.edu.utn.frc.aerogest.flight"      "flight-service"
New-Service "reservation-service" "reservation-service" "ar.edu.utn.frc.aerogest.reservation" "reservation-service"
