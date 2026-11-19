package ru.itmo.pluginmanager.persistence

import com.fasterxml.jackson.databind.JsonNode

/** Хранилище неизменяемых JSON Schema, адресуемых версией формата манифеста. */
interface ManifestVersionStore {
    /** Возвращает схему версии либо null, если версия ещё не зарегистрирована. */
    suspend fun find(version: String): JsonNode?

    /** Создаёт версию или подтверждает идемпотентный повтор идентичной схемы. */
    suspend fun create(version: String, schema: JsonNode): Boolean
}
