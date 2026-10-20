CREATE TYPE activation_state AS ENUM (
    'ACTIVATING',
    'ACTIVE',
    'FAILED',
    'DEGRADED',
    'SUSPENDING',
    'SUSPENDED',
    'RESUMING',
    'STOPPING',
    'STOPPED'
    );

CREATE TYPE media_type AS ENUM (
    'AUDIO',
    'VIDEO',
    'SCREEN_SHARE',
    'SCREEN_SHARE_AUDIO'
    );

CREATE TYPE activation_work_kind AS ENUM (
    'ACTIVATE',
    'HEALTHCHECK'
    );

CREATE TABLE plugin_activation
(
    activation_id         UUID PRIMARY KEY,
    call_id               VARCHAR(255) NOT NULL CHECK (length(btrim(call_id)) > 0),
    plugin_id             VARCHAR(255) NOT NULL CHECK (length(btrim(plugin_id)) > 0),
    requested_version     VARCHAR(255) CHECK (length(btrim(requested_version)) > 0),
    requested_registry_id VARCHAR(255) CHECK (length(btrim(requested_registry_id)) > 0),
    requested_by          VARCHAR(255) NOT NULL CHECK (length(btrim(requested_by)) > 0),
    idempotency_key       VARCHAR(255) NOT NULL CHECK (length(btrim(idempotency_key)) > 0),
    resolved_registry_id  VARCHAR(255) CHECK (length(btrim(resolved_registry_id)) > 0),
    resolved_version      VARCHAR(255) CHECK (length(btrim(resolved_version)) > 0),
    state                 activation_state NOT NULL,
    state_revision        BIGINT       NOT NULL DEFAULT 1 CHECK (state_revision > 0),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    state_changed_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    failure_code          VARCHAR(64),
    CONSTRAINT activation_idempotency UNIQUE (call_id, idempotency_key),
    CONSTRAINT activation_selection_pair CHECK (
        (resolved_registry_id IS NULL) = (resolved_version IS NULL)
        ),
    CONSTRAINT activation_requested_version CHECK (
        requested_version IS NULL OR resolved_version IS NULL OR requested_version = resolved_version
        ),
    CONSTRAINT activation_requested_registry CHECK (
        requested_registry_id IS NULL OR resolved_registry_id IS NULL
            OR requested_registry_id = resolved_registry_id
        ),
    CONSTRAINT activation_active_selection CHECK (state <> 'ACTIVE' OR resolved_version IS NOT NULL),
    CONSTRAINT activation_failure CHECK ((state = 'FAILED') = (failure_code IS NOT NULL))
);

CREATE INDEX activation_call_idx ON plugin_activation (call_id, created_at, activation_id);
CREATE INDEX activation_inactive_idx ON plugin_activation (state, activation_id)
    WHERE state IN ('STOPPED', 'FAILED', 'SUSPENDED');

CREATE TABLE activation_route
(
    route_id        UUID PRIMARY KEY,
    activation_id   UUID        NOT NULL UNIQUE REFERENCES plugin_activation (activation_id),
    signal_dst      TEXT        NOT NULL CHECK (length(btrim(signal_dst)) > 0),
    healthcheck_url TEXT        NOT NULL CHECK (length(btrim(healthcheck_url)) > 0),
    media_types     media_type[] NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT route_media_types CHECK (
        array_position(media_types, NULL) IS NULL
        )
);

CREATE TABLE activation_state_history
(
    history_id    UUID PRIMARY KEY,
    activation_id UUID        NOT NULL REFERENCES plugin_activation (activation_id),
    revision      BIGINT      NOT NULL CHECK (revision > 0),
    from_state    activation_state,
    to_state      activation_state NOT NULL,
    changed_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason_code   VARCHAR(64),
    CONSTRAINT history_revision UNIQUE (activation_id, revision),
    CONSTRAINT history_initial CHECK ((revision = 1) = (from_state IS NULL)),
    CONSTRAINT history_transition CHECK (from_state IS NULL OR from_state <> to_state)
);

CREATE INDEX history_retention_idx ON activation_state_history (changed_at, activation_id);

CREATE TABLE activation_work
(
    activation_id               UUID PRIMARY KEY REFERENCES plugin_activation (activation_id),
    kind                        activation_work_kind NOT NULL,
    next_run_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempt_count               INTEGER     NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    consecutive_health_failures INTEGER     NOT NULL DEFAULT 0 CHECK (consecutive_health_failures >= 0),
    lease_until                 TIMESTAMPTZ,
    lease_token                 UUID,
    last_error_code             VARCHAR(64),
    CONSTRAINT work_lease_pair CHECK ((lease_until IS NULL) = (lease_token IS NULL))
);

CREATE INDEX work_due_idx ON activation_work (kind, next_run_at, activation_id);
CREATE INDEX work_lease_idx ON activation_work (lease_until) WHERE lease_until IS NOT NULL;
