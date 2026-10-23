package ru.itmo.pluginmanager.config

import io.smallrye.config.ConfigMapping
import java.net.URI
import java.time.Duration

/** Настройки реестров, фоновой обработки, кеша и очистки; длительности задаются в ISO-8601. */
@ConfigMapping(prefix = "plugin-manager")
interface PluginManagerConfig {
    fun registries(): Map<String, Registry>

    fun http(): Http

    fun activation(): Activation

    fun health(): Health

    fun worker(): Worker

    fun cache(): Cache

    fun cleanup(): Cleanup

    /** Адрес отдельного внешнего реестра. */
    interface Registry {
        fun url(): URI
    }

    /** Ограничения исходящих запросов к реестрам. */
    interface Http {
        fun connectTimeout(): Duration

        fun requestTimeout(): Duration

        fun searchTimeout(): Duration

        fun concurrency(): Int
    }

    /** Ограничения и повторы фоновой активации. */
    interface Activation {
        fun concurrency(): Int

        fun attempts(): Int

        fun retryDelays(): List<Duration>
    }

    /** Политика проверки активных плагинов. */
    interface Health {
        fun timeout(): Duration

        fun interval(): Duration

        fun failureThreshold(): Int

        fun concurrency(): Int
    }

    /** Частота захвата заданий и защита от конкурирующих обработчиков. */
    interface Worker {
        fun pollInterval(): Duration

        fun leaseDuration(): Duration
    }

    /** TTL манифестов и ограничение времени обращения в Redis. */
    interface Cache {
        fun ttl(): Duration

        fun timeout(): Duration
    }

    /** Очистка истории с сохранением текущей ревизии и необязательное удаление неактивных маршрутов. */
    interface Cleanup {
        fun historyRetention(): Duration

        fun interval(): Duration

        fun batchSize(): Int

        fun inactiveRoutesEnabled(): Boolean
    }
}
