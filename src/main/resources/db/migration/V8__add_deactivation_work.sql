ALTER TYPE activation_work_kind ADD VALUE 'DEACTIVATE';

ALTER TABLE activation_work
    ADD COLUMN requested_by VARCHAR(255);

UPDATE activation_work AS work
SET requested_by = activation.requested_by
FROM plugin_activation AS activation
WHERE work.activation_id = activation.activation_id;

ALTER TABLE activation_work
    ALTER COLUMN requested_by SET NOT NULL;
