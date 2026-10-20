package ru.itmo.pluginmanager.config

import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.validateHttpUrl

@Startup
@ApplicationScoped
class RuntimePolicyValidator(config: PluginManagerConfig) {
    init {
        val durations = listOf(
            config.http().connectTimeout(), config.http().requestTimeout(), config.http().searchTimeout(),
            config.health().timeout(), config.health().interval(), config.worker().pollInterval(),
            config.worker().leaseDuration(), config.cache().ttl(), config.cache().timeout(),
            config.cleanup().historyRetention(), config.cleanup().interval(),
        ) + config.activation().retryDelays()
        require(durations.all { it.toMillis() > 0 }) { "Durations must be at least one millisecond" }
        val limits = listOf(
            config.http().concurrency(),
            config.activation().concurrency(),
            config.activation().attempts(),
            config.health().concurrency(),
            config.health().failureThreshold(),
            config.cleanup().batchSize(),
        )
        require(limits.all { it > 0 }) { "Limits must be positive" }
        require(
            config.activation().retryDelays().size >= config.activation().attempts() - 1,
        ) { "Not enough retry delays" }
        val minimumLease = config.http().searchTimeout().plus(config.http().requestTimeout())
            .plus(config.health().timeout()).plus(config.cache().timeout().multipliedBy(2))
            .plus(config.worker().pollInterval())
        require(config.worker().leaseDuration() > minimumLease) { "Lease must exceed execution deadlines" }
        config.registries().values.forEach { validateHttpUrl(it.url().toString(), ErrorCode.INVALID_REQUEST) }
    }
}
