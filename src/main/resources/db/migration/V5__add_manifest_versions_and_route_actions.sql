CREATE TABLE manifest_version
(
    version    VARCHAR(255) PRIMARY KEY CHECK (length(btrim(version)) > 0),
    schema     JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE activation_route
    ADD COLUMN allowed_actions TEXT[] NOT NULL DEFAULT '{}'::TEXT[],
    ADD COLUMN include_participants BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE activation_route
    ADD CONSTRAINT route_allowed_actions CHECK (array_position(allowed_actions, NULL) IS NULL);
