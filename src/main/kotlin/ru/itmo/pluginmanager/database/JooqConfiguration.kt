package ru.itmo.pluginmanager.database

import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions.DATABASE
import io.r2dbc.spi.ConnectionFactoryOptions.DRIVER
import io.r2dbc.spi.ConnectionFactoryOptions.HOST
import io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD
import io.r2dbc.spi.ConnectionFactoryOptions.PORT
import io.r2dbc.spi.ConnectionFactoryOptions.USER
import io.r2dbc.spi.ConnectionFactoryOptions.builder
import jakarta.enterprise.inject.Disposes
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration

@Singleton
class JooqConfiguration {
    @Produces
    @Singleton
    fun connectionPool(config: DatabaseConfig): ConnectionPool {
        val connectionFactory = ConnectionFactories.get(
            builder()
                .option(DRIVER, "postgresql")
                .option(HOST, config.host())
                .option(PORT, config.port())
                .option(DATABASE, config.name())
                .option(USER, config.username())
                .option(PASSWORD, config.password())
                .build()
        )

        val pool = config.pool()
        return ConnectionPool(
            ConnectionPoolConfiguration.builder(connectionFactory)
                .name("plugin-manager")
                .initialSize(pool.initialSize())
                .maxSize(pool.maxSize())
                .maxIdleTime(pool.maxIdleTime())
                .maxAcquireTime(pool.maxAcquireTime())
                .maxCreateConnectionTime(pool.maxCreateConnectionTime())
                .validationQuery(pool.validationQuery())
                .build()
        )
    }

    @Produces
    @Singleton
    fun dslContext(connectionPool: ConnectionPool): DSLContext = DSL.using(
        DefaultConfiguration()
            .set(connectionPool)
            .set(SQLDialect.POSTGRES)
    )

    fun disposeConnectionPool(@Disposes connectionPool: ConnectionPool) {
        connectionPool.dispose()
    }
}
