package ru.itmo.pluginmanager.health

/** Проверка HTTP-доступности плагина без отправки сигналов или медиа. */
interface PluginHealthcheck {
    /** Выполняет GET без перенаправлений; только HTTP 200 в пределах таймаута означает успех. */
    suspend fun check(url: String): Boolean
}
