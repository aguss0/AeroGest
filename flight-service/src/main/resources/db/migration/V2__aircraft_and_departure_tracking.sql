-- Flight Service: datos para verificar la salida real del vuelo con OpenSky Network (RN-16, ADR 0006)
--  callsign              indicativo de llamada ICAO que transmite la aeronave (p. ej. ARG1132)
--  aircraft_icao24       dirección del transpondedor (6 dígitos hexadecimales, en minúsculas)
--  departure_checked_at  última vez que se consultó OpenSky con una respuesta válida
--  departure_detected_at cuándo se detectó el despegue (y se cerró el vuelo por eso)

ALTER TABLE flights
    ADD COLUMN callsign              VARCHAR(8),
    ADD COLUMN aircraft_icao24       VARCHAR(6),
    ADD COLUMN departure_checked_at  TIMESTAMPTZ,
    ADD COLUMN departure_detected_at TIMESTAMPTZ;

ALTER TABLE flights
    -- Los dos datos de la aeronave van juntos o ninguno
    ADD CONSTRAINT ck_flights_aircraft_pair CHECK ((callsign IS NULL) = (aircraft_icao24 IS NULL)),
    ADD CONSTRAINT ck_flights_callsign      CHECK (callsign IS NULL OR callsign ~ '^[A-Z0-9]{3,8}$'),
    ADD CONSTRAINT ck_flights_icao24        CHECK (aircraft_icao24 IS NULL OR aircraft_icao24 ~ '^[0-9a-f]{6}$');

-- Candidatos del job: vuelos programados con aeronave asignada, por fecha de salida
CREATE INDEX idx_flights_tracking ON flights (departure_at)
    WHERE status = 'SCHEDULED' AND aircraft_icao24 IS NOT NULL;
