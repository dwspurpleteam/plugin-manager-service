package ru.itmo.pluginmanager.application

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import jakarta.enterprise.context.ApplicationScoped
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.PluginManagerException
import ru.itmo.pluginmanager.persistence.ManifestVersionStore

@ApplicationScoped
@Suppress("TooGenericExceptionCaught")
class DefaultManifestSchemaService(private val store: ManifestVersionStore) : ManifestSchemaService {
    override suspend fun register(schema: JsonNode): ManifestSchemaRegistration {
        if (!schema.isObject) invalidRequest("JSON Schema must be an object")
        try {
            val registry = registry()
            val metaSchema = registry.getSchema(
                SchemaLocation.of(SpecificationVersion.DRAFT_2020_12.dialectId),
            )
            if (metaSchema.validate(schema).isNotEmpty()) invalidRequest("Invalid JSON Schema")
            registry.getSchema(schema)
        } catch (exception: RuntimeException) {
            throw PluginManagerException(ErrorCode.INVALID_REQUEST, "Invalid JSON Schema", exception)
        }
        val version = schema.path("properties").path("manifest-version").path("const")
        if (!version.isTextual || version.asText().isBlank()) {
            invalidRequest("Manifest version must be a non-empty properties.manifest-version.const string")
        }
        return ManifestSchemaRegistration(version.asText(), store.create(version.asText(), schema))
    }

    override suspend fun validate(manifest: JsonNode) {
        val version = manifest.path("manifest-version")
        if (!version.isTextual || version.asText().isBlank()) invalidManifest("Manifest version is missing")
        val schemaNode = store.find(version.asText()) ?: invalidManifest("Manifest version is not supported")
        val messages = compile(schemaNode, ErrorCode.INVALID_MANIFEST).validate(manifest)
        if (messages.isNotEmpty()) invalidManifest("Manifest does not match its registered schema")
    }

    private fun compile(schema: JsonNode, code: ErrorCode): Schema = try {
        registry().getSchema(schema)
    } catch (exception: RuntimeException) {
        throw PluginManagerException(code, "Invalid JSON Schema", exception)
    }

    private fun registry(): SchemaRegistry =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
            builder.schemaCacheEnabled(false)
        }

    private fun invalidRequest(message: String): Nothing =
        throw PluginManagerException(ErrorCode.INVALID_REQUEST, message)

    private fun invalidManifest(message: String): Nothing =
        throw PluginManagerException(ErrorCode.INVALID_MANIFEST, message)
}
