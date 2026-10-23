package ru.itmo.pluginmanager.database

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.postgresql.PostgreSQLContainer

class PostgresTestResource : QuarkusTestResourceLifecycleManager {
    private val postgres = PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("plugin_manager")
        .withUsername("plugin_manager")
        .withPassword("plugin_manager")

    override fun start(): Map<String, String> {
        postgres.start()

        return mapOf(
            "database.host" to postgres.host,
            "database.port" to postgres.firstMappedPort.toString(),
            "database.name" to postgres.databaseName,
            "database.username" to postgres.username,
            "database.password" to postgres.password,
            "database.migration.username" to postgres.username,
            "database.migration.password" to postgres.password,
            "quarkus.datasource.migration.jdbc.url" to postgres.jdbcUrl,
            "quarkus.datasource.migration.username" to postgres.username,
            "quarkus.datasource.migration.password" to postgres.password,
        )
    }

    override fun stop() {
        postgres.stop()
    }
}
