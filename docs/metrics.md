# Application metrics

Prometheus can scrape the Micrometer endpoint at `GET /q/metrics`. The endpoint
returns OpenMetrics text and contains standard JVM, process and HTTP server
metrics without business identifiers or request payloads.

## Plugin Manager metrics

| Metric | Type | Description |
| --- | --- | --- |
| `plugin_manager_calls_total` | counter | Number of newly created activation requests. Idempotent retries are not counted. |
| `plugin_manager_active_plugins` | gauge | Current number of plugin activations in `ACTIVE` state. |
| `plugin_manager_failed_plugins` | gauge | Number of plugin activations transitioned to `FAILED` during the current process lifetime. |
| `plugin_manager_healthcheck_first_failures_total` | counter | Health checks that failed on the first consecutive attempt. |
| `plugin_manager_healthcheck_second_failures_total` | counter | Health checks that failed on the second consecutive attempt. |

The active and failed gauges are updated on activation state transitions. The
healthcheck counters distinguish consecutive failures; a successful check resets
the consecutive-failure sequence.

## Scrape example

```shell
curl -H 'Accept: application/openmetrics-text' http://localhost:8080/q/metrics
```
