package ru.itmo.pluginmanager.database

import io.smallrye.config.ConfigMapping
import java.time.Duration

@ConfigMapping(prefix = "database")
interface DatabaseConfig {
    fun host(): String

    fun port(): Int

    fun name(): String

    fun username(): String

    fun password(): String

    fun migration(): Credentials

    fun pool(): Pool

    interface Credentials {
        fun username(): String

        fun password(): String
    }

    interface Pool {
        fun initialSize(): Int

        fun maxSize(): Int

        fun maxIdleTime(): Duration

        fun maxAcquireTime(): Duration

        fun maxCreateConnectionTime(): Duration

        fun validationQuery(): String
    }
}
