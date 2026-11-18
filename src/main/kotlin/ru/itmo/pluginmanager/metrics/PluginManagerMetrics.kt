package ru.itmo.pluginmanager.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.atomic.AtomicInteger

@ApplicationScoped
@Startup
class PluginManagerMetrics(registry: MeterRegistry) {
    private val calls = Counter.builder("plugin_manager_calls_total")
        .description("Number of newly registered calls")
        .register(registry)
    private val activePlugins = AtomicInteger()
    private val failedPlugins = AtomicInteger()
    private val firstHealthcheckFailures = Counter.builder("plugin_manager_healthcheck_first_failures_total")
        .description("Number of failed first healthcheck attempts")
        .register(registry)
    private val secondHealthcheckFailures = Counter.builder("plugin_manager_healthcheck_second_failures_total")
        .description("Number of failed second healthcheck attempts")
        .register(registry)

    init {
        registry.gauge("plugin_manager_active_plugins", activePlugins)
        registry.gauge("plugin_manager_failed_plugins", failedPlugins)
    }

    fun callRegistered() = calls.increment()

    fun pluginActivated() = activePlugins.incrementAndGet()

    fun pluginFailed(wasActive: Boolean) {
        if (wasActive) activePlugins.decrementAndGet()
        failedPlugins.incrementAndGet()
    }

    fun healthcheckFailed(previousConsecutiveFailures: Int) {
        when (previousConsecutiveFailures) {
            0 -> firstHealthcheckFailures.increment()
            1 -> secondHealthcheckFailures.increment()
        }
    }
}
