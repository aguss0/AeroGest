-- Reservation Service: pasajeros y reservas.
-- Los usuarios viven en Keycloak: aquí solo se guarda el identificador del usuario
-- (claim "sub" del token), sin clave foránea.
-- flight_id es una referencia LÓGICA: la tabla de vuelos vive en la BD de Flight Service.

CREATE TABLE passengers (
    id              BIGSERIAL    PRIMARY KEY,
    owner_user_id   VARCHAR(64)  NOT NULL,            -- sub del usuario que registró al pasajero
    first_name      VARCHAR(100) NOT NULL,
    last_name       VARCHAR(100) NOT NULL,
    document_type   VARCHAR(10)  NOT NULL,
    document_number VARCHAR(30)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_passengers_document UNIQUE (document_type, document_number),     -- RN-14
    CONSTRAINT ck_passengers_document_type CHECK (document_type IN ('DNI', 'PASSPORT'))
);

CREATE INDEX idx_passengers_owner ON passengers (owner_user_id);

CREATE TABLE reservations (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    passenger_id        BIGINT      NOT NULL REFERENCES passengers (id),
    flight_id           BIGINT      NOT NULL,
    created_by_user_id  VARCHAR(64) NOT NULL,         -- sub del usuario autenticado (RN-09)
    status              VARCHAR(10) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT ck_reservations_status CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'REJECTED'))
);

-- RN-06: un pasajero no puede tener dos reservas activas para el mismo vuelo
-- (lo garantiza la BD, incluso con solicitudes concurrentes)
CREATE UNIQUE INDEX uq_reservations_active_passenger_flight
    ON reservations (passenger_id, flight_id)
    WHERE status IN ('PENDING', 'CONFIRMED');

CREATE INDEX idx_reservations_created_by ON reservations (created_by_user_id);
CREATE INDEX idx_reservations_flight     ON reservations (flight_id);
CREATE INDEX idx_reservations_pending    ON reservations (created_at) WHERE status = 'PENDING';
