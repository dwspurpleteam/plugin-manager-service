package ru.itmo.pluginmanager.application

import com.fasterxml.jackson.databind.JsonNode

/** Регистрирует схемы и проверяет сырые манифесты до их преобразования в доменную модель. */
interface ManifestSchemaService {
    /** Проверяет и сохраняет схему, возвращая версию формата и признак создания. */
    suspend fun register(schema: JsonNode): ManifestSchemaRegistration

    /** Проверяет манифест по зарегистрированной схеме его версии. */
    suspend fun validate(manifest: JsonNode)
}

data class ManifestSchemaRegistration(val version: String, val created: Boolean)
