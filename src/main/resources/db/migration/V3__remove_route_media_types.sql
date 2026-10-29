ALTER TABLE activation_route
    DROP COLUMN IF EXISTS media_types;

DROP TYPE IF EXISTS media_type;
