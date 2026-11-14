package ru.itmo.pluginmanager.metrics

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test

@QuarkusTest
class PrometheusMetricsResourceTest {

    /**
     * Тест проверяет публикацию базовых JVM-метрик в формате Prometheus.
     * Ожидаемый результат: GET /q/metrics возвращает 200 и метрику использования памяти JVM.
     */
    @Test
    fun `prometheus endpoint exposes JVM metrics`() {
        given()
            .`when`()
            .get("/q/metrics")
            .then()
            .statusCode(200)
            .header("Content-Type", containsString("application/openmetrics-text"))
            .body(containsString("jvm_memory_used_bytes"))
            .body(containsString("plugin_manager_calls_total"))
            .body(containsString("plugin_manager_active_plugins"))
            .body(containsString("plugin_manager_failed_plugins"))
            .body(containsString("plugin_manager_healthcheck_first_failures_total"))
            .body(containsString("plugin_manager_healthcheck_second_failures_total"))
    }
}
