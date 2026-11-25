CREATE UNIQUE INDEX activation_call_plugin_running_idx ON plugin_activation (call_id, plugin_id)
    WHERE state NOT IN ('FAILED', 'STOPPED');
