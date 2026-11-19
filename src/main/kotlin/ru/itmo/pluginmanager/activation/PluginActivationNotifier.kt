package ru.itmo.pluginmanager.activation

import ru.itmo.pluginmanager.domain.PluginActivationMessage

/** Отправляет Plugin Backend начальное сообщение жизненного цикла активации. */
interface PluginActivationNotifier {
    /** Доставляет сообщение на заданный в манифесте endpoint либо сообщает об ошибке доставки. */
    suspend fun notify(endpoint: String, message: PluginActivationMessage)
}
