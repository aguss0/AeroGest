# Evidencia: verificación de salida real contra OpenSky Network (RN-16)

Fecha: 2026-11-04, ~11:49 UTC. Sistema levantado con `docker compose up -d --build`; todas las
llamadas pasan por el gateway (`http://localhost:8080`). OpenSky real, acceso anónimo.

## 1. Aeronave real en el aire
Consulta directa a OpenSky (recuadro sobre el centro de Argentina). Una de las filas:

```
["e0b215","ARG1324 ","Argentina",1793792601,1793792601,-58.6502,-34.7093,5615.94,false,192.45,...]
```

`icao24 = e0b215`, `callsign = "ARG1324 "` (con espacios a la derecha), `on_ground = false`.
Respuesta `200` con `X-Rate-Limit-Remaining: 398`.

## 2. Recorrido por la API

| Paso | Solicitud | Resultado |
|---|---|---|
| Alta con la aeronave real, salida programada dentro de 1 h | `POST /api/v1/flights` (admin) con `callsign=ARG1324`, `aircraftIcao24=e0b215` | `201`, `SCHEDULED` |
| Mismo avión, otro callsign (`XXX999`) | `POST /api/v1/flights/{id}/departure-check` (admin) | `200`, `result=OTHER_FLIGHT`, sigue `SCHEDULED`, `observedCallsign=ARG1324` |
| Un USER intenta forzar la verificación | `POST .../departure-check` (user1) | `403 ACCESS_DENIED` (gateway) |
| Verificación del vuelo con `ARG1324` | `POST .../departure-check` (admin) | `200`, `result=DEPARTURE_DETECTED`, `status=CLOSED`, `observedOnGround=false`, `lastContact=2026-11-04T11:49:28Z` |
| Reserva en ese vuelo (salida programada 12:49, todavía futura) | `POST /api/v1/reservations` (user1) | `409`; la reserva queda `REJECTED` |
| Vuelo sin aeronave | `POST .../departure-check` (admin) | `409 FLIGHT_NOT_TRACKABLE` |

## 3. Logs

Flight Service, al cerrar el vuelo (mismo correlation id que la solicitud del gateway):

```
INFO [0c54737a-df22-4730-80ee-66131a598ec4] DepartureVerificationService : RN-16: vuelo 8 (OS971) cerrado: OpenSky muestra ARG1324 en el aire
```

Primera corrida automática del job (correlation id propio):

```
INFO [opensky-55c9a339] DepartureVerificationService : OpenSky: 1 vuelos verificados, 0 cerrados por despegue
```

(El único candidato que quedaba era el del callsign `XXX999`: `OTHER_FLIGHT`, no se cierra.)

Reservation Service, al intentar reservar:

```
INFO [2dc52d83-c26f-4259-94eb-22fd864e9c94] ReservationService : Reserva 44f209aa-a271-449f-a535-38d8ef9ef072 rechazada por Flight Service: FLIGHT_NOT_RESERVABLE
```

## Cómo repetirlo
1. Buscar una aeronave en el aire: `https://opensky-network.org/api/states/all?lamin=-40&lomin=-66&lamax=-30&lomax=-56`
   y tomar una fila con `on_ground=false` (posición 8) y callsign `ARG...` (posición 1).
2. Crear un vuelo con ese `callsign` y `aircraftIcao24` y salida programada futura (carpeta 09 de Postman).
3. `POST /api/v1/flights/{id}/departure-check` como admin.

Los datos son en tiempo real: el avión de esta evidencia ya no estará en el aire cuando se repita.
