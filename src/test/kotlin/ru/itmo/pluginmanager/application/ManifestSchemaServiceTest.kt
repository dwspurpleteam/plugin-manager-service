package ru.itmo.pluginmanager.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.PluginManagerException
import ru.itmo.pluginmanager.persistence.ManifestVersionStore

class ManifestSchemaServiceTest {
    private val mapper = ObjectMapper()
    private val store = MemoryManifestVersionStore()
    private val service = DefaultManifestSchemaService(store)

    /**
     * Тест проверяет извлечение версии из const и идемпотентную регистрацию схемы.
     * Ожидаемый результат: версия извлекается из manifest-version, идентичный повтор не создаёт новую запись.
     */
    @Test
    fun `registers schema version idempotently`() = runBlocking {
        val schema = schema("1.1")
        val created = service.register(schema)
        val repeated = service.register(schema.deepCopy())

        assertEquals("1.1", created.version)
        assertTrue(created.created)
        assertFalse(repeated.created)
    }

    /**
     * Тест проверяет обязательность однозначного строкового const версии манифеста.
     * Ожидаемый результат: схема без такого ограничения отклоняется как INVALID_REQUEST.
     */
    @Test
    fun `rejects schema without version const`() {
        val invalid = mapper.readTree("""{"type":"object","properties":{"manifest-version":{"type":"string"}}}""")
        val exception = assertThrows(PluginManagerException::class.java) {
            runBlocking { service.register(invalid) }
        }
        assertEquals(ErrorCode.INVALID_REQUEST, exception.code)
    }

    /**
     * Тест проверяет выбор сохранённой схемы по manifest-version и валидацию содержимого.
     * Ожидаемый результат: корректный манифест принят, неизвестная версия и нарушение схемы отклонены.
     */
    @Test
    fun `validates manifest with stored version schema`() = runBlocking {
        service.register(schema("0.1"))
        service.validate(mapper.readTree("""{"manifest-version":"0.1"}"""))

        val invalid = assertThrows(PluginManagerException::class.java) {
            runBlocking { service.validate(mapper.readTree("""{"manifest-version":"0.1","extra":true}""")) }
        }
        assertEquals(ErrorCode.INVALID_MANIFEST, invalid.code)
        val unsupported = assertThrows(PluginManagerException::class.java) {
            runBlocking { service.validate(mapper.readTree("""{"manifest-version":"1.1"}""")) }
        }
        assertEquals(ErrorCode.INVALID_MANIFEST, unsupported.code)
    }

    private fun schema(version: String): JsonNode = mapper.readTree(
        """
        {
          "${'$'}schema":"https://json-schema.org/draft/2020-12/schema",
          "type":"object",
          "properties":{"manifest-version":{"const":"$version"}},
          "required":["manifest-version"],
          "additionalProperties":false
        }
        """.trimIndent(),
    )
}

private class MemoryManifestVersionStore : ManifestVersionStore {
    private val schemas = mutableMapOf<String, JsonNode>()

    override suspend fun find(version: String): JsonNode? = schemas[version]

    override suspend fun create(version: String, schema: JsonNode): Boolean {
        val existing = schemas[version]
        if (existing == schema) return false
        if (existing != null) {
            throw PluginManagerException(ErrorCode.MANIFEST_VERSION_CONFLICT, "Conflict")
        }
        schemas[version] = schema
        return true
    }
}
