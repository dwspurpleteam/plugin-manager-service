package ru.itmo.pluginmanager.persistence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.jooq.JSONB
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.table
import ru.itmo.pluginmanager.database.JooqTransaction
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.PluginManagerException

@ApplicationScoped
class JooqManifestVersionStore(private val tx: JooqTransaction, private val mapper: ObjectMapper) :
    ManifestVersionStore {
    private val manifestVersion = table("manifest_version")
    private val version = field("version", String::class.java)
    private val schemaField = field("schema", JSONB::class.java)

    override suspend fun find(version: String): JsonNode? = tx.transaction { dsl ->
        dsl.select(schemaField).from(manifestVersion)
            .where(this.version.eq(version)).awaitFirstOrNull()
            ?.value1()?.data()?.let(mapper::readTree)
    }

    override suspend fun create(version: String, schema: JsonNode): Boolean = tx.transaction { dsl ->
        val json = JSONB.valueOf(mapper.writeValueAsString(schema))
        val inserted = dsl.insertInto(manifestVersion)
            .columns(this.version, schemaField).values(version, json)
            .onConflict(this.version).doNothing().awaitSingle()
        if (inserted == 1) return@transaction true

        val existing = dsl.select(schemaField).from(manifestVersion)
            .where(this.version.eq(version)).awaitSingle().value1()
        if (existing != json) {
            throw PluginManagerException(
                ErrorCode.MANIFEST_VERSION_CONFLICT,
                "Manifest version is already registered with another schema",
            )
        }
        false
    }
}
