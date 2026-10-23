ALTER TABLE activation_route
    ADD COLUMN allowed_event_types TEXT[],
    ADD COLUMN allowed_event_groups TEXT[];

ALTER TABLE activation_route
    ADD CONSTRAINT route_allowed_event_types CHECK (array_position(allowed_event_types, NULL) IS NULL),
    ADD CONSTRAINT route_allowed_event_groups CHECK (array_position(allowed_event_groups, NULL) IS NULL),
    ADD CONSTRAINT route_event_permissions_precedence CHECK (
        allowed_event_types IS NULL OR allowed_event_groups IS NULL
    );
