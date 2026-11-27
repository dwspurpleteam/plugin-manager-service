ALTER TABLE plugin_activation
    ADD COLUMN control_url TEXT,
    ADD COLUMN control_url_unknown BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE plugin_activation AS activation
SET control_url = route.signal_dst
FROM activation_route AS route
WHERE route.activation_id = activation.activation_id;

UPDATE plugin_activation
SET control_url_unknown = TRUE
WHERE control_url IS NULL
  AND resolved_version IS NOT NULL
  AND state IN ('ACTIVE', 'FAILED', 'SUSPENDED', 'STOPPING');
