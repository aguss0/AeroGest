-- Flight Service: vuelos y asignaciones de plaza (idempotencia por reservation_id)

CREATE TABLE flights (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(30)  NOT NULL,
    origin       VARCHAR(3)   NOT NULL,
    destination  VARCHAR(3)   NOT NULL,
    departure_at TIMESTAMPTZ  NOT NULL,
    arrival_at   TIMESTAMPTZ  NOT NULL,
    capacity     INTEGER      NOT NULL,
    seats_taken  INTEGER      NOT NULL DEFAULT 0,
    status       VARCHAR(20)  NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_flights_code        UNIQUE (code),                                        -- RN-01
    CONSTRAINT ck_flights_times       CHECK (arrival_at > departure_at),                    -- RN-02
    CONSTRAINT ck_flights_capacity    CHECK (capacity > 0),                                 -- RN-03 / RN-13
    CONSTRAINT ck_flights_seats       CHECK (seats_taken >= 0 AND seats_taken <= capacity), -- RN-05
    CONSTRAINT ck_flights_route       CHECK (origin <> destination),                        -- RN-11
    CONSTRAINT ck_flights_iata        CHECK (origin ~ '^[A-Z]{3}$' AND destination ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_flights_status      CHECK (status IN ('SCHEDULED', 'CLOSED', 'CANCELLED', 'COMPLETED'))
);

CREATE INDEX idx_flights_route_departure ON flights (origin, destination, departure_at);
CREATE INDEX idx_flights_status          ON flights (status);

CREATE TABLE seat_allocations (
    reservation_id UUID        PRIMARY KEY,           -- RN-15: una asignación por reserva
    flight_id      BIGINT      NOT NULL REFERENCES flights (id),
    status         VARCHAR(10) NOT NULL,
    allocated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at    TIMESTAMPTZ,

    CONSTRAINT ck_seat_allocations_status CHECK (status IN ('ALLOCATED', 'RELEASED'))
);

CREATE INDEX idx_seat_allocations_flight ON seat_allocations (flight_id);
